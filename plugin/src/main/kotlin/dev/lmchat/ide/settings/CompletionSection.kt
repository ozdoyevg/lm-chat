package dev.lmchat.ide.settings

import com.intellij.icons.AllIcons
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.Messages
import com.intellij.ui.JBIntSpinner
import com.intellij.ui.components.JBCheckBox
import com.intellij.util.ui.FormBuilder
import dev.lmchat.core.completion.CompletionMode
import dev.lmchat.core.completion.FimTemplate
import dev.lmchat.core.llm.OpenAiCompatibleClient
import java.awt.BorderLayout
import javax.swing.DefaultComboBoxModel
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel

class CompletionSection(private val providers: ProvidersSection) : SettingsSection {

    private class ProviderChoice(val id: String, val name: String) {
        override fun toString() = name
    }

    private val enabled = JBCheckBox("Enable inline code completion (Tab accepts)")
    private val providerChoice = ComboBox<ProviderChoice>()
    private val modelField = ComboBox<String>().apply { isEditable = true }
    private val modeChoice = ComboBox<String>(CompletionMode.values().map { it.label }.toTypedArray())
    private val templateChoice = ComboBox<String>(FimTemplate.values().map { it.label }.toTypedArray())
    private val linesBefore = JBIntSpinner(60, 0, 500)
    private val linesAfter = JBIntSpinner(20, 0, 200)
    private val maxLines = JBIntSpinner(3, 1, 20)
    private val delayMs = JBIntSpinner(500, 100, 5000)
    private val maxTokens = JBIntSpinner(96, 16, 1024)

    init {
        providers.addChangeListener { refreshProviderChoices(selectedProviderId()) }
    }

    override val component: JComponent by lazy {
        val modelRow = JPanel(BorderLayout(4, 0)).apply {
            add(modelField, BorderLayout.CENTER)
            add(JButton(AllIcons.Actions.Refresh).apply {
                toolTipText = "Load models from the selected provider"
                addActionListener { loadModels() }
            }, BorderLayout.EAST)
        }
        FormBuilder.createFormBuilder()
            .addComponent(enabled)
            .addLabeledComponent("Provider:", providerChoice)
            .addLabeledComponent("Model:", modelRow)
            .addLabeledComponent("Mode:", modeChoice)
            .addLabeledComponent("FIM template:", templateChoice)
            .addLabeledComponent("Context lines before cursor:", linesBefore)
            .addLabeledComponent("Context lines after cursor:", linesAfter)
            .addLabeledComponent("Max suggested lines:", maxLines)
            .addLabeledComponent("Delay after typing (ms):", delayMs)
            .addLabeledComponent("Max tokens:", maxTokens)
            .panel
    }

    override fun loadFrom(state: LmSettings.Data) {
        enabled.isSelected = state.completionEnabled
        refreshProviderChoices(state.completionProviderId)
        modelField.selectedItem = state.completionModel
        modeChoice.selectedIndex = CompletionMode.fromId(state.completionMode).ordinal
        templateChoice.selectedIndex = FimTemplate.byName(state.fimTemplate).ordinal
        linesBefore.number = state.ctxLinesBefore
        linesAfter.number = state.ctxLinesAfter
        maxLines.number = state.maxSuggestLines
        delayMs.number = state.completionDelayMs
        maxTokens.number = state.completionMaxTokens
    }

    override fun saveTo(state: LmSettings.Data) {
        state.completionEnabled = enabled.isSelected
        state.completionProviderId = selectedProviderId()
        state.completionModel = modelField.currentText()
        state.completionMode = CompletionMode.values()[modeChoice.selectedIndex.coerceAtLeast(0)].id
        state.fimTemplate = FimTemplate.values()[templateChoice.selectedIndex.coerceAtLeast(0)].name
        state.ctxLinesBefore = linesBefore.number
        state.ctxLinesAfter = linesAfter.number
        state.maxSuggestLines = maxLines.number
        state.completionDelayMs = delayMs.number
        state.completionMaxTokens = maxTokens.number
    }

    override fun snapshot(): Any? = listOf(
        enabled.isSelected, selectedProviderId(), modelField.currentText(), modeChoice.selectedIndex,
        templateChoice.selectedIndex, linesBefore.number, linesAfter.number, maxLines.number,
        delayMs.number, maxTokens.number
    )

    private fun selectedProviderId(): String = (providerChoice.selectedItem as? ProviderChoice)?.id ?: ""

    private fun refreshProviderChoices(selectedId: String) {
        val choices = providers.rows().map { ProviderChoice(it.id, it.name.ifBlank { "(unnamed)" }) }
        providerChoice.model = DefaultComboBoxModel<ProviderChoice>(choices.toTypedArray())
        choices.firstOrNull { it.id == selectedId }?.let { providerChoice.selectedItem = it }
    }

    private fun loadModels() {
        val row = providers.rows().firstOrNull { it.id == selectedProviderId() } ?: return
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val models = OpenAiCompatibleClient(row.baseUrl, row.apiKey).listModels(row.modelFilter)
                ApplicationManager.getApplication().invokeLater({
                    val current = modelField.currentText()
                    modelField.model = DefaultComboBoxModel<String>(models.toTypedArray())
                    modelField.selectedItem = current
                }, ModalityState.any())
            } catch (e: Exception) {
                ApplicationManager.getApplication().invokeLater({
                    Messages.showErrorDialog("Cannot load models: ${e.message ?: e}", "LM Chat")
                }, ModalityState.any())
            }
        }
    }
}