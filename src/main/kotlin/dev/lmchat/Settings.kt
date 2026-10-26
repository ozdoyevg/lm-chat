package dev.lmchat

import com.intellij.credentialStore.CredentialAttributes
import com.intellij.credentialStore.generateServiceName
import com.intellij.icons.AllIcons
import com.intellij.ide.passwordSafe.PasswordSafe
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.Messages
import com.intellij.ui.ColorPanel
import com.intellij.ui.JBColor
import com.intellij.ui.JBIntSpinner
import com.intellij.ui.TitledSeparator
import com.intellij.ui.ToolbarDecorator
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.FormBuilder
import com.intellij.util.xmlb.XmlSerializerUtil
import dev.lmchat.api.OpenAiClient
import dev.lmchat.completion.FimTemplate
import dev.lmchat.ui.ChatSettingsBus
import dev.lmchat.ui.ChatTheme
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.GraphicsEnvironment
import java.awt.GridLayout
import java.util.UUID
import javax.swing.DefaultCellEditor
import javax.swing.DefaultComboBoxModel
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JMenuItem
import javax.swing.JPanel
import javax.swing.JPasswordField
import javax.swing.JPopupMenu
import javax.swing.table.DefaultTableCellRenderer
import javax.swing.table.DefaultTableModel

/** One OpenAI-compatible endpoint. The API key lives in Password Safe, keyed by [id]. */
class Provider(
    var id: String = "",
    var name: String = "",
    var baseUrl: String = "",
    /** Comma-separated; a model is listed if its id contains ANY term. Empty = show all. */
    var modelFilter: String = ""
)

class Preset(val name: String, val url: String, val filter: String)

val PRESETS = listOf(
    Preset("LM Studio (LAN)", "http://192.168.0.101:1234/v1", ""),
    Preset("Ollama (local)", "http://localhost:11434/v1", ""),
    Preset("OpenRouter (free models)", "https://openrouter.ai/api/v1", ":free"),
    Preset("OpenCode Zen (free models)", "https://opencode.ai/zen/v1", "free,big-pickle"),
    Preset("Custom (OpenAI-compatible)", "https://", "")
)

@State(name = "LmChatSettings", storages = [Storage("lmchat.xml")])
class LmSettings : PersistentStateComponent<LmSettings.Data> {

    class Data {
        var providers: MutableList<Provider> =
            mutableListOf(Provider("lm-studio", "LM Studio", "http://192.168.0.101:1234/v1", ""))
        var lastSelection: String = ""
        var systemPrompt: String = "You are a concise, expert coding assistant working inside PhpStorm."
        var temperature: Double = 0.2
        var useTools: Boolean = true
        var autoTitle: Boolean = true

        // inline completion
        var completionEnabled: Boolean = true
        var completionProviderId: String = "lm-studio"
        var completionModel: String = ""
        var completionMode: String = "chat"          // "chat" | "fim" | "ollama"
        var fimTemplate: String = "QWEN"
        var ctxLinesBefore: Int = 60
        var ctxLinesAfter: Int = 20
        var maxSuggestLines: Int = 3
        var completionDelayMs: Int = 500
        var completionMaxTokens: Int = 96

        // appearance: blank family / 0 size = use the default
        var chatFontFamily: String = ""
        var chatFontSize: Int = 0
        var codeFontFamily: String = ""
        var codeFontSize: Int = 0
        var smallFontSize: Int = 0
        var inputFontFamily: String = ""
        var inputFontSize: Int = 0
        var colorsLight: MutableMap<String, String> = HashMap()   // overrides only
        var colorsDark: MutableMap<String, String> = HashMap()
    }

    private val data = Data()
    override fun getState(): Data = data
    override fun loadState(state: Data) = XmlSerializerUtil.copyBean(state, data)

    companion object {
        fun get(): LmSettings = ApplicationManager.getApplication().getService(LmSettings::class.java)

        private fun attrs(id: String) = CredentialAttributes(generateServiceName("LM Chat", id))
        fun apiKey(providerId: String): String = PasswordSafe.instance.getPassword(attrs(providerId)) ?: ""
        fun setApiKey(providerId: String, key: String) =
            PasswordSafe.instance.setPassword(attrs(providerId), key.ifBlank { null })
    }
}

