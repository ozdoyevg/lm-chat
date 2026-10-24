package dev.lmchat.ui

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.content.ContentFactory
import com.intellij.util.ui.JBUI
import dev.lmchat.LmConfigurable
import dev.lmchat.LmSettings
import dev.lmchat.api.OpenAiClient
import dev.lmchat.api.StreamListener
import dev.lmchat.tools.ProjectTools
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.awt.GridLayout
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import javax.swing.DefaultComboBoxModel
import javax.swing.JButton
import javax.swing.JPanel

private const val MAX_ROUNDS = 10

class ChatToolWindowFactory : ToolWindowFactory, DumbAware {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val panel = ChatPanel(project)
        val content = ContentFactory.SERVICE.getInstance().createContent(panel, "", false)
        @Suppress("UsePropertyAccessSyntax")
        content.setDisposer(panel)
        toolWindow.contentManager.addContent(content)
    }
}

class ChatPanel(project: Project) : JPanel(BorderLayout()), Disposable {

    private val settings get() = LmSettings.get().state
    private val tools = ProjectTools(project)
    private val history = mutableListOf<JsonObject>()

    private val modelBox = ComboBox<ModelItem>()
    @Volatile private var loadGen = 0
    private val toolsBox = JBCheckBox("Tools", LmSettings.get().state.useTools)
    private val transcript = JBTextArea().apply {
        isEditable = false; lineWrap = true; wrapStyleWord = true; margin = JBUI.insets(6)
    }
    private val input = JBTextArea(3, 20).apply { lineWrap = true; wrapStyleWord = true }
    private val sendBtn = JButton("Send")
    private val stopBtn = JButton("Stop").apply { isEnabled = false }

    @Volatile private var client: OpenAiClient? = null
    @Volatile private var stopRequested = false
    private var busy = false
    private var loadingModels = false

