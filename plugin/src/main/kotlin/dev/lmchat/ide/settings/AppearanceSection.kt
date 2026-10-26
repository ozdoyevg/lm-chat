package dev.lmchat.ide.settings

import com.intellij.openapi.ui.ComboBox
import com.intellij.ui.ColorPanel
import com.intellij.ui.JBColor
import com.intellij.ui.JBIntSpinner
import com.intellij.ui.TitledSeparator
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.FormBuilder
import dev.lmchat.ide.theme.ChatTheme
import java.awt.BorderLayout
import java.awt.GraphicsEnvironment
import java.awt.GridLayout
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel

/** Fonts and colors of the chat. Colors are stored per theme (light/dark); only changed colors are saved. */
class AppearanceSection : SettingsSection {
    private val families =
        listOf("") + GraphicsEnvironment.getLocalGraphicsEnvironment().availableFontFamilyNames.toList()

    private fun familyBox() = ComboBox<String>(families.toTypedArray()).apply { isEditable = true }

    private val chatFamily = familyBox()
    private val codeFamily = familyBox()
    private val inputFamily = familyBox()
    private val chatSize = JBIntSpinner(0, 0, 60)
    private val codeSize = JBIntSpinner(0, 0, 60)
    private val smallSize = JBIntSpinner(0, 0, 60)
    private val inputSize = JBIntSpinner(0, 0, 60)
    private val colorPanels = ChatTheme.COLOR_DEFINITIONS.associate { it.key to ColorPanel() }

    override val component: JComponent by lazy {
        val colorGrid = JPanel(GridLayout(0, 2, 16, 4)).apply {
            ChatTheme.COLOR_DEFINITIONS.forEach { definition ->
                add(JPanel(BorderLayout(8, 0)).apply {
                    add(JBLabel(definition.label), BorderLayout.CENTER)
                    add(colorPanels.getValue(definition.key), BorderLayout.EAST)
                })
            }
        }
        val resetColors = JButton("Reset colors to defaults").apply {
            addActionListener {
                ChatTheme.COLOR_DEFINITIONS.forEach {
                    colorPanels.getValue(it.key).selectedColor = ChatTheme.defaultColor(it)
                }
            }
        }
        val themeName = if (JBColor.isBright()) "light" else "dark"
        FormBuilder.createFormBuilder()
            .addLabeledComponent("Chat font (family, size):", fontRow(chatFamily, chatSize))
            .addLabeledComponent("Code font (family, size):", fontRow(codeFamily, codeSize))
            .addLabeledComponent("Small text size (time, labels):", smallSize)
            .addLabeledComponent("Input font (blank = chat font):", fontRow(inputFamily, inputSize))
            .addComponent(TitledSeparator("Colors for the $themeName theme"))
            .addComponent(colorGrid)
            .addComponent(resetColors)
            .panel
    }

    override fun loadFrom(state: LmSettings.Data) {
        chatFamily.selectedItem = state.chatFontFamily
        chatSize.number = state.chatFontSize
        codeFamily.selectedItem = state.codeFontFamily
        codeSize.number = state.codeFontSize
        smallSize.number = state.smallFontSize
        inputFamily.selectedItem = state.inputFontFamily
        inputSize.number = state.inputFontSize
        ChatTheme.COLOR_DEFINITIONS.forEach { colorPanels.getValue(it.key).selectedColor = ChatTheme.color(it.key) }
    }

    override fun saveTo(state: LmSettings.Data) {
        state.chatFontFamily = chatFamily.currentText()
        state.chatFontSize = chatSize.number
        state.codeFontFamily = codeFamily.currentText()
        state.codeFontSize = codeSize.number
        state.smallFontSize = smallSize.number
        state.inputFontFamily = inputFamily.currentText()
        state.inputFontSize = inputSize.number

        // keep only colors that differ from the default of the current theme
        val overrides = HashMap<String, String>()
        for (definition in ChatTheme.COLOR_DEFINITIONS) {
            val selected = colorPanels.getValue(definition.key).selectedColor ?: continue
            val default = ChatTheme.defaultColor(definition)
            if ((selected.rgb and 0xFFFFFF) != (default.rgb and 0xFFFFFF)) overrides[definition.key] =
                ChatTheme.hex(selected)
        }
        if (JBColor.isBright()) state.colorsLight = overrides else state.colorsDark = overrides
    }

    override fun snapshot(): Any? = listOf(
        chatFamily.currentText(), chatSize.number, codeFamily.currentText(), codeSize.number, smallSize.number,
        inputFamily.currentText(), inputSize.number,
        ChatTheme.COLOR_DEFINITIONS.map { colorPanels.getValue(it.key).selectedColor?.rgb }
    )

    private fun fontRow(family: ComboBox<String>, size: JBIntSpinner) =
        JPanel(BorderLayout(8, 0)).apply { add(family, BorderLayout.CENTER); add(size, BorderLayout.EAST) }
}