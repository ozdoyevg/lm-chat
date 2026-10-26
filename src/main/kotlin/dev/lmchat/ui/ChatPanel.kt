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
import com.intellij.openapi.util.IconLoader
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
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
import java.awt.Cursor
import java.awt.Dimension
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.util.Locale
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import javax.swing.BorderFactory
import javax.swing.Icon
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.Timer

private const val MAX_ROUNDS = 10
private const val BUTTON = 32

private const val TITLE_PROMPT =
    "Write a short title (max 5 words) for a chat that starts with the user's message below. " +
            "Reply with the title only: no quotes, no punctuation at the end."

class ChatToolWindowFactory : ToolWindowFactory, DumbAware {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val panel = ChatPanel(project)
        val content = ContentFactory.SERVICE.getInstance().createContent(panel, "", false)
        content.setDisposer(panel)
        toolWindow.contentManager.addContent(content)
    }
}

/** Stop icon with a spinning ring around it. The animation only runs while the button is visible. */
class StopButton(private val onStop: () -> Unit) : JComponent() {
    private val icon = IconLoader.getIcon("/icons/stop.svg", StopButton::class.java)
    private var angle = 0
    private val timer = Timer(30) { angle = (angle + 12) % 360; repaint() }

    init {
        val d = Dimension(JBUI.scale(BUTTON), JBUI.scale(BUTTON))
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
            g2.color = ChatTheme.color("accent")
            g2.stroke = BasicStroke(JBUI.scale(2).toFloat(), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
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

    private val picker = ModelPicker()
    private val ctxLabel = JBLabel("").apply {
        font = font.deriveFont(font.size2D - 1f)
        border = JBUI.Borders.empty(0, 6)
        isVisible = false
    }
    private val view = ChatView()
    private val titleLabel = JBLabel("New chat").apply {
        font = font.deriveFont(Font.BOLD)
        border = JBUI.Borders.empty(0, 6)
    }

    private val input = JBTextArea(3, 20).apply {
        lineWrap = true
        wrapStyleWord = true
        border = JBUI.Borders.empty(8, 10)
        emptyText.text = "Write a message…"
    }
    private val inputScroll = JBScrollPane(input).apply {
        border = JBUI.Borders.empty()
        viewportBorder = JBUI.Borders.empty()
    }
    private val composer = JPanel(BorderLayout())

    private val sendBtn = JButton(IconLoader.getIcon("/icons/send.svg", ChatPanel::class.java)).apply {
        toolTipText = "Send (Enter)"
        isBorderPainted = false
        isContentAreaFilled = false
        isFocusable = false
        margin = JBUI.emptyInsets()
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        val d = Dimension(JBUI.scale(BUTTON), JBUI.scale(BUTTON))
        preferredSize = d
        minimumSize = d
    }
    private val stopBtn = StopButton { stop() }
    private val actionHolder = JPanel(CardLayout()).apply {
        isOpaque = false
        add(sendBtn, "send")
        add(stopBtn, "stop")
    }

    private val onSettingsChanged: () -> Unit = { ui { applyAppearance() } }

    @Volatile
    private var client: OpenAiClient? = null
    @Volatile
    private var stopRequested = false
    @Volatile
    private var loadGen = 0
    @Volatile
    private var turnUsed: Int? = null     // tokens reported by the server for the last request
    private var ctxGen = 0
    private var ctxMax: Int? = null                 // model maximum
    private var ctxLoaded: Int? = null              // context the model is currently loaded with
    private var ctxUsed = 0
    private var ctxEstimated = false
    private var sessionId = 0
    private var busy = false

    init {
        // ---- top: session title on the left, "+" and the gear on the right ----
        val topBar = ActionManager.getInstance().createActionToolbar(
            "LmChatToolbar",
            DefaultActionGroup().apply {
                add(iconAction("New session", AllIcons.General.Add, enabled = { !busy }) { newSession() })
                add(iconAction("Settings", AllIcons.General.GearPlain) {
                    ShowSettingsUtil.getInstance().showSettingsDialog(project, LmConfigurable::class.java)
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
            add(topBar.component, BorderLayout.EAST)
        }, BorderLayout.NORTH)

        // ---- center: transcript ----
        add(view, BorderLayout.CENTER)

        // ---- bottom: input, then model + context + reload on the left and Send/Stop on the right ----
        val reloadBar = ActionManager.getInstance().createActionToolbar(
            "LmChatReload",
            DefaultActionGroup(iconAction("Reload models", AllIcons.Actions.Refresh) { loadModels() }),
            true
        ).apply {
            targetComponent = this@ChatPanel
            setReservePlaceAutoPopupIcon(false)
            component.border = JBUI.Borders.empty()
            component.isOpaque = false
        }

        val controls = JPanel(BorderLayout(10, 0)).apply {
            isOpaque = false
            border = JBUI.Borders.empty(4, 8, 6, 8)
            add(JPanel(BorderLayout(4, 0)).apply {
                isOpaque = false
                add(picker, BorderLayout.CENTER)
                add(JPanel(BorderLayout(2, 0)).apply {
                    isOpaque = false
                    add(ctxLabel, BorderLayout.CENTER)
                    add(reloadBar.component, BorderLayout.EAST)
                }, BorderLayout.EAST)
            }, BorderLayout.CENTER)
            add(actionHolder, BorderLayout.EAST)
        }

        composer.apply {
            border = JBUI.Borders.customLine(JBColor.border(), 1, 0, 0, 0)
            add(inputScroll, BorderLayout.CENTER)
            add(controls, BorderLayout.SOUTH)
        }
        add(composer, BorderLayout.SOUTH)

        sendBtn.addActionListener { onSend() }
        picker.onChange = { item, byUser ->
            if (byUser) settings.lastSelection = item.key
            recomputeEstimate()
            refreshContextInfo()
        }
        input.addKeyListener(object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent) {
                if (e.keyCode != KeyEvent.VK_ENTER) return
                e.consume()
                if (e.isShiftDown) input.replaceSelection("\n") else onSend()
            }
        })

        ChatSettingsBus.add(onSettingsChanged)
        applyAppearance()

        if (System.getProperty("lmchat.demo") == "true") view.demo()
        loadModels()
    }

    private fun iconAction(text: String, icon: Icon, enabled: () -> Boolean = { true }, action: () -> Unit) =
        object : DumbAwareAction(text, text, icon) {
            override fun actionPerformed(e: AnActionEvent) = action()
            override fun update(e: AnActionEvent) {
                e.presentation.isEnabled = enabled()
            }
        }

    // ---- helpers ----

    private fun ui(r: () -> Unit) =
        ApplicationManager.getApplication().invokeLater({ r() }, ModalityState.any())

    /** Applies the font and color settings to the input area, the model picker and the transcript. */
    private fun applyAppearance() {
        val f = ChatTheme.fonts()
        val bg = ChatTheme.color("inputBg")
        input.font = Font(f.inputFamily, Font.PLAIN, f.inputSize)
        input.background = bg
        inputScroll.background = bg
        inputScroll.viewport.background = bg
        composer.background = bg
        picker.background = bg
        view.applyTheme()
        updateCtxLabel()
        revalidate()
        repaint()
    }

    private fun setBusy(b: Boolean) {
        busy = b
        (actionHolder.layout as CardLayout).show(actionHolder, if (b) "stop" else "send")
    }

    private fun setTitle(t: String) {
        titleLabel.text = t
        titleLabel.toolTipText = t
    }

    private fun newSession() {
        if (busy) return
        sessionId++
        history.clear()
        view.clear()
        setTitle("New chat")
        ctxUsed = 0
        ctxEstimated = false
        updateCtxLabel()
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

    // ---- context indicator ----

    private fun fmtTokens(n: Int): String =
        if (n < 1000) "$n" else String.format(Locale.US, "%.1fk", n / 1000.0).replace(".0k", "k")

    private fun estimateTokens(): Int {
        var chars = settings.systemPrompt.length
        for (m in history) {
            val c = m.get("content")
            if (c != null && !c.isJsonNull) chars += c.asString.length
        }
        return chars / 4
    }

    private fun recomputeEstimate() {
        ctxUsed = if (history.isEmpty()) 0 else estimateTokens()
        ctxEstimated = history.isNotEmpty()
        updateCtxLabel()
    }

    private fun updateCtxLabel() {
        val limit = ctxLoaded ?: ctxMax
        val usedText = (if (ctxEstimated) "~" else "") + fmtTokens(ctxUsed)
        ctxLabel.text = when {
            limit != null -> "$usedText / ${fmtTokens(limit)}"
            ctxUsed > 0 -> usedText
            else -> ""
        }
        ctxLabel.isVisible = ctxLabel.text.isNotEmpty()
        ctxLabel.foreground = ChatTheme.color(if (limit != null && ctxUsed > limit * 0.85) "error" else "muted")
        ctxLabel.toolTipText = buildString {
            append("<html>Context used: ").append(String.format(Locale.US, "%,d", ctxUsed)).append(" tokens")
            if (ctxEstimated) append(" (estimate)")
            ctxLoaded?.let { append("<br>Loaded context: ").append(String.format(Locale.US, "%,d", it)) }
            ctxMax?.let { append("<br>Model maximum: ").append(String.format(Locale.US, "%,d", it)) }
            append("</html>")
        }
    }

    /** Looks up the model's context window; local servers can also report what it is currently loaded with. */
    private fun refreshContextInfo() {
        val item = picker.selected ?: return
        val provider = settings.providers.firstOrNull { it.id == item.providerId } ?: return
        val gen = ++ctxGen
        ctxMax = item.ctx
        ctxLoaded = null
        updateCtxLabel()
        val url = provider.baseUrl
        val providerId = provider.id
        ApplicationManager.getApplication().executeOnPooledThread {
            val info = try {
                OpenAiClient(url, LmSettings.apiKey(providerId)).contextInfo(item.model)
            } catch (e: Exception) {
                null
            }
            if (info != null) ui {
                if (gen == ctxGen) {
                    ctxLoaded = info.loaded
                    if (info.max != null) ctxMax = info.max
                    updateCtxLabel()
                }
            }
        }
    }

    // ---- models ----

    private fun loadModels() {
        val gen = ++loadGen
        val providers = settings.providers.map { Provider(it.id, it.name, it.baseUrl, it.modelFilter) }
        val last = settings.lastSelection
        picker.clear()

        ApplicationManager.getApplication().executeOnPooledThread {
            for (p in providers) {
                try {
                    val infos = OpenAiClient(p.baseUrl, LmSettings.apiKey(p.id)).listModelInfos(p.modelFilter)
                    val items = infos.map { ModelItem(p.id, p.name, it.id, it.ctx) }
                    ui {
                        if (gen != loadGen) return@ui
                        picker.add(items, last)
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
        val item = picker.selected
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
        val autoTitle = s.autoTitle
        val firstTurn = history.isEmpty()
        val session = sessionId

        if (firstTurn) setTitle(quickTitle(userText))
        stopRequested = false
        turnUsed = null
        setBusy(true)
        history.add(message("user", userText))
        recomputeEstimate()
        view.userMessage(userText)
        view.beginAssistant()

        ApplicationManager.getApplication().executeOnPooledThread {
            var c: OpenAiClient? = null
            var ok = false
            try {
                c = OpenAiClient(url, LmSettings.apiKey(providerId))
                client = c
                agentLoop(c, item.model, system, temperature, useTools)
                ok = true
            } catch (e: Exception) {
                ui { view.error(e.message ?: e.toString()) }
            } finally {
                ui {
                    view.endAssistant()
                    setBusy(false)
                    val used = turnUsed
                    if (used != null) {
                        ctxUsed = used; ctxEstimated = false; updateCtxLabel()
                    } else recomputeEstimate()
                    refreshContextInfo()    // the model may have been loaded by this request
                }
            }
            if (firstTurn && autoTitle && ok && !stopRequested && c != null) {
                generateTitle(c, item.model, userText, session)
            }
        }
    }

    private fun generateTitle(c: OpenAiClient, model: String, firstMessage: String, session: Int) {
        val raw = try {
            c.chatOnce(model, listOf(message("system", TITLE_PROMPT), message("user", firstMessage.take(600))), 0.2, 24)
        } catch (e: Exception) {
            return
        }
        val title = cleanTitle(raw)
        if (title.isNotEmpty()) ui { if (session == sessionId) setTitle(title) }
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
            res.promptTokens?.let { turnUsed = it + (res.completionTokens ?: 0) }
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

    override fun dispose() {
        ChatSettingsBus.remove(onSettingsChanged)
        client?.cancel()
        view.denyPending()
    }
}

private fun quickTitle(text: String): String {
    val line = text.trim().lineSequence().firstOrNull { it.isNotBlank() }.orEmpty()
        .replace(Regex("\\s+"), " ")
    return when {
        line.isEmpty() -> "New chat"
        line.length > 40 -> line.take(40).trimEnd() + "…"
        else -> line
    }
}

private fun cleanTitle(raw: String): String =
    raw.replace(Regex("(?s)<think>.*?</think>"), "")
        .lines().firstOrNull { it.isNotBlank() }.orEmpty()
        .trim().trim('"', '\'', '`', '*', '#', '.', ' ')
        .take(60)

private fun message(role: String, content: String) =
    JsonObject().apply { addProperty("role", role); addProperty("content", content) }

private fun toolMessage(id: String, content: String) =
    JsonObject().apply { addProperty("role", "tool"); addProperty("tool_call_id", id); addProperty("content", content) }