package com.tube.player.account

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.io.File
import java.security.KeyStore
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Вошедший аккаунт Google: cookie youtube.com и то, что показываем на экране. */
data class Account(
    val cookies: String,
    val visitorData: String? = null,
    val dataSyncId: String? = null,
    val name: String? = null,
    val handle: String? = null,
    val avatar: String? = null,
) {
    /** SAPISID (или __Secure-3PAPISID) — из него считается заголовок авторизации InnerTube. */
    val sapisid: String? get() = cookie("SAPISID") ?: cookie("__Secure-3PAPISID")

    fun cookie(name: String): String? = parseCookies(cookies)[name]

    companion object {
        fun parseCookies(header: String): Map<String, String> = header.split(';').mapNotNull { part ->
            val eq = part.indexOf('=')
            if (eq <= 0) null else part.substring(0, eq).trim() to part.substring(eq + 1).trim()
        }.toMap()
    }
}

/**
 * `Authorization: SAPISIDHASH <ts>_<sha1("<ts> <SAPISID> <origin>")>` — так авторизуется сайт youtube.com
 * (и Metrolist/InnerTune) в запросах InnerTube с cookie.
 */
object SapisidHash {
    fun header(sapisid: String, origin: String = "https://www.youtube.com", nowSeconds: Long = System.currentTimeMillis() / 1000): String {
        val digest = MessageDigest.getInstance("SHA-1").digest("$nowSeconds $sapisid $origin".toByteArray())
        return "SAPISIDHASH ${nowSeconds}_" + digest.joinToString("") { "%02x".format(it) }
    }
}

/** AES-GCM ключом из Android Keystore: ключ не покидает устройство и не попадает в резервные копии. */
class KeystoreCipher(private val alias: String = "tube_player_account") {
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    fun encrypt(plain: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        return cipher.iv + cipher.doFinal(plain)
    }

    fun decrypt(data: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, data, 0, 12))
        return cipher.doFinal(data, 12, data.size - 12)
    }

    fun deleteKey() = runCatching { KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(alias) }
}

/**
 * Хранилище аккаунта: зашифрованный файл в filesDir. Вход необязателен — без него [account] = null,
 * и всё работает анонимно.
 */
class AccountStore(context: Context, private val cipher: KeystoreCipher = KeystoreCipher()) {
    private val file = AtomicFile(File(context.filesDir, "account.bin"))
    private val _account = MutableStateFlow(load())
    val account: StateFlow<Account?> = _account.asStateFlow()

    fun save(account: Account) {
        _account.value = account
        val json = JSONObject().apply {
            put("cookies", account.cookies)
            account.visitorData?.let { put("visitorData", it) }
            account.dataSyncId?.let { put("dataSyncId", it) }
            account.name?.let { put("name", it) }
            account.handle?.let { put("handle", it) }
            account.avatar?.let { put("avatar", it) }
        }
        var out: java.io.FileOutputStream? = null
        try {
            out = file.startWrite()
            out.write(cipher.encrypt(json.toString().toByteArray()))
            file.finishWrite(out)
        } catch (_: Exception) {
            out?.let { file.failWrite(it) }
        }
    }

    fun clear() {
        _account.value = null
        file.delete()
        cipher.deleteKey()
    }

    private fun load(): Account? = runCatching {
        val o = JSONObject(String(cipher.decrypt(file.readFully())))
        Account(
            cookies = o.getString("cookies"),
            visitorData = o.optString("visitorData").ifEmpty { null },
            dataSyncId = o.optString("dataSyncId").ifEmpty { null },
            name = o.optString("name").ifEmpty { null },
            handle = o.optString("handle").ifEmpty { null },
            avatar = o.optString("avatar").ifEmpty { null },
        )
    }.getOrNull()
}