class LmConfigurable : Configurable {
    private class Ref(val id: String, val name: String) {
        override fun toString() = name
    }

    // chat
    private val tableModel = DefaultTableModel(
        arrayOf("Name", "Base URL", "API key", "Model filter (contains, comma-separated)"), 0
    )
    private val ids = mutableListOf<String>()           // parallel to table rows
    private val table = JBTable(tableModel)
    private val temp = JBTextField()
    private val system = JBTextArea(4, 40).apply { lineWrap = true; wrapStyleWord = true }
    private val toolsBox = JBCheckBox("Enable project tools (read files, search, edit with approval)")
    private val autoTitleBox =
        JBCheckBox("Generate session titles with the model (one extra short request per session)")

    // completion
    private val modes = listOf("chat", "fim", "ollama")
    private val compEnabled = JBCheckBox("Enable inline code completion (Tab accepts)")
    private val compProvider = ComboBox<Ref>()
    private val compModel = ComboBox<String>().apply { isEditable = true }
    private val compMode = ComboBox<String>(
        arrayOf(
            "Chat (works with any model)",
            "FIM (/v1/completions, pick a template)",
            "Ollama FIM (/api/generate, model's own template)"
        )
    )
    private val fimBox = ComboBox<String>(FimTemplate.values().map { it.label }.toTypedArray())
    private val ctxBefore = JBIntSpinner(60, 0, 500)
    private val ctxAfter = JBIntSpinner(20, 0, 200)
    private val maxLines = JBIntSpinner(3, 1, 20)
    private val delayMs = JBIntSpinner(500, 100, 5000)
    private val maxTokens = JBIntSpinner(96, 16, 1024)

    // appearance
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
    private val colorPanels = ChatTheme.DEFS.associate { it.key to ColorPanel() }

    private var snapshot: List<Any?> = emptyList()
    private val s get() = LmSettings.get().state

    init {
        table.putClientProperty("terminateEditOnFocusLost", true)
        table.preferredScrollableViewportSize = Dimension(640, 130)
        table.columnModel.getColumn(2).apply {
            cellRenderer = object : DefaultTableCellRenderer() {
                override fun setValue(value: Any?) {
                    text = if ((value as? String).isNullOrEmpty()) "" else "••••••••"
                }
            }
            cellEditor = DefaultCellEditor(JPasswordField())
        }
        tableModel.addTableModelListener { refreshProviders(compProviderId()) }
    }

    override fun getDisplayName(): String = "LM Chat"

