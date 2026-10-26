package dev.lmchat.ide.chat

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.util.IconLoader
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.JBUI
import dev.lmchat.ide.theme.ChatTheme
import java.awt.BorderLayout
import java.awt.CardLayout
import java.awt.Cursor
import java.awt.Dimension
import java.awt.Font
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import javax.swing.JButton
import javax.swing.JPanel

/**
 * The bottom panel: message input, model picker, context indicator, reload and Send/Stop.
 * [onSend] returns true when the message was accepted (the input is then cleared).
 */
class ComposerPanel(
    private val onSend: (String) -> Boolean,
    private val onStop: () -> Unit,
    onReloadModels: () -> Unit
) : JPanel(BorderLayout()) {

    val modelPicker = ModelPicker()
    val contextIndicator = ContextIndicator()

    private var busy = false

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
    private val sendButton = JButton(IconLoader.getIcon("/icons/send.svg", ComposerPanel::class.java)).apply {
        toolTipText = "Send (Enter)"
        isBorderPainted = false
        isContentAreaFilled = false
        isFocusable = false
        margin = JBUI.emptyInsets()
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        val size = Dimension(JBUI.scale(StopButton.BUTTON_SIZE), JBUI.scale(StopButton.BUTTON_SIZE))
        preferredSize = size
        minimumSize = size
    }
    private val stopButton = StopButton { onStop() }
    private val sendOrStop = JPanel(CardLayout()).apply {
        isOpaque = false
        add(sendButton, "send")
        add(stopButton, "stop")
    }

    init {
        val reloadToolbar = ActionManager.getInstance().createActionToolbar(
            "LmChatReload",
            DefaultActionGroup(iconAction("Reload models", AllIcons.Actions.Refresh) { onReloadModels() }),
            true
        ).apply {
            targetComponent = this@ComposerPanel
            setReservePlaceAutoPopupIcon(false)
            component.border = JBUI.Borders.empty()
            component.isOpaque = false
        }

        val controls = JPanel(BorderLayout(10, 0)).apply {
            isOpaque = false
            border = JBUI.Borders.empty(4, 8, 6, 8)
            add(JPanel(BorderLayout(4, 0)).apply {
                isOpaque = false
                add(modelPicker, BorderLayout.CENTER)
                add(JPanel(BorderLayout(2, 0)).apply {
                    isOpaque = false
                    add(contextIndicator, BorderLayout.CENTER)
                    add(reloadToolbar.component, BorderLayout.EAST)
                }, BorderLayout.EAST)
            }, BorderLayout.CENTER)
            add(sendOrStop, BorderLayout.EAST)
        }

        border = JBUI.Borders.customLine(JBColor.border(), 1, 0, 0, 0)
        add(inputScroll, BorderLayout.CENTER)
        add(controls, BorderLayout.SOUTH)

        sendButton.addActionListener { submit() }
        input.addKeyListener(object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent) {
                if (e.keyCode != KeyEvent.VK_ENTER) return
                e.consume()
                if (e.isShiftDown) input.replaceSelection("\n") else submit()
            }
        })
        applyAppearance()
    }

    fun setBusy(busy: Boolean) {
        this.busy = busy
        (sendOrStop.layout as CardLayout).show(sendOrStop, if (busy) "stop" else "send")
    }

    /** Applies the font and color settings. */
    fun applyAppearance() {
        val fonts = ChatTheme.fonts()
        val background = ChatTheme.color("inputBg")
        input.font = Font(fonts.inputFamily, Font.PLAIN, fonts.inputSize)
        input.background = background
        inputScroll.background = background
        inputScroll.viewport.background = background
        modelPicker.background = background
        this.background = background
        contextIndicator.refresh()
    }

    private fun submit() {
        val text = input.text.trim()
        if (text.isEmpty() || busy) return
        if (onSend(text)) input.text = ""
    }
}