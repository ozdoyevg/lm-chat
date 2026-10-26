package dev.lmchat.ide.settings

import com.intellij.ui.ToolbarDecorator
import com.intellij.ui.table.JBTable
import dev.lmchat.core.provider.Provider
import dev.lmchat.core.provider.ProviderPresets
import java.awt.Dimension
import java.util.UUID
import javax.swing.DefaultCellEditor
import javax.swing.JComponent
import javax.swing.JMenuItem
import javax.swing.JPasswordField
import javax.swing.JPopupMenu
import javax.swing.table.DefaultTableCellRenderer
import javax.swing.table.DefaultTableModel

data class ProviderRow(val id: String, val name: String, val baseUrl: String, val apiKey: String, val modelFilter: String)

/** The table of providers. API keys are kept in Password Safe, never in the settings file. */
class ProvidersSection : SettingsSection {
    private val tableModel = DefaultTableModel(
        arrayOf("Name", "Base URL", "API key", "Model filter (contains, comma-separated)"), 0
    )
    private val rowIds = mutableListOf<String>()      // parallel to the table rows
    private val table = JBTable(tableModel)
    private val changeListeners = mutableListOf<() -> Unit>()

    init {
        table.putClientProperty("terminateEditOnFocusLost", true)
        table.preferredScrollableViewportSize = Dimension(640, 130)
        table.columnModel.getColumn(KEY_COLUMN).apply {
            cellRenderer = object : DefaultTableCellRenderer() {
                override fun setValue(value: Any?) {
                    text = if ((value as? String).isNullOrEmpty()) "" else "••••••••"
                }
            }
            cellEditor = DefaultCellEditor(JPasswordField())
        }
        tableModel.addTableModelListener { changeListeners.forEach { it() } }
    }

    override val component: JComponent by lazy { createPanel() }

    fun addChangeListener(listener: () -> Unit) { changeListeners.add(listener) }

    fun rows(): List<ProviderRow> = (0 until tableModel.rowCount).map { row ->
        ProviderRow(rowIds[row], cell(row, 0), cell(row, 1), cell(row, 2), cell(row, 3))
    }

    override fun snapshot(): Any? = rows()

    override fun loadFrom(state: LmSettings.Data) {
        tableModel.rowCount = 0
        rowIds.clear()
        state.providers.forEach { addRow(it.id, it.name, it.baseUrl, IdeCredentials.apiKey(it.id), it.modelFilter) }
    }

    override fun saveTo(state: LmSettings.Data) {
        table.cellEditor?.stopCellEditing()
        val rows = rows()
        val keptIds = rows.map { it.id }.toSet()
        state.providers.map { it.id }.filter { it !in keptIds }.forEach { IdeCredentials.setApiKey(it, "") }
        state.providers = rows.map { Provider(it.id, it.name, it.baseUrl, it.modelFilter) }.toMutableList()
        rows.forEach { IdeCredentials.setApiKey(it.id, it.apiKey) }
    }

    private fun createPanel(): JComponent =
        ToolbarDecorator.createDecorator(table)
            .setAddAction { button ->
                val menu = JPopupMenu()
                ProviderPresets.all.forEach { preset ->
                    menu.add(JMenuItem(preset.name).apply {
                        addActionListener {
                            addRow(UUID.randomUUID().toString(), preset.name, preset.baseUrl, "", preset.modelFilter)
                        }
                    })
                }
                menu.show(button.contextComponent, 0, 0)
            }
            .setRemoveAction {
                table.cellEditor?.stopCellEditing()
                val row = table.selectedRow
                if (row >= 0) { rowIds.removeAt(row); tableModel.removeRow(row) }
            }
            .disableUpDownActions()
            .createPanel()

    private fun addRow(id: String, name: String, baseUrl: String, apiKey: String, modelFilter: String) {
        rowIds.add(id)
        tableModel.addRow(arrayOf<Any>(name, baseUrl, apiKey, modelFilter))
    }

    private fun cell(row: Int, column: Int) = ((tableModel.getValueAt(row, column) as? String) ?: "").trim()

    private companion object {
        const val KEY_COLUMN = 2
    }
}