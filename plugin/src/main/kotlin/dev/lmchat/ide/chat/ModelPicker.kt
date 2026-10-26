package dev.lmchat.ide.chat

import com.intellij.icons.AllIcons
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Cursor
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.JPanel

class ModelItem(val providerId: String, val providerName: String, val model: String, val contextLength: Int? = null) {
    val key: String get() = "$providerId::$model"
    override fun toString() = "$providerName / $model"
}

/** A flat model selector. Unlike a combo box it always uses the background color we give it. */
class ModelPicker : JPanel(BorderLayout(6, 0)) {
    private val items = mutableListOf<ModelItem>()
    private val label = JBLabel("No models")
    private val arrow = JBLabel(AllIcons.General.ArrowDown)

    var selected: ModelItem? = null
        private set

    /** Called with the item and whether the user picked it (false = chosen automatically). */
    var onChange: ((ModelItem, Boolean) -> Unit)? = null

    init {
        isOpaque = true
        border = JBUI.Borders.empty(4, 8)
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        add(label, BorderLayout.CENTER)
        add(arrow, BorderLayout.EAST)
        addMouseListener(object : MouseAdapter() {
            override fun mousePressed(e: MouseEvent) = showPopup()
        })
    }

    fun clear() {
        items.clear()
        selected = null
        label.text = "No models"
        toolTipText = null
    }

    /** Adds models; selects [preferredKey] if it is among them, otherwise the first model when nothing is selected. */
    fun add(newItems: List<ModelItem>, preferredKey: String?) {
        items.addAll(newItems)
        val preferred = newItems.firstOrNull { it.key == preferredKey }
        when {
            preferred != null -> select(preferred, byUser = false)
            selected == null -> newItems.firstOrNull()?.let { select(it, byUser = false) }
        }
    }

    private fun select(item: ModelItem, byUser: Boolean) {
        selected = item
        label.text = item.toString()
        toolTipText = item.toString()
        onChange?.invoke(item, byUser)
    }

    private fun showPopup() {
        if (items.isEmpty()) return
        JBPopupFactory.getInstance()
            .createPopupChooserBuilder(items.toList())
            .setItemChosenCallback { select(it, byUser = true) }
            .setNamerForFiltering { it.toString() }
            .setVisibleRowCount(12)
            .createPopup()
            .showUnderneathOf(this)
    }
}