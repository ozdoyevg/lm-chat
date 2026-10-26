package dev.lmchat.ide.settings

import com.intellij.credentialStore.CredentialAttributes
import com.intellij.credentialStore.generateServiceName
import com.intellij.ide.passwordSafe.PasswordSafe
import dev.lmchat.core.provider.CredentialStore

/** API keys in the IDE's Password Safe, one entry per provider id. */
object IdeCredentials : CredentialStore {
    private fun attributes(providerId: String) = CredentialAttributes(generateServiceName("LM Chat", providerId))

    override fun apiKey(providerId: String): String =
        PasswordSafe.instance.getPassword(attributes(providerId)) ?: ""

    fun setApiKey(providerId: String, key: String) {
        PasswordSafe.instance.setPassword(attributes(providerId), key.ifBlank { null })
    }
}