    override fun createComponent(): JComponent {
        val providers = ToolbarDecorator.createDecorator(table)
            .setAddAction { button ->
                val menu = JPopupMenu()
                PRESETS.forEach { p ->
                    menu.add(JMenuItem(p.name).apply {
                        addActionListener { addRow(UUID.randomUUID().toString(), p.name, p.url, "", p.filter) }
                    })
                }
                menu.show(button.contextComponent, 0, 0)
            }
            .setRemoveAction {
                table.cellEditor?.stopCellEditing()
                val r = table.selectedRow
                if (r >= 0) {
                    ids.removeAt(r); tableModel.removeRow(r)
                }
            }
            .disableUpDownActions()
            .createPanel()

        val modelRow = JPanel(BorderLayout(4, 0)).apply {
            add(compModel, BorderLayout.CENTER)
            add(JButton(AllIcons.Actions.Refresh).apply {
                toolTipText = "Load models from the selected provider"
                addActionListener { loadCompletionModels() }
            }, BorderLayout.EAST)
        }

        val colorsGrid = JPanel(GridLayout(0, 2, 16, 4)).apply {
            ChatTheme.DEFS.forEach { d ->
                add(JPanel(BorderLayout(8, 0)).apply {
                    add(JBLabel(d.label), BorderLayout.CENTER)
                    add(colorPanels.getValue(d.key), BorderLayout.EAST)
                })
            }
        }
        val themeName = if (JBColor.isBright()) "light" else "dark"
        val resetColors = JButton("Reset colors to defaults").apply {
            addActionListener {
                ChatTheme.DEFS.forEach { colorPanels.getValue(it.key).selectedColor = ChatTheme.defaultColor(it) }
            }
        }

        return FormBuilder.createFormBuilder()
            .addComponent(TitledSeparator("Chat"))
            .addLabeledComponentFillVertically("Providers:", providers)
            .addLabeledComponent("Temperature:", temp)
            .addLabeledComponent("System prompt:", JBScrollPane(system))
            .addComponent(toolsBox)
            .addComponent(autoTitleBox)
            .addComponent(TitledSeparator("Inline completion"))
            .addComponent(compEnabled)
            .addLabeledComponent("Provider:", compProvider)
            .addLabeledComponent("Model:", modelRow)
            .addLabeledComponent("Mode:", compMode)
            .addLabeledComponent("FIM template:", fimBox)
            .addLabeledComponent("Context lines before cursor:", ctxBefore)
            .addLabeledComponent("Context lines after cursor:", ctxAfter)
            .addLabeledComponent("Max suggested lines:", maxLines)
            .addLabeledComponent("Delay after typing (ms):", delayMs)
            .addLabeledComponent("Max tokens:", maxTokens)
            .addComponent(TitledSeparator("Appearance (blank font / size 0 = default)"))
            .addLabeledComponent("Chat font (family, size):", fontRow(chatFamily, chatSize))
            .addLabeledComponent("Code font (family, size):", fontRow(codeFamily, codeSize))
            .addLabeledComponent("Small text size (time, labels):", smallSize)
            .addLabeledComponent("Input font (blank = chat font):", fontRow(inputFamily, inputSize))
            .addComponent(TitledSeparator("Colors for the $themeName theme"))
            .addComponent(colorsGrid)
            .addComponent(resetColors)
            .panel
    }

    private fun fontRow(family: ComboBox<String>, size: JBIntSpinner) =
        JPanel(BorderLayout(8, 0)).apply { add(family, BorderLayout.CENTER); add(size, BorderLayout.EAST) }

    private fun ComboBox<String>.text(): String = (editor.item ?: selectedItem)?.toString()?.trim().orEmpty()

    // ---- providers table ----

    private fun addRow(id: String, name: String, url: String, key: String, filter: String) {
        ids.add(id)
        tableModel.addRow(arrayOf<Any>(name, url, key, filter))
    }

    /** Rows as [id, name, url, key, filter]. */
    private fun currentRows(): List<List<String>> = (0 until tableModel.rowCount).map { r ->
        listOf(ids[r]) + (0 until 4).map { c -> ((tableModel.getValueAt(r, c) as? String) ?: "").trim() }
    }

    private fun refreshProviders(selectedId: String) {
        val refs = (0 until tableModel.rowCount).map {
            Ref(ids[it], ((tableModel.getValueAt(it, 0) as? String) ?: "").ifBlank { "(unnamed)" })
        }
        compProvider.model = DefaultComboBoxModel<Ref>(refs.toTypedArray())
        refs.firstOrNull { it.id == selectedId }?.let { compProvider.selectedItem = it }
    }

    private fun compProviderId(): String = (compProvider.selectedItem as? Ref)?.id ?: ""

