package dev.lmchat.ui

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.util.IconLoader
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.content.ContentFactory
import com.intellij.util.ui.JBUI
import dev.lmchat.LmConfigurable
import dev.lmchat.LmSettings
import dev.lmchat.Provider
import dev.lmchat.api.OpenAiClient
import dev.lmchat.api.StreamListener
import dev.lmchat.tools.ProjectTools
import java.awt.BasicStroke
import java.awt.BorderLayout
import java.awt.CardLayout
import java.awt.Color
import java.awt.Cursor
import java.awt.Dimension
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.DefaultComboBoxModel
import javax.swing.Icon
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.Timer
import javax.swing.BorderFactory
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

private const val MAX_ROUNDS = 20

class ChatToolWindowFactory : ToolWindowFactory, DumbAware {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val panel = ChatPanel(project)
        val content = ContentFactory.SERVICE.getInstance().createContent(panel, "", false)
        content.setDisposer(panel)
        toolWindow.contentManager.addContent(content)
    }
}

class ModelItem(val providerId: String, val providerName: String, val model: String) {
    val key: String get() = "$providerId::$model"
    override fun toString() = "$providerName / $model"
}

/** Stop icon with a spinning ring around it. The animation only runs while the button is visible. */
class StopButton(private val onStop: () -> Unit) : JComponent() {
    private val icon = IconLoader.getIcon("/icons/stop.svg", StopButton::class.java)
    private var angle = 0
    private val timer = Timer(30) { angle = (angle + 12) % 360; repaint() }

    init {
        val d = Dimension(JBUI.scale(30), JBUI.scale(30))
        preferredSize = d
        minimumSize = d
        toolTipText = "Stop"
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) = onStop()
        })
    }

    override fun setVisible(aFlag: Boolean) {
        super.setVisible(aFlag)
        if (aFlag) timer.start() else timer.stop()
    }

    override fun removeNotify() {
        timer.stop(); super.removeNotify()
    }

    override fun paintComponent(g: Graphics) {
        val g2 = g.create() as Graphics2D
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            val cx = width / 2
            val cy = height / 2
            icon.paintIcon(this, g2, cx - icon.iconWidth / 2, cy - icon.iconHeight / 2)
            val r = minOf(width, height) / 2 - JBUI.scale(3)
            g2.color = JBColor(Color(0x3574F0), Color(0x548AF7))
            g2.stroke = BasicStroke(JBUI.scale(3).toFloat(), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
            g2.drawArc(cx - r, cy - r, 2 * r, 2 * r, angle, 100)
        } finally {
            g2.dispose()
        }
    }
}

class ChatPanel(project: Project) : JPanel(BorderLayout()), Disposable {

    private val settings get() = LmSettings.get().state
    private val tools = ProjectTools(project) { askApproval(it) }
    private val history = mutableListOf<JsonObject>()

    private val modelBox = ComboBox<ModelItem>()
    private val view = ChatView()
    private val input = JBTextArea(3, 20).apply {
        lineWrap = true
        wrapStyleWord = true
        border = JBUI.Borders.empty(6, 8)
    }
    private val inputScroll = JBScrollPane(input).apply {
        border = JBUI.Borders.customLine(JBColor.border(), 1)
    }

    private val sendBtn = JButton(IconLoader.getIcon("/icons/send.svg", ChatPanel::class.java)).apply {
        toolTipText = "Send (Enter)"
        isBorderPainted = false
        isContentAreaFilled = false
        isFocusable = false
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        val d = Dimension(JBUI.scale(30), JBUI.scale(30))
        preferredSize = d
        minimumSize = d
    }
    private val stopBtn = StopButton { stop() }
    private val actionHolder = JPanel(CardLayout()).apply {
        add(sendBtn, "send")
        add(stopBtn, "stop")
    }

    @Volatile
    private var client: OpenAiClient? = null
    @Volatile
    private var stopRequested = false
    @Volatile
    private var loadGen = 0
    private var busy = false
    private var loadingModels = false

