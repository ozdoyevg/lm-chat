package dev.lmchat.ide.settings

import com.intellij.openapi.options.Configurable
import com.intellij.ui.TitledSeparator
import com.intellij.util.ui.FormBuilder
import dev.lmchat.ide.theme.ChatSettingsBus
import javax.swing.JComponent
import javax.swing.JPanel

class SettingsConfigurable : Configurable {
    private val providers = ProvidersSection()
    private val chatOptions = ChatOptionsSection()
    private val completion = CompletionSection(providers)
    private val appearance = AppearanceSection()

        // order matters: the completion section reads the provider rows
    private val sections: List<SettingsSection> = listOf(providers, chatOptions, completion, appearance)

    private var savedSnapshot: List<Any?> = emptyList()
    private val state get() = LmSettings.get().state

    override fun getDisplayName(): String = "LM Chat"

    override fun createComponent(): JComponent = FormBuilder.createFormBuilder()
        .addComponent(TitledSeparator("Chat"))
        .addComponent(providers.component)
        .addComponent(chatOptions.component)
        .addComponent(TitledSeparator("Inline completion"))
        .addComponent(completion.component)
        .addComponent(TitledSeparator("Appearance"))
        .addComponent(appearance.component)
        .addComponentFillVertically(JPanel(), 0)
        .panel

    override fun reset() {
        sections.forEach { it.loadFrom(state) }
        savedSnapshot = currentSnapshot()
    }

    override fun apply() {
        sections.forEach { it.saveTo(state) }
        savedSnapshot = currentSnapshot()
        ChatSettingsBus.fire()
    }

    override fun isModified(): Boolean = currentSnapshot() != savedSnapshot

    private fun currentSnapshot() = sections.map { it.snapshot() }
}