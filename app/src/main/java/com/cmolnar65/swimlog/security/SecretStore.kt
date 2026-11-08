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

/** Small encrypted preference store. The token is never written to logs or a backup. */
class SecretStore(context: Context) {
    private val prefs = context.getSharedPreferences("private_config", Context.MODE_PRIVATE)
    private val keyAlias = "swimlog_hc_config_key_v1"

    fun save(endpoint: String, token: String) {
        val uri = java.net.URI(endpoint.trim())
        require(uri.scheme.equals("https", ignoreCase = true) && uri.host.equals("cmolnar.com", ignoreCase = true) && uri.userInfo == null && (uri.port == -1 || uri.port == 443) && uri.path in listOf("", "/") && uri.query == null && uri.fragment == null) {
            "Use the official https://cmolnar.com site"
        }
        require(token.isNotBlank()) { "Token is required" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val ciphertext = cipher.doFinal(token.toByteArray(Charsets.UTF_8))
        prefs.edit().putString("endpoint", "https://cmolnar.com")
            .putString("token_iv", Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            .putString("token_ciphertext", Base64.encodeToString(ciphertext, Base64.NO_WRAP))
            .apply()
    }

    fun endpoint(): String? = prefs.getString("endpoint", null)

    fun token(): String? {
        return try {
            val iv = prefs.getString("token_iv", null) ?: return null
            val encrypted = prefs.getString("token_ciphertext", null) ?: return null
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP)))
            String(cipher.doFinal(Base64.decode(encrypted, Base64.NO_WRAP)), Charsets.UTF_8)
        } catch (_: Exception) { null }
    }

    fun clear() { prefs.edit().clear().apply() }

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