    init {
        val toolbar = ActionManager.getInstance().createActionToolbar(
            "LmChatToolbar",
            DefaultActionGroup().apply {
                add(iconAction("Reload models", AllIcons.Actions.Refresh) { loadModels() })
                add(iconAction("Clear chat", AllIcons.Actions.GC, enabled = { !busy }) { clearChat() })
                add(iconAction("Settings", AllIcons.General.GearPlain) {
                    ShowSettingsUtil.getInstance().showSettingsDialog(project, LmConfigurable::class.java)
                })
            },
            true
        ).apply {
            targetComponent = this@ChatPanel
            setReservePlaceAutoPopupIcon(false)    // removes the empty space reserved for the "»" overflow icon
            component.border = JBUI.Borders.empty()
        }

        // top panel with a line underneath
        add(JPanel(BorderLayout(4, 0)).apply {
            border = BorderFactory.createCompoundBorder(
                JBUI.Borders.customLine(JBColor.border(), 0, 0, 1, 0),
                JBUI.Borders.empty(4, 6, 4, 2)
            )
            add(modelBox, BorderLayout.CENTER)
            add(toolbar.component, BorderLayout.EAST)
        }, BorderLayout.NORTH)

        add(view, BorderLayout.CENTER)

        // bottom panel with a line above it
        add(JPanel(BorderLayout(6, 0)).apply {
            border = BorderFactory.createCompoundBorder(
                JBUI.Borders.customLine(JBColor.border(), 1, 0, 0, 0),
                JBUI.Borders.empty(8)
            )
            add(inputScroll, BorderLayout.CENTER)
            add(JPanel(BorderLayout()).apply { add(actionHolder, BorderLayout.SOUTH) }, BorderLayout.EAST)
        }, BorderLayout.SOUTH)

        sendBtn.addActionListener { onSend() }
        modelBox.addActionListener {
            if (!loadingModels) (modelBox.selectedItem as? ModelItem)?.let { settings.lastSelection = it.key }
        }
        input.addKeyListener(object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent) {
                if (e.keyCode == KeyEvent.VK_ENTER && !e.isShiftDown) {
                    e.consume(); onSend()
                }
            }
        })

        loadModels()
    }

    private fun iconAction(text: String, icon: Icon, enabled: () -> Boolean = { true }, action: () -> Unit) =
        object : DumbAwareAction(text, text, icon) {
            override fun actionPerformed(e: AnActionEvent) = action()
            override fun update(e: AnActionEvent) {
                e.presentation.isEnabled = enabled()
            }
        }

    // ---- UI helpers ----

    private fun ui(r: () -> Unit) =
        ApplicationManager.getApplication().invokeLater({ r() }, ModalityState.any())

    private fun setBusy(b: Boolean) {
        busy = b
        (actionHolder.layout as CardLayout).show(actionHolder, if (b) "stop" else "send")
    }

    private fun clearChat() {
        if (!busy) {
            history.clear(); view.clear()
        }
    }

    private fun stop() {
        stopRequested = true
        client?.cancel()
        view.denyPending()
    }

    /** Called on the worker thread by the tools; shows Allow/Deny in the chat and waits for the click. */
    private fun askApproval(summary: String): Boolean {
        val answer = CompletableFuture<Boolean>()
        ui { view.requestApproval(summary) { ok -> answer.complete(ok) } }
        return try {
            answer.get(15, TimeUnit.MINUTES)
        } catch (e: Exception) {
            ui { view.denyPending() }
            false
        }
    }

    override fun dispose() {
        client?.cancel(); view.denyPending()
    }

    // ---- models ----

    private fun loadModels() {
        val gen = ++loadGen
        val providers = settings.providers.map { Provider(it.id, it.name, it.baseUrl, it.modelFilter) }
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
                        if (items.isEmpty()) view.info("${p.name}: no models matched.")
                    }
                } catch (e: Exception) {
                    ui { view.error("${p.name}: ${e.message ?: e}") }
                }
            }
        }
    }

    // ---- chat ----

    private fun onSend() {
        if (busy) return
        val text = input.text.trim()
        if (text.isEmpty()) return
        val item = modelBox.selectedItem as? ModelItem
        if (item == null) {
            view.error("No model selected. Check Settings and reload."); return
        }
        input.text = ""
        startTurn(text, item)
    }

    private fun startTurn(userText: String, item: ModelItem) {
        val s = settings
        val provider = s.providers.firstOrNull { it.id == item.providerId }
        if (provider == null) {
            view.error("That provider was removed. Reload models."); return
        }
        val url = provider.baseUrl
        val providerId = provider.id
        val system = s.systemPrompt
        val temperature = s.temperature
        val useTools = s.useTools

        stopRequested = false
        setBusy(true)
        history.add(message("user", userText))
        view.userMessage(userText)
        view.beginAssistant()

        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val c = OpenAiClient(url, LmSettings.apiKey(providerId))
                client = c
                agentLoop(c, item.model, system, temperature, useTools)
            } catch (e: Exception) {
                ui { view.error(e.message ?: e.toString()) }
            } finally {
                ui { view.endAssistant(); setBusy(false) }
            }
        }
    }

    private fun agentLoop(c: OpenAiClient, model: String, system: String, temperature: Double, useTools: Boolean) {
        val listener = object : StreamListener {
            override fun onText(chunk: String) = ui { view.assistantText(chunk) }
            override fun onReasoning(chunk: String) = ui { view.thinking() }
        }

        for (round in 1..MAX_ROUNDS) {
            if (stopRequested) {
                ui { view.info("Stopped.") }; return
            }

            val messages = ArrayList<JsonObject>()
            if (system.isNotBlank()) messages.add(message("system", system))
            messages.addAll(history)

            val res = c.streamChat(model, messages, if (useTools) tools.specs else null, temperature, listener)
            if (res.cancelled) {
                if (res.text.isNotEmpty()) history.add(message("assistant", res.text))
                ui { view.info("Stopped.") }
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
                ui { view.toolCall(tc.name, tc.arguments) }
                val out = tools.execute(tc.name, tc.arguments)
                history.add(toolMessage(tc.id, out))
                val failed = out.startsWith("Error") || out.startsWith("The user denied")
                val summary = if (out.length <= 120) out.replace('\n', ' ') else "${out.length} chars returned"
                ui { view.toolResult(summary, failed) }
            }
        }
        ui { view.info("Stopped: too many tool rounds.") }
    }
}

private fun formatArgs(json: String): String = try {
    JsonParser.parseString(json).asJsonObject.entrySet().joinToString("  ") { (k, v) ->
        val s = (if (v.isJsonPrimitive) v.asString else v.toString()).replace('\n', ' ')
        "$k=" + if (s.length > 60) s.take(60) + "…" else s
    }
} catch (e: Exception) {
    json.take(120)
}

private fun message(role: String, content: String) =
    JsonObject().apply { addProperty("role", role); addProperty("content", content) }

private fun toolMessage(id: String, content: String) =
    JsonObject().apply { addProperty("role", "tool"); addProperty("tool_call_id", id); addProperty("content", content) }