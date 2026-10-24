package dev.lmchat.ui

import com.google.gson.JsonArray
import com.google.gson.JsonObject
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

private const val MAX_ROUNDS = 10

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
        val d = Dimension(JBUI.scale(28), JBUI.scale(28))
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
            val r = minOf(width, height) / 2 - JBUI.scale(2)
            g2.color = JBColor(Color(0x3574F0), Color(0x548AF7))
            g2.stroke = BasicStroke(JBUI.scale(2).toFloat(), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
            g2.drawArc(cx - r, cy - r, 2 * r, 2 * r, angle, 100)
        } finally {
            g2.dispose()
        }
    }
}

class ChatPanel(project: Project) : JPanel(BorderLayout()), Disposable {

    private val settings get() = LmSettings.get().state
    private val tools = ProjectTools(project)
    private val history = mutableListOf<JsonObject>()

    private val modelBox = ComboBox<ModelItem>()
    private val transcript = JBTextArea().apply {
        isEditable = false; lineWrap = true; wrapStyleWord = true; margin = JBUI.insets(6)
    }
    private val input = JBTextArea(3, 20).apply { lineWrap = true; wrapStyleWord = true }

    private val sendBtn = JButton(IconLoader.getIcon("/icons/send.svg", ChatPanel::class.java)).apply {
        toolTipText = "Send (Enter)"
        isBorderPainted = false
        isContentAreaFilled = false
        isFocusable = false
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        val d = Dimension(JBUI.scale(28), JBUI.scale(28))
        preferredSize = d
        minimumSize = d
    }
    private val stopBtn = StopButton { stop() }
    private val actionHolder = JPanel(CardLayout()).apply { add(sendBtn, "send"); add(stopBtn, "stop") }

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
            component.border = JBUI.Borders.empty()
        }

        add(JPanel(BorderLayout(4, 0)).apply {
            border = JBUI.Borders.empty(4)
            add(modelBox, BorderLayout.CENTER)
            add(toolbar.component, BorderLayout.EAST)
        }, BorderLayout.NORTH)

        add(JBScrollPane(transcript), BorderLayout.CENTER)

        add(JPanel(BorderLayout(4, 0)).apply {
            border = JBUI.Borders.empty(4)
            add(JBScrollPane(input), BorderLayout.CENTER)
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

    /** Leading newlines are dropped while the transcript is empty, so there are no blank rows at the top. */
    private fun append(s: String) = ui {
        val t = if (transcript.document.length == 0) s.trimStart('\n') else s
        if (t.isNotEmpty()) {
            transcript.append(t)
            transcript.caretPosition = transcript.document.length
        }
    }

    private fun setBusy(b: Boolean) {
        busy = b
        (actionHolder.layout as CardLayout).show(actionHolder, if (b) "stop" else "send")
    }

    private fun clearChat() {
        if (!busy) {
            history.clear(); transcript.text = ""
        }
    }

    private fun stop() {
        stopRequested = true; client?.cancel()
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
                        if (items.isEmpty()) append("\n[info] ${p.name}: no models matched.\n")
                    }
                } catch (e: Exception) {
                    append("\n[error] ${p.name}: ${e.message ?: e}\n")
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
            append("\n[error] No model selected. Check Settings and press reload.\n"); return
        }
        input.text = ""
        startTurn(text, item)
    }

    private fun startTurn(userText: String, item: ModelItem) {
        val s = settings
        val provider = s.providers.firstOrNull { it.id == item.providerId }
        if (provider == null) {
            append("\n[error] That provider was removed. Reload models.\n"); return
        }
        val url = provider.baseUrl
        val providerId = provider.id
        val system = s.systemPrompt
        val temperature = s.temperature
        val useTools = s.useTools

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
                if (!thinking) {
                    thinking = true; append("(thinking…) ")
                }
            }
        }

        for (round in 1..MAX_ROUNDS) {
            if (stopRequested) {
                append("\n[stopped]\n"); return
            }

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

    override fun dispose() {
        client?.cancel()
    }
}

private fun message(role: String, content: String) =
    JsonObject().apply { addProperty("role", role); addProperty("content", content) }

private fun toolMessage(id: String, content: String) =
    JsonObject().apply { addProperty("role", "tool"); addProperty("tool_call_id", id); addProperty("content", content) }