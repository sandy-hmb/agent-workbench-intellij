package org.agentworkbench.intellij.review

import com.intellij.credentialStore.CredentialAttributes
import com.intellij.credentialStore.Credentials
import com.intellij.credentialStore.generateServiceName
import com.intellij.ide.passwordSafe.PasswordSafe
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

internal object ReviewCredentials {
    fun load(kitRoot: String, host: ReviewHost): String? =
        PasswordSafe.instance.get(attributes(kitRoot, host))?.getPasswordAsString()?.takeIf(String::isNotBlank)

    fun store(kitRoot: String, host: ReviewHost, token: String?) {
        val credentials = token?.trim()?.takeIf(String::isNotBlank)?.let { Credentials(null, it) }
        PasswordSafe.instance.set(attributes(kitRoot, host), credentials, false)
    }

    private fun attributes(kitRoot: String, host: ReviewHost): CredentialAttributes {
        val key = digest("$kitRoot\u0000${host.id}\u0000${host.normalizedOrigin().orEmpty()}")
        return CredentialAttributes(generateServiceName("Agent Workbench code review", key))
    }

    private fun digest(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(StandardCharsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
}
