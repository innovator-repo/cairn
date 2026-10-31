package app.cairn.github

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Keeps the GitHub token encrypted with an AES-GCM key that lives in the Android Keystore and
 * never leaves it. Only the ciphertext is written to app storage. The token is read-only scoped
 * on GitHub's side (a fine-grained personal access token) and is never logged or backed up.
 */
class TokenVault(context: Context) {
    private val prefs = context.getSharedPreferences("cairn_vault", Context.MODE_PRIVATE)

    fun has(): Boolean = prefs.contains(KEY_BLOB)

    fun save(token: String) {
        val cipher = Cipher.getInstance(TRANSFORM)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val ct = cipher.doFinal(token.trim().toByteArray(Charsets.UTF_8))
        val blob = Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + ":" + Base64.encodeToString(ct, Base64.NO_WRAP)
        prefs.edit().putString(KEY_BLOB, blob).apply()
    }

    /** The token, or null when none is stored or the key can no longer open it. */
    fun load(): String? {
        val blob = prefs.getString(KEY_BLOB, null) ?: return null
        return try {
            val (iv, ct) = blob.split(":").map { Base64.decode(it, Base64.NO_WRAP) }
            val cipher = Cipher.getInstance(TRANSFORM)
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
            String(cipher.doFinal(ct), Charsets.UTF_8)
        } catch (_: Exception) {
            null
        }
    }

    fun clear() {
        prefs.edit().remove(KEY_BLOB).apply()
        try { KeyStore.getInstance(PROVIDER).apply { load(null) }.deleteEntry(ALIAS) } catch (_: Exception) {}
    }

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance(PROVIDER).apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER)
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return gen.generateKey()
    }

    private companion object {
        const val PROVIDER = "AndroidKeyStore"
        const val ALIAS = "cairn_github_token"
        const val KEY_BLOB = "token_blob"
        const val TRANSFORM = "AES/GCM/NoPadding"
    }
}
