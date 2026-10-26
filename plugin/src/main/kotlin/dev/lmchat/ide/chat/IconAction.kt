package dev.lmchat.ide.chat

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAwareAction
import javax.swing.Icon

/** A toolbar button defined by a lambda. */
internal fun iconAction(text: String, icon: Icon, enabled: () -> Boolean = { true }, action: () -> Unit): AnAction =
    object : DumbAwareAction(text, text, icon) {
        override fun actionPerformed(e: AnActionEvent) = action()
        override fun update(e: AnActionEvent) {
            e.presentation.isEnabled = enabled()
        }
    }