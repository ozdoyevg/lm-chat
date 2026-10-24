package dev.lmchat

import com.intellij.credentialStore.CredentialAttributes
import com.intellij.credentialStore.generateServiceName
import com.intellij.ide.passwordSafe.PasswordSafe
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.options.Configurable
import com.intellij.ui.ToolbarDecorator
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.FormBuilder
import com.intellij.util.xmlb.XmlSerializerUtil
import java.awt.Dimension
import java.util.UUID
import javax.swing.DefaultCellEditor
import javax.swing.JComponent
import javax.swing.JMenuItem
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
    private val tableModel = DefaultTableModel(
        arrayOf("Name", "Base URL", "API key", "Model filter (contains, comma-separated)"), 0
    )
    private val ids = mutableListOf<String>()           // parallel to table rows
    private val table = JBTable(tableModel)
    private val temp = JBTextField()
    private val system = JBTextArea(5, 40).apply { lineWrap = true; wrapStyleWord = true }
    private var snapshot: List<List<String>> = emptyList()
    private val s get() = LmSettings.get().state

    init {
        table.putClientProperty("terminateEditOnFocusLost", true)
        table.preferredScrollableViewportSize = Dimension(640, 150)
        table.columnModel.getColumn(2).apply {
            cellRenderer = object : DefaultTableCellRenderer() {
                override fun setValue(value: Any?) {
                    text = if ((value as? String).isNullOrEmpty()) "" else "••••••••"
                }
            }
            cellEditor = DefaultCellEditor(JPasswordField())
        }
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
                if (r >= 0) { tableModel.removeRow(r); ids.removeAt(r) }
            }
            .disableUpDownActions()
            .createPanel()

        return FormBuilder.createFormBuilder()
            .addLabeledComponentFillVertically("Providers:", providers)
            .addLabeledComponent("Temperature:", temp)
            .addLabeledComponent("System prompt:", JBScrollPane(system))
            .panel
    }

    private fun addRow(id: String, name: String, url: String, key: String, filter: String) {
        ids.add(id)
        tableModel.addRow(arrayOf<Any>(name, url, key, filter))
    }

    /** Rows as [id, name, url, key, filter]. */
    private fun currentRows(): List<List<String>> = (0 until tableModel.rowCount).map { r ->
        listOf(ids[r]) + (0 until 4).map { c -> ((tableModel.getValueAt(r, c) as? String) ?: "").trim() }
    }

    override fun isModified(): Boolean =
        currentRows() != snapshot || temp.text.trim() != s.temperature.toString() || system.text != s.systemPrompt

    override fun apply() {
        table.cellEditor?.stopCellEditing()
        val rows = currentRows()
        val kept = rows.map { it[0] }.toSet()
        s.providers.map { it.id }.filter { it !in kept }.forEach { LmSettings.setApiKey(it, "") }
        s.providers = rows.map { Provider(it[0], it[1], it[2], it[4]) }.toMutableList()
        rows.forEach { LmSettings.setApiKey(it[0], it[3]) }
        s.temperature = temp.text.trim().toDoubleOrNull() ?: 0.2
        s.systemPrompt = system.text
        snapshot = rows
    }

    override fun reset() {
        tableModel.rowCount = 0
        ids.clear()
        s.providers.forEach { addRow(it.id, it.name, it.baseUrl, LmSettings.apiKey(it.id), it.modelFilter) }
        temp.text = s.temperature.toString()
        system.text = s.systemPrompt
        snapshot = currentRows()
    }
}