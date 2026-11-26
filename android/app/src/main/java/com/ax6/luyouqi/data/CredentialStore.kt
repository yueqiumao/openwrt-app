package com.ax6.luyouqi.data

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
 * 凭据存储：地址/端口/用户名是普通配置，密码用 Android Keystore 里的 AES-256-GCM 密钥加密后存密文。
 * 明文密码不落盘、不进日志；allowBackup 已关闭。
 */
class CredentialStore(context: Context) {

    data class Profile(
        val host: String,
        val port: Int,
        val secure: Boolean,
        val username: String,
    )

    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun loadProfile(): Profile? {
        val host = prefs.getString(KEY_HOST, null) ?: return null
        return Profile(
            host = host,
            port = prefs.getInt(KEY_PORT, 80),
            secure = prefs.getBoolean(KEY_SECURE, false),
            username = prefs.getString(KEY_USER, "") ?: "",
        )
    }

    fun loadPassword(): String? {
        val encrypted = prefs.getString(KEY_PASSWORD, null) ?: return null
        return runCatching { decrypt(encrypted) }.getOrNull()
    }

    fun save(profile: Profile, password: String) {
        prefs.edit()
            .putString(KEY_HOST, profile.host)
            .putInt(KEY_PORT, profile.port)
            .putBoolean(KEY_SECURE, profile.secure)
            .putString(KEY_USER, profile.username)
            .putString(KEY_PASSWORD, encrypt(password))
            .apply()
    }

    fun clear() {
        prefs.edit().clear().apply()
    }

    // ---- 加解密 -------------------------------------------------------------

    private fun encrypt(plain: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val iv = cipher.iv
        val body = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        val joined = ByteArray(iv.size + body.size)
        iv.copyInto(joined, 0)
        body.copyInto(joined, iv.size)
        return Base64.encodeToString(joined, Base64.NO_WRAP)
    }

    private fun decrypt(encoded: String): String {
        val joined = Base64.decode(encoded, Base64.NO_WRAP)
        val iv = joined.copyOfRange(0, IV_LENGTH)
        val body = joined.copyOfRange(IV_LENGTH, joined.size)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(TAG_BITS, iv))
        return String(cipher.doFinal(body), Charsets.UTF_8)
    }

    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    private companion object {
        const val PREFS = "ax6_secure"
        const val KEY_HOST = "host"
        const val KEY_PORT = "port"
        const val KEY_SECURE = "secure"
        const val KEY_USER = "username"
        const val KEY_PASSWORD = "password_cipher"

        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val ALIAS = "ax6_credential"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_LENGTH = 12
        const val TAG_BITS = 128
    }
}
