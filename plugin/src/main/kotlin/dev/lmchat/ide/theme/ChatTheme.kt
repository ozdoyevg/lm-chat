package dev.lmchat.ide.theme

import com.intellij.openapi.editor.DefaultLanguageHighlighterColors
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.ui.JBColor
import com.intellij.util.ui.UIUtil
import dev.lmchat.ide.settings.LmSettings
import java.awt.Color
import java.util.concurrent.CopyOnWriteArrayList

class ColorDefinition(val key: String, val label: String, val lightRgb: Int, val darkRgb: Int)

class ChatFonts(
    val chatFamily: String, val chatSize: Int,
    val codeFamily: String, val codeSize: Int,
    val smallSize: Int,
    val inputFamily: String, val inputSize: Int
)

/** Resolves colors and fonts: settings override first, then IDE colors or the built-in defaults. */
object ChatTheme {
    val COLOR_DEFINITIONS = listOf(
        ColorDefinition("chatBg", "Chat background", 0xFFFFFF, 0x2B2D30),
        ColorDefinition("inputBg", "Input and model selector background", 0xFFFFFF, 0x2B2D30),
        ColorDefinition("userBg", "Request bubble", 0xDDEBFF, 0x2D4263),
        ColorDefinition("userText", "Request text", 0x0B2545, 0xE6EEF8),
        ColorDefinition("time", "Request time", 0x9AA0A6, 0x6E7681),
        ColorDefinition("assistantBg", "Answer bubble", 0xF5F6F8, 0x35373A),
        ColorDefinition("assistantText", "Answer text", 0x1F2328, 0xD9DCE0),
        ColorDefinition("muted", "Muted text", 0x6B7280, 0x8B949E),
        ColorDefinition("error", "Errors", 0xCF222E, 0xF85149),
        ColorDefinition("accent", "Accent (Stop ring)", 0x3574F0, 0x548AF7),
        ColorDefinition("allowBg", "Allow button", 0xD3E6D8, 0x34483A),
        ColorDefinition("denyBg", "Deny button", 0xEBD3D3, 0x4D3434),
        ColorDefinition("toolBg", "Tool card", 0xFFF6E0, 0x372F1D),
        ColorDefinition("toolHead", "Tool name", 0xB45309, 0xE3B341),
        ColorDefinition("toolMuted", "Tool arguments", 0x7A5B1E, 0xB8A36A),
        ColorDefinition("codeBg", "Code block", 0xE7EAEE, 0x1F2022),
        ColorDefinition("codeLabel", "Code label", 0x6B7280, 0x8B949E),
        ColorDefinition("inlineBg", "Inline code", 0xDDE1E6, 0x45484D),
        ColorDefinition("diffOldBg", "Removed code", 0xFFE3E0, 0x3D2326),
        ColorDefinition("diffNewBg", "Added code", 0xDDF6E4, 0x1F3326),
        ColorDefinition("synPlain", "Code: text", 0x24292F, 0xD4D4D4),
        ColorDefinition("synKeyword", "Code: keyword", 0x0033B3, 0xCC7832),
        ColorDefinition("synString", "Code: string", 0x067D17, 0x6A8759),
        ColorDefinition("synNumber", "Code: number", 0x1750EB, 0x6897BB),
        ColorDefinition("synComment", "Code: comment", 0x6B7280, 0x8B949E),
        ColorDefinition("synVar", "Code: variable", 0x0F6E8C, 0x7EC3E6),
        ColorDefinition("synCall", "Code: function call", 0x00627A, 0xFFC66D)
    )

    private fun schemeForeground(key: TextAttributesKey): Color? =
        EditorColorsManager.getInstance().globalScheme.getAttributes(key)?.foregroundColor

    /** The color used when there is no override: IDE colors where it makes sense, else the built-in pair. */
    fun defaultColor(definition: ColorDefinition): Color {
        val fromIde: Color? = when (definition.key) {
            "chatBg" -> UIUtil.getEditorPaneBackground()
            "inputBg" -> UIUtil.getTextFieldBackground()
            "synPlain" -> schemeForeground(DefaultLanguageHighlighterColors.IDENTIFIER)
            "synKeyword" -> schemeForeground(DefaultLanguageHighlighterColors.KEYWORD)
            "synString" -> schemeForeground(DefaultLanguageHighlighterColors.STRING)
            "synNumber" -> schemeForeground(DefaultLanguageHighlighterColors.NUMBER)
            "synComment" -> schemeForeground(DefaultLanguageHighlighterColors.LINE_COMMENT)
            "synVar" -> schemeForeground(DefaultLanguageHighlighterColors.LOCAL_VARIABLE)
            "synCall" -> schemeForeground(DefaultLanguageHighlighterColors.FUNCTION_CALL)
            else -> null
        }
        return fromIde ?: JBColor(Color(definition.lightRgb), Color(definition.darkRgb))
    }

    fun color(key: String): Color {
        val definition = COLOR_DEFINITIONS.first { it.key == key }
        val state = LmSettings.get().state
        val overrides = if (JBColor.isBright()) state.colorsLight else state.colorsDark
        overrides[key]?.let { parse(it) }?.let { return it }
        return defaultColor(definition)
    }

    fun parse(hex: String): Color? = try {
        Color(Integer.parseInt(hex.removePrefix("#"), 16))
    } catch (e: NumberFormatException) {
        null
    }

    fun hex(color: Color): String = String.format("#%06X", color.rgb and 0xFFFFFF)

    fun fonts(): ChatFonts {
        val state = LmSettings.get().state
        val scheme = EditorColorsManager.getInstance().globalScheme
        val codeFamily = state.codeFontFamily.ifBlank { scheme.editorFontName }
        val codeSize = if (state.codeFontSize > 0) state.codeFontSize else scheme.editorFontSize
        val chatFamily = state.chatFontFamily.ifBlank { UIUtil.getLabelFont().family }
        val chatSize = if (state.chatFontSize > 0) state.chatFontSize else codeSize
        val smallSize = if (state.smallFontSize > 0) state.smallFontSize else (codeSize - 3).coerceAtLeast(9)
        val inputFamily = state.inputFontFamily.ifBlank { chatFamily }
        val inputSize = if (state.inputFontSize > 0) state.inputFontSize else chatSize
        return ChatFonts(chatFamily, chatSize, codeFamily, codeSize, smallSize, inputFamily, inputSize)
    }
}

/** Lets open chat windows re-read colors and fonts after the settings were applied. */
object ChatSettingsBus {
    private val listeners = CopyOnWriteArrayList<() -> Unit>()
    fun add(listener: () -> Unit) {
        listeners.add(listener)
    }

    fun remove(listener: () -> Unit) {
        listeners.remove(listener)
    }

    fun fire() {
        listeners.forEach { it() }
    }
}