    private fun loadCompletionModels() {
        val row = currentRows().firstOrNull { it[0] == compProviderId() } ?: return
        val (url, key, filter) = Triple(row[2], row[3], row[4])
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val models = OpenAiClient(url, key).listModels(filter)
                ApplicationManager.getApplication().invokeLater({
                    val current = compModel.text()
                    compModel.model = DefaultComboBoxModel<String>(models.toTypedArray())
                    compModel.selectedItem = current
                }, ModalityState.any())
            } catch (e: Exception) {
                ApplicationManager.getApplication().invokeLater({
                    Messages.showErrorDialog("Cannot load models: ${e.message ?: e}", "LM Chat")
                }, ModalityState.any())
            }
        }
    }

    // ---- Configurable ----

    private fun uiState(): List<Any?> = listOf(
        currentRows(), temp.text.trim(), system.text, toolsBox.isSelected, autoTitleBox.isSelected,
        compEnabled.isSelected, compProviderId(), compModel.text(), compMode.selectedIndex, fimBox.selectedIndex,
        ctxBefore.number, ctxAfter.number, maxLines.number, delayMs.number, maxTokens.number,
        chatFamily.text(), chatSize.number, codeFamily.text(), codeSize.number, smallSize.number,
        inputFamily.text(), inputSize.number,
        ChatTheme.DEFS.map { colorPanels.getValue(it.key).selectedColor?.rgb }
    )

    override fun isModified(): Boolean = uiState() != snapshot

    override fun apply() {
        table.cellEditor?.stopCellEditing()
        val rows = currentRows()
        val kept = rows.map { it[0] }.toSet()
        s.providers.map { it.id }.filter { it !in kept }.forEach { LmSettings.setApiKey(it, "") }
        s.providers = rows.map { Provider(it[0], it[1], it[2], it[4]) }.toMutableList()
        rows.forEach { LmSettings.setApiKey(it[0], it[3]) }

        s.temperature = temp.text.trim().toDoubleOrNull() ?: 0.2
        s.systemPrompt = system.text
        s.useTools = toolsBox.isSelected
        s.autoTitle = autoTitleBox.isSelected

        s.completionEnabled = compEnabled.isSelected
        s.completionProviderId = compProviderId()
        s.completionModel = compModel.text()
        s.completionMode = modes[compMode.selectedIndex.coerceAtLeast(0)]
        s.fimTemplate = FimTemplate.values()[fimBox.selectedIndex.coerceAtLeast(0)].name
        s.ctxLinesBefore = ctxBefore.number
        s.ctxLinesAfter = ctxAfter.number
        s.maxSuggestLines = maxLines.number
        s.completionDelayMs = delayMs.number
        s.completionMaxTokens = maxTokens.number

        s.chatFontFamily = chatFamily.text()
        s.chatFontSize = chatSize.number
        s.codeFontFamily = codeFamily.text()
        s.codeFontSize = codeSize.number
        s.smallFontSize = smallSize.number
        s.inputFontFamily = inputFamily.text()
        s.inputFontSize = inputSize.number

        // store only colors that differ from the default of the current theme
        val overrides = HashMap<String, String>()
        for (d in ChatTheme.DEFS) {
            val sel = colorPanels.getValue(d.key).selectedColor ?: continue
            if ((sel.rgb and 0xFFFFFF) != (ChatTheme.defaultColor(d).rgb and 0xFFFFFF)) overrides[d.key] =
                ChatTheme.hex(sel)
        }
        if (JBColor.isBright()) s.colorsLight = overrides else s.colorsDark = overrides

        snapshot = uiState()
        ChatSettingsBus.fire()
    }

    override fun reset() {
        tableModel.rowCount = 0
        ids.clear()
        s.providers.forEach { addRow(it.id, it.name, it.baseUrl, LmSettings.apiKey(it.id), it.modelFilter) }
        temp.text = s.temperature.toString()
        system.text = s.systemPrompt
        toolsBox.isSelected = s.useTools
        autoTitleBox.isSelected = s.autoTitle

        compEnabled.isSelected = s.completionEnabled
        refreshProviders(s.completionProviderId)
        compModel.selectedItem = s.completionModel
        compMode.selectedIndex = modes.indexOf(s.completionMode).coerceAtLeast(0)
        fimBox.selectedIndex = FimTemplate.of(s.fimTemplate).ordinal
        ctxBefore.number = s.ctxLinesBefore
        ctxAfter.number = s.ctxLinesAfter
        maxLines.number = s.maxSuggestLines
        delayMs.number = s.completionDelayMs
        maxTokens.number = s.completionMaxTokens

        chatFamily.selectedItem = s.chatFontFamily
        chatSize.number = s.chatFontSize
        codeFamily.selectedItem = s.codeFontFamily
        codeSize.number = s.codeFontSize
        smallSize.number = s.smallFontSize
        inputFamily.selectedItem = s.inputFontFamily
        inputSize.number = s.inputFontSize
        ChatTheme.DEFS.forEach { colorPanels.getValue(it.key).selectedColor = ChatTheme.color(it.key) }

        snapshot = uiState()
    }
}