    init {
        val modelRow = JPanel(BorderLayout(4, 0)).apply {
            add(modelBox, BorderLayout.CENTER)
            add(JButton("↻").apply { toolTipText = "Reload models"; addActionListener { loadModels() } }, BorderLayout.EAST)
            add(JButton("×").apply { addActionListener { clearChat() } })
            add(JButton("☰").apply {
                addActionListener {
                    ShowSettingsUtil.getInstance().showSettingsDialog(project, LmConfigurable::class.java)
                }
            })
        }
        val optionsRow = JPanel(FlowLayout(FlowLayout.LEFT, 4, 0)).apply {
            add(toolsBox)
        }
        add(JPanel(GridLayout(2, 1, 0, 2)).apply {
            border = JBUI.Borders.empty(4)
            add(modelRow); add(optionsRow)
        }, BorderLayout.NORTH)

        add(JBScrollPane(transcript), BorderLayout.CENTER)

        add(JPanel(BorderLayout(4, 0)).apply {
            border = JBUI.Borders.empty(4)
            add(JBScrollPane(input), BorderLayout.CENTER)
            add(JPanel(GridLayout(2, 1, 0, 2)).apply { add(sendBtn); add(stopBtn) }, BorderLayout.EAST)
        }, BorderLayout.SOUTH)

        sendBtn.addActionListener { onSend() }
        stopBtn.addActionListener { stopRequested = true; client?.cancel() }
        toolsBox.addActionListener { settings.useTools = toolsBox.isSelected }
        modelBox.addActionListener {
            if (!loadingModels) (modelBox.selectedItem as? ModelItem)?.let { settings.lastSelection = it.key }
        }
        input.addKeyListener(object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent) {
                if (e.keyCode == KeyEvent.VK_ENTER && !e.isShiftDown) { e.consume(); onSend() }
            }
        })

        loadModels()
    }

    // ---- UI helpers (EDT) ----

    private fun ui(r: () -> Unit) =
        ApplicationManager.getApplication().invokeLater({ r() }, ModalityState.any())

    private fun append(s: String) = ui {
        transcript.append(s)
        transcript.caretPosition = transcript.document.length
    }

    private fun setBusy(b: Boolean) { busy = b; sendBtn.isEnabled = !b; stopBtn.isEnabled = b }

    private fun clearChat() { if (!busy) { history.clear(); transcript.text = "" } }

    // ---- models ----
    private fun loadModels() {
        val gen = ++loadGen
        
        //val providers = settings.providers.map { Provider(it.id, it.name, it.baseUrl, it.modelFilter) }
        val providers = settings.providers.toList()
        
        val last = settings.lastSelection
        loadingModels = true
        modelBox.model = DefaultComboBoxModel<ModelItem>()
        loadingModels = false

        ApplicationManager.getApplication().executeOnPooledThread {
            for (p in providers) {
                try {
                    val models = OpenAiClient(p.baseUrl, LmSettings.apiKey(p.id)).listModels(p.modelFilter)
                    val items = models.map { ModelItem(p.id, p.name, it) }
                    ui {
                        if (gen != loadGen) return@ui
                        loadingModels = true
                        items.forEach { modelBox.addItem(it) }
                        items.firstOrNull { it.key == last }?.let { modelBox.selectedItem = it }
                        loadingModels = false
                        if (items.isEmpty()) append("\n[info] ${p.name}: no models matched.\n")
                    }
                } catch (e: Exception) {
                    append("\n[error] ${p.name}: ${e.message ?: e}\n")
                }
            }
        }
    }

    private fun onSend() {
        if (busy) return
        val text = input.text.trim()
        if (text.isEmpty()) return
        val item = modelBox.selectedItem as? ModelItem
        if (item == null) {
            append("\n[error] No model selected. Check Settings and press ↻.\n"); return
        }
        input.text = ""
        startTurn(text, item)
    }

    private fun startTurn(userText: String, item: ModelItem) {
        val s = settings
        val provider = s.providers.firstOrNull { it.id == item.providerId }
        if (provider == null) {
            append("\n[error] That provider was removed. Press ↻.\n"); return
        }
        val url = provider.baseUrl
        val providerId = provider.id
        val system = s.systemPrompt
        val temperature = s.temperature
        val useTools = toolsBox.isSelected

        stopRequested = false
        setBusy(true)
        history.add(message("user", userText))
        append("\n\nYou: $userText\n\nAssistant: ")

        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val c = OpenAiClient(url, LmSettings.apiKey(providerId))
                client = c
                agentLoop(c, item.model, system, temperature, useTools)
            } catch (e: Exception) {
                append("\n[error] ${e.message ?: e}\n")
            } finally {
                ui { setBusy(false) }
            }
        }
    }

    private fun agentLoop(c: OpenAiClient, model: String, system: String, temperature: Double, useTools: Boolean) {
        val listener = object : StreamListener {
            private var thinking = false
            override fun onText(chunk: String) = append(chunk)
            override fun onReasoning(chunk: String) {
                if (!thinking) { thinking = true; append("(thinking…) ") }
            }
        }

        for (round in 1..MAX_ROUNDS) {
            if (stopRequested) { append("\n[stopped]\n"); return }

            val messages = ArrayList<JsonObject>()
            if (system.isNotBlank()) messages.add(message("system", system))
            messages.addAll(history)

            val res = c.streamChat(model, messages, if (useTools) tools.specs else null, temperature, listener)
            if (res.cancelled) {
                if (res.text.isNotEmpty()) history.add(message("assistant", res.text))
                append("\n[stopped]\n")
                return
            }

            val assistant = message("assistant", res.text)
            if (res.toolCalls.isNotEmpty()) {
                val arr = JsonArray()
                res.toolCalls.forEach { tc ->
                    val fn = JsonObject().apply { addProperty("name", tc.name); addProperty("arguments", tc.arguments) }
                    arr.add(JsonObject().apply {
                        addProperty("id", tc.id); addProperty("type", "function"); add("function", fn)
                    })
                }
                assistant.add("tool_calls", arr)
            }
            history.add(assistant)
            if (res.toolCalls.isEmpty()) return

            for (tc in res.toolCalls) {
                append("\n⚙ ${tc.name}(${tc.arguments.take(160)})\n")
                history.add(toolMessage(tc.id, tools.execute(tc.name, tc.arguments)))
            }
            append("\nAssistant: ")
        }
        append("\n[stopped: too many tool rounds]\n")
    }

    override fun dispose() { client?.cancel() }
}

class ModelItem(val providerId: String, val providerName: String, val model: String) {
    val key: String get() = "$providerId::$model"
    override fun toString() = "$providerName / $model"
}

private fun message(role: String, content: String) =
    JsonObject().apply { addProperty("role", role); addProperty("content", content) }

private fun toolMessage(id: String, content: String) =
    JsonObject().apply { addProperty("role", "tool"); addProperty("tool_call_id", id); addProperty("content", content) }