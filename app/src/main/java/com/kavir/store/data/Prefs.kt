package com.kavir.store.data

import android.content.Context
import android.content.res.Configuration
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import java.util.Locale
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** AES-GCM encryption with a non-exportable key held in the Android Keystore. */
object SecureStore {
    private const val ALIAS = "kavir_admin_key"
    private const val TRANSFORM = "AES/GCM/NoPadding"

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(
                ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return gen.generateKey()
    }

    fun encrypt(plain: String): String {
        val cipher = Cipher.getInstance(TRANSFORM)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val iv = cipher.iv
        val ct = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(iv + ct, Base64.NO_WRAP)
    }

    fun decrypt(encoded: String): String? = try {
        val all = Base64.decode(encoded, Base64.NO_WRAP)
        val iv = all.copyOfRange(0, 12)
        val ct = all.copyOfRange(12, all.size)
        val cipher = Cipher.getInstance(TRANSFORM)
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
        String(cipher.doFinal(ct), Charsets.UTF_8)
    } catch (e: Exception) {
        null
    }
}

class Prefs(context: Context) {
    private val sp = context.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    var language: String
        get() = sp.getString("lang", "system") ?: "system"
        set(v) = sp.edit().putString("lang", v).apply()

    var theme: String
        get() = sp.getString("theme", "system") ?: "system"
        set(v) = sp.edit().putString("theme", v).apply()

    /** Admin token, stored encrypted with a Keystore-backed key. Never leaves the device. */
    var adminToken: String?
        get() = sp.getString("admin_token", null)?.let { SecureStore.decrypt(it) }
        set(v) {
            if (v == null) sp.edit().remove("admin_token").apply()
            else sp.edit().putString("admin_token", SecureStore.encrypt(v)).apply()
        }

    companion object {
        const val NAME = "kavir_prefs"
    }
}

object LocaleHelper {
    /** Applies the in-app language (if any) to the activity's base context. */
    fun wrap(base: Context): Context {
        val lang = base.getSharedPreferences(Prefs.NAME, Context.MODE_PRIVATE)
            .getString("lang", "system") ?: "system"
        if (lang == "system") return base
        val locale = Locale(lang)
        Locale.setDefault(locale)
        val cfg = Configuration(base.resources.configuration)
        cfg.setLocale(locale)
        cfg.setLayoutDirection(locale)
        return base.createConfigurationContext(cfg)
    }
}
