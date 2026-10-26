package dev.lmchat.ui

import com.intellij.openapi.editor.DefaultLanguageHighlighterColors
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.ui.JBColor
import com.intellij.util.ui.UIUtil
import dev.lmchat.LmSettings
import java.awt.Color
import java.util.concurrent.CopyOnWriteArrayList

/** Resolves colors and fonts: settings override first, then the built-in / IDE defaults. */
object ChatTheme {
    class Def(val key: String, val label: String, val light: Int, val dark: Int)

    class Fonts(
        val chatFamily: String, val chatSize: Int,
        val codeFamily: String, val codeSize: Int,
        val smallSize: Int,
        val inputFamily: String, val inputSize: Int
    )

    val DEFS = listOf(
        Def("chatBg", "Chat background", 0xFFFFFF, 0x2B2D30),
        Def("inputBg", "Input and model selector background", 0xFFFFFF, 0x2B2D30),
        Def("userBg", "Request bubble", 0xDDEBFF, 0x2D4263),
        Def("userText", "Request text", 0x0B2545, 0xE6EEF8),
        Def("time", "Request time", 0x9AA0A6, 0x6E7681),
        Def("assistantBg", "Answer bubble", 0xF5F6F8, 0x35373A),
        Def("assistantText", "Answer text", 0x1F2328, 0xD9DCE0),
        Def("muted", "Muted text", 0x6B7280, 0x8B949E),
        Def("error", "Errors", 0xCF222E, 0xF85149),
        Def("ok", "Allow / success", 0x1A7F37, 0x56D364),
        Def("accent", "Accent (Stop ring)", 0x3574F0, 0x548AF7),
        Def("toolBg", "Tool card", 0xFFF6E0, 0x372F1D),
        Def("toolHead", "Tool name", 0xB45309, 0xE3B341),
        Def("toolMuted", "Tool arguments", 0x7A5B1E, 0xB8A36A),
        Def("codeBg", "Code block", 0xE7EAEE, 0x1F2022),
        Def("codeLabel", "Code label", 0x6B7280, 0x8B949E),
        Def("inlineBg", "Inline code", 0xDDE1E6, 0x45484D),
        Def("diffOldBg", "Removed code", 0xFFE3E0, 0x3D2326),
        Def("diffNewBg", "Added code", 0xDDF6E4, 0x1F3326),
        Def("synPlain", "Code: text", 0x24292F, 0xD4D4D4),
        Def("synKeyword", "Code: keyword", 0x0033B3, 0xCC7832),
        Def("synString", "Code: string", 0x067D17, 0x6A8759),
        Def("synNumber", "Code: number", 0x1750EB, 0x6897BB),
        Def("synComment", "Code: comment", 0x6B7280, 0x8B949E),
        Def("synVar", "Code: variable", 0x0F6E8C, 0x7EC3E6),
        Def("synCall", "Code: function call", 0x00627A, 0xFFC66D)
    )

    private fun schemeFg(key: TextAttributesKey): Color? =
        EditorColorsManager.getInstance().globalScheme.getAttributes(key)?.foregroundColor

    /** The color used when there is no override: IDE colors where it makes sense, else the built-in pair. */
    fun defaultColor(d: Def): Color {
        val fromIde: Color? = when (d.key) {
            "chatBg" -> UIUtil.getEditorPaneBackground()
            "inputBg" -> UIUtil.getTextFieldBackground()
            "synPlain" -> schemeFg(DefaultLanguageHighlighterColors.IDENTIFIER)
            "synKeyword" -> schemeFg(DefaultLanguageHighlighterColors.KEYWORD)
            "synString" -> schemeFg(DefaultLanguageHighlighterColors.STRING)
            "synNumber" -> schemeFg(DefaultLanguageHighlighterColors.NUMBER)
            "synComment" -> schemeFg(DefaultLanguageHighlighterColors.LINE_COMMENT)
            "synVar" -> schemeFg(DefaultLanguageHighlighterColors.LOCAL_VARIABLE)
            "synCall" -> schemeFg(DefaultLanguageHighlighterColors.FUNCTION_CALL)
            else -> null
        }
        return fromIde ?: JBColor(Color(d.light), Color(d.dark))
    }

    fun color(key: String): Color {
        val d = DEFS.first { it.key == key }
        val st = LmSettings.get().state
        val hex = (if (JBColor.isBright()) st.colorsLight else st.colorsDark)[key]
        if (hex != null) parse(hex)?.let { return it }
        return defaultColor(d)
    }

    fun parse(hex: String): Color? = try {
        Color(Integer.parseInt(hex.removePrefix("#"), 16))
    } catch (e: NumberFormatException) {
        null
    }

    fun hex(c: Color): String = String.format("#%06X", c.rgb and 0xFFFFFF)

    fun fonts(): Fonts {
        val s = LmSettings.get().state
        val scheme = EditorColorsManager.getInstance().globalScheme
        val codeFamily = s.codeFontFamily.ifBlank { scheme.editorFontName }
        val codeSize = if (s.codeFontSize > 0) s.codeFontSize else scheme.editorFontSize
        val chatFamily = s.chatFontFamily.ifBlank { UIUtil.getLabelFont().family }
        val chatSize = if (s.chatFontSize > 0) s.chatFontSize else codeSize
        val small = if (s.smallFontSize > 0) s.smallFontSize else (codeSize - 3).coerceAtLeast(9)
        val inFamily = s.inputFontFamily.ifBlank { chatFamily }
        val inSize = if (s.inputFontSize > 0) s.inputFontSize else chatSize
        return Fonts(chatFamily, chatSize, codeFamily, codeSize, small, inFamily, inSize)
    }
}

/** Lets open chat windows re-read colors and fonts after the settings were applied. */
object ChatSettingsBus {
    private val listeners = CopyOnWriteArrayList<() -> Unit>()
    fun add(l: () -> Unit) {
        listeners.add(l)
    }

    fun remove(l: () -> Unit) {
        listeners.remove(l)
    }

    fun fire() {
        listeners.forEach { it() }
    }
}