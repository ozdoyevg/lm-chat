package dev.lmchat.ide.chat

import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import dev.lmchat.core.agent.ContextUsage
import dev.lmchat.core.llm.ContextInfo
import dev.lmchat.ide.theme.ChatTheme
import java.util.Locale

/** Shows "used / limit" tokens of the conversation. Turns red when the context is nearly full. */
class ContextIndicator : JBLabel("") {
    private var maxTokens: Int? = null        // what the model supports
    private var loadedTokens: Int? = null     // what a local server has it loaded with
    private var usedTokens = 0
    private var usedIsEstimate = false

    init {
        font = font.deriveFont(font.size2D - 1f)
        border = JBUI.Borders.empty(0, 6)
        isVisible = false
    }

    fun setModelLimit(max: Int?) {
        maxTokens = max
        loadedTokens = null
        refresh()
    }

    fun setLocalServerInfo(info: ContextInfo) {
        loadedTokens = info.loadedLength
        info.maxLength?.let { maxTokens = it }
        refresh()
    }

    fun setUsed(tokens: Int, isEstimate: Boolean) {
        usedTokens = tokens
        usedIsEstimate = isEstimate
        refresh()
    }

    fun refresh() {
        val limit = loadedTokens ?: maxTokens
        val used = (if (usedIsEstimate) "~" else "") + ContextUsage.format(usedTokens)
        text = when {
            limit != null -> "$used / ${ContextUsage.format(limit)}"
            usedTokens > 0 -> used
            else -> ""
        }
        isVisible = text.isNotEmpty()
        foreground = ChatTheme.color(if (limit != null && usedTokens > limit * 0.85) "error" else "muted")
        toolTipText = buildString {
            append("<html>Context used: ").append(String.format(Locale.US, "%,d", usedTokens)).append(" tokens")
            if (usedIsEstimate) append(" (estimate)")
            loadedTokens?.let { append("<br>Loaded context: ").append(String.format(Locale.US, "%,d", it)) }
            maxTokens?.let { append("<br>Model maximum: ").append(String.format(Locale.US, "%,d", it)) }
            append("</html>")
        }
    }
}