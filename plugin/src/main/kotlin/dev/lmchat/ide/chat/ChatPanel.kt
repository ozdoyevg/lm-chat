package dev.lmchat.ide.chat

import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.Project
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import dev.lmchat.core.agent.AgentConfig
import dev.lmchat.core.agent.AgentListener
import dev.lmchat.core.agent.ChatSession
import dev.lmchat.core.agent.SessionTitles
import dev.lmchat.core.llm.OpenAiCompatibleClient
import dev.lmchat.core.provider.Provider
import dev.lmchat.core.tools.ChangeApprover
import dev.lmchat.ide.chat.view.ChatView
import dev.lmchat.ide.settings.IdeCredentials
import dev.lmchat.ide.settings.LmSettings
import dev.lmchat.ide.settings.SettingsConfigurable
import dev.lmchat.ide.theme.ChatSettingsBus
import dev.lmchat.ide.tools.IdeTools
import java.awt.BorderLayout
import java.awt.Font
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import javax.swing.BorderFactory
import javax.swing.JPanel

/** The tool window content: title bar, transcript and composer. Connects the UI to the core [ChatSession]. */
class ChatPanel(project: Project) : JPanel(BorderLayout()), Disposable {

    private val settings get() = LmSettings.get().state
    private val session = ChatSession(IdeTools.createHost(project, ChangeApprover { askApproval(it) }))

    private val view = ChatView()
    private val composer = ComposerPanel(
        onSend = { text -> sendMessage(text) },
        onStop = { stopTurn() },
        onReloadModels = { loadModels() }
    )
    private val titleLabel = JBLabel("New chat").apply {
        font = font.deriveFont(Font.BOLD)
        border = JBUI.Borders.empty(0, 6)
    }

    private val agentListener = object : AgentListener {
        override fun onAssistantText(chunk: String) = onUiThread { view.assistantText(chunk) }
        override fun onThinking() = onUiThread { view.thinking() }
        override fun onToolCall(name: String, argumentsJson: String) = onUiThread { view.toolCall(name, argumentsJson) }
        override fun onToolResult(summary: String, failed: Boolean) = onUiThread { view.toolResult(summary, failed) }
        override fun onNotice(message: String) = onUiThread { view.info(message) }
    }
    private val onSettingsChanged: () -> Unit = { onUiThread { applyAppearance() } }

    private var busy = false
    private var sessionNumber = 0
    private var modelLoadGeneration = 0
    private var contextLookupGeneration = 0

    init {
        val titleToolbar = ActionManager.getInstance().createActionToolbar(
            "LmChatToolbar",
            DefaultActionGroup().apply {
                add(iconAction("New session", AllIcons.General.Add, enabled = { !busy }) { newSession() })
                add(iconAction("Settings", AllIcons.General.GearPlain) {
                    ShowSettingsUtil.getInstance().showSettingsDialog(project, SettingsConfigurable::class.java)
                })
            },
            true
        ).apply {
            targetComponent = this@ChatPanel
            setReservePlaceAutoPopupIcon(false)
            component.border = JBUI.Borders.empty()
        }

        add(JPanel(BorderLayout(4, 0)).apply {
            border = BorderFactory.createCompoundBorder(
                JBUI.Borders.customLine(JBColor.border(), 0, 0, 1, 0),
                JBUI.Borders.empty(4, 6, 4, 2)
            )
            add(titleLabel, BorderLayout.CENTER)
            add(titleToolbar.component, BorderLayout.EAST)
        }, BorderLayout.NORTH)
        add(view, BorderLayout.CENTER)
        add(composer, BorderLayout.SOUTH)

        composer.modelPicker.onChange = { item, byUser ->
            if (byUser) settings.lastSelection = item.key
            updateEstimatedUsage()
            lookUpContextWindow()
        }

        ChatSettingsBus.add(onSettingsChanged)
        if (System.getProperty("lmchat.demo") == "true") view.demo()
        loadModels()
    }

    private fun onUiThread(action: () -> Unit) =
        ApplicationManager.getApplication().invokeLater({ action() }, ModalityState.any())

    private fun applyAppearance() {
        composer.applyAppearance()
        view.applyTheme()
        revalidate()
        repaint()
    }

    private fun setBusy(value: Boolean) {
        busy = value
        composer.setBusy(value)
    }

    private fun setTitle(title: String) {
        titleLabel.text = title
        titleLabel.toolTipText = title
    }

    private fun newSession() {
        if (busy) return
        sessionNumber++
        session.clear()
        view.clear()
        setTitle("New chat")
        composer.contextIndicator.setUsed(0, isEstimate = false)
    }

    private fun stopTurn() {
        session.requestStop()
        view.denyPending()
    }

