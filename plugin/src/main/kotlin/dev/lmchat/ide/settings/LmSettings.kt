package dev.lmchat.ide.settings

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.util.xmlb.XmlSerializerUtil
import dev.lmchat.core.provider.Provider

/**
 * Persisted settings. The property names are the keys in lmchat.xml; they are unchanged
 * from earlier versions so existing settings keep working.
 */
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
        var completionMode: String = "chat"          // CompletionMode.id
        var fimTemplate: String = "QWEN"             // FimTemplate name
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
    }
}