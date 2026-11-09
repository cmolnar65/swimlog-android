package com.cmolnar65.swimlog.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Encrypted preference store for the owner token and short-lived PKCE transaction. */
class SecretStore(context: Context) {
    private val prefs = context.getSharedPreferences("private_config", Context.MODE_PRIVATE)
    private val keyAlias = "swimlog_hc_config_key_v1"

    fun save(endpoint: String, token: String) {
        saveEndpoint(endpoint)
        saveToken(token)
    }

    fun saveEndpoint(endpoint: String) {
        val uri = java.net.URI(endpoint.trim())
        require(uri.scheme?.equals("https", ignoreCase = true) == true && uri.host?.equals("cmolnar.com", ignoreCase = true) == true && uri.userInfo == null && (uri.port == -1 || uri.port == 443) && uri.path in listOf("", "/") && uri.query == null && uri.fragment == null) {
            "Use the official https://cmolnar.com site"
        }
        prefs.edit().putString("endpoint", "https://cmolnar.com").apply()
    }

    fun saveToken(token: String) {
        require(token.isNotBlank()) { "Token is required" }
        saveEncrypted("token_iv", "token_ciphertext", token)
    }

    fun saveAuthorizationAttempt(state: String, verifier: String) {
        require(state.matches(Regex("^[A-Za-z0-9_-]{43}$")) && verifier.matches(Regex("^[A-Za-z0-9._~-]{43,128}$"))) {
            "Invalid sign-in state"
        }
        saveEncrypted("authorization_state_iv", "authorization_state", state)
        saveEncrypted("authorization_verifier_iv", "authorization_verifier", verifier)
    }

    fun authorizationAttempt(): Pair<String, String>? {
        val state = decrypt("authorization_state_iv", "authorization_state") ?: return null
        val verifier = decrypt("authorization_verifier_iv", "authorization_verifier") ?: return null
        return state to verifier
    }

    fun clearAuthorizationAttempt() {
        prefs.edit().remove("authorization_state_iv").remove("authorization_state")
            .remove("authorization_verifier_iv").remove("authorization_verifier").apply()
    }

    fun endpoint(): String? = prefs.getString("endpoint", null)

    fun token(): String? {
        return decrypt("token_iv", "token_ciphertext")
    }

    fun clear() { prefs.edit().clear().apply() }

    private fun saveEncrypted(ivKey: String, valueKey: String, value: String) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val encrypted = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        prefs.edit().putString(ivKey, Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            .putString(valueKey, Base64.encodeToString(encrypted, Base64.NO_WRAP)).apply()
    }

    private fun decrypt(ivKey: String, valueKey: String): String? {
        return try {
            val iv = prefs.getString(ivKey, null) ?: return null
            val encrypted = prefs.getString(valueKey, null) ?: return null
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP)))
            String(cipher.doFinal(Base64.decode(encrypted, Base64.NO_WRAP)), Charsets.UTF_8)
        } catch (_: Exception) { null }
    }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(keyAlias, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(KeyGenParameterSpec.Builder(keyAlias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setRandomizedEncryptionRequired(true).build())
        return generator.generateKey()
    }
}
