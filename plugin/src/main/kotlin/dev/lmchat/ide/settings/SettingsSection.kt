package dev.lmchat.ide.settings

import javax.swing.JComponent

/** One block of the settings page. */
interface SettingsSection {
    val component: JComponent
    fun loadFrom(state: LmSettings.Data)
    fun saveTo(state: LmSettings.Data)

        /** A value that changes whenever the user edits something in this section. */
    fun snapshot(): Any?
}