    /** Called on the worker thread by the file tools; shows Allow/Deny in the chat and waits for the click. */
    private fun askApproval(summary: String): Boolean {
        val answer = CompletableFuture<Boolean>()
        onUiThread { view.requestApproval(summary) { allowed -> answer.complete(allowed) } }
        return try {
            answer.get(15, TimeUnit.MINUTES)
        } catch (e: Exception) {
            onUiThread { view.denyPending() }
            false
        }
    }

    // ---- models and context window ----

    private fun loadModels() {
        val generation = ++modelLoadGeneration
        val providers = settings.providers.map { it.copy() }
        val lastSelection = settings.lastSelection
        composer.modelPicker.clear()

        ApplicationManager.getApplication().executeOnPooledThread {
            for (provider in providers) {
                try {
                    val client = OpenAiCompatibleClient(provider.baseUrl, IdeCredentials.apiKey(provider.id))
                    val items = client.listModelInfos(provider.modelFilter)
                        .map { ModelItem(provider.id, provider.name, it.id, it.contextLength) }
                    onUiThread {
                        if (generation != modelLoadGeneration) return@onUiThread
                        composer.modelPicker.add(items, lastSelection)
                        if (items.isEmpty()) view.info("${provider.name}: no models matched.")
                    }
                } catch (e: Exception) {
                    onUiThread { view.error("${provider.name}: ${e.message ?: e}") }
                }
            }
        }
    }

    /** Looks up the model's context window; local servers can also report what it is currently loaded with. */
    private fun lookUpContextWindow() {
        val item = composer.modelPicker.selected ?: return
        val provider = settings.providers.firstOrNull { it.id == item.providerId }?.copy() ?: return
        val generation = ++contextLookupGeneration
        composer.contextIndicator.setModelLimit(item.contextLength)

        ApplicationManager.getApplication().executeOnPooledThread {
            val info = try {
                OpenAiCompatibleClient(provider.baseUrl, IdeCredentials.apiKey(provider.id)).contextInfo(item.model)
            } catch (e: Exception) {
                null
            }
            if (info != null) onUiThread {
                if (generation == contextLookupGeneration) composer.contextIndicator.setLocalServerInfo(info)
            }
        }
    }

    private fun updateEstimatedUsage() {
        val tokens = if (session.isEmpty) 0 else session.estimateTokens(settings.systemPrompt)
        composer.contextIndicator.setUsed(tokens, isEstimate = !session.isEmpty)
    }

    // ---- sending ----

    private fun sendMessage(text: String): Boolean {
        if (busy) return false
        val item = composer.modelPicker.selected
        if (item == null) {
            view.error("No model selected. Check Settings and reload."); return false
        }
        val provider = settings.providers.firstOrNull { it.id == item.providerId }
        if (provider == null) {
            view.error("That provider was removed. Reload models."); return false
        }
        startTurn(text, item.model, provider.copy())
        return true
    }

    private fun startTurn(userText: String, model: String, provider: Provider) {
        val state = settings
        val config = AgentConfig(model, state.systemPrompt, state.temperature, state.useTools)
        val autoTitle = state.autoTitle
        val isFirstTurn = session.isEmpty
        val sessionAtStart = sessionNumber

        if (isFirstTurn) setTitle(SessionTitles.fromFirstMessage(userText))
        setBusy(true)
        session.beginTurn(userText)
        updateEstimatedUsage()
        view.userMessage(userText)
        view.beginAssistant()

        ApplicationManager.getApplication().executeOnPooledThread {
            var client: OpenAiCompatibleClient? = null
            var completed = false
            var reportedTokens: Int? = null
            try {
                client = OpenAiCompatibleClient(provider.baseUrl, IdeCredentials.apiKey(provider.id))
                val result = session.runTurn(client, config, agentListener)
                completed = result.completed
                reportedTokens = result.reportedTokens
            } catch (e: Exception) {
                onUiThread { view.error(e.message ?: e.toString()) }
            } finally {
                onUiThread {
                    view.endAssistant()
                    setBusy(false)
                    val reported = reportedTokens
                    if (reported != null) composer.contextIndicator.setUsed(reported, isEstimate = false)
                    else updateEstimatedUsage()
                    lookUpContextWindow()    // the server may have loaded the model for this request
                }
            }
            if (isFirstTurn && autoTitle && completed && !session.stopWasRequested && client != null) {
                session.suggestTitle(client, model)?.let { title ->
                    onUiThread { if (sessionAtStart == sessionNumber) setTitle(title) }
                }
            }
        }
    }

    override fun dispose() {
        ChatSettingsBus.remove(onSettingsChanged)
        session.requestStop()
        view.denyPending()
    }
}