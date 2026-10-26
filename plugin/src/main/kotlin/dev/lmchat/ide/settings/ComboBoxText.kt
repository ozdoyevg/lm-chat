package dev.lmchat.ide.settings

import com.intellij.openapi.ui.ComboBox

/** The text of an editable combo box, including what was typed but not yet committed. */
internal fun ComboBox<String>.currentText(): String =
    (editor.item ?: selectedItem)?.toString()?.trim().orEmpty()