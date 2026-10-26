package dev.lmchat.ide.settings

import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.FormBuilder
import javax.swing.JComponent

class ChatOptionsSection : SettingsSection {
    private val temperature = JBTextField()
    private val systemPrompt = JBTextArea(4, 40).apply { lineWrap = true; wrapStyleWord = true }
    private val useTools = JBCheckBox("Enable project tools (read files, search, edit with approval)")
    private val autoTitle = JBCheckBox("Generate session titles with the model (one extra short request per session)")

    override val component: JComponent by lazy {
        FormBuilder.createFormBuilder()
            .addLabeledComponent("Temperature:", temperature)
            .addLabeledComponent("System prompt:", JBScrollPane(systemPrompt))
            .addComponent(useTools)
            .addComponent(autoTitle)
            .panel
    }

    override fun loadFrom(state: LmSettings.Data) {
        temperature.text = state.temperature.toString()
        systemPrompt.text = state.systemPrompt
        useTools.isSelected = state.useTools
        autoTitle.isSelected = state.autoTitle
    }

    override fun saveTo(state: LmSettings.Data) {
        state.temperature = temperature.text.trim().toDoubleOrNull() ?: 0.2
        state.systemPrompt = systemPrompt.text
        state.useTools = useTools.isSelected
        state.autoTitle = autoTitle.isSelected
    }

    override fun snapshot(): Any? =
        listOf(temperature.text.trim(), systemPrompt.text, useTools.isSelected, autoTitle.isSelected)
}