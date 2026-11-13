package com.tube.update

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/** Релиз на GitHub, который новее установленной версии. */
data class Release(
    val version: String,
    val notes: String,
    val apkUrl: String,
    val apkSize: Long,
    val pageUrl: String,
    /** SHA-256 файла из API GitHub; null — проверяем только подпись. */
    val sha256: String? = null,
)

sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data object UpToDate : UpdateState
    data class Available(val release: Release) : UpdateState
    data class Downloading(val release: Release, val progress: Float) : UpdateState
    data class Ready(val release: Release, val apk: File) : UpdateState
    data class Failed(val message: String) : UpdateState
}

/** Откуда и что обновлять: репозиторий GitHub и имя APK в релизе (`Tube-X.Y.apk`, `TubePlayer-X.Y.apk`). */
data class UpdateSource(
    /** «DenHaIz/Tube_DPI». */
    val repo: String,
    /** Начало имени APK: «Tube-» или «TubePlayer-». */
    val apkPrefix: String,
    val currentVersion: String,
    val userAgent: String,
)

/** Где приложение хранит настройку автопроверки и время последней проверки. */
interface UpdatePrefs {
    suspend fun autoCheck(): Boolean
    suspend fun lastCheck(): Long
    suspend fun setLastCheck(time: Long)
}

/**
 * Обновления из релизов GitHub (Tube — DenHaIz/Tube_DPI, Tube Player — DenHaIz/Tube_player): последний релиз
 * сравнивается с версией приложения, APK скачивается в кэш и открывается системным установщиком
 * (подпись та же, поэтому ставится поверх). Нужен FileProvider `${applicationId}.files` с cache-path «updates/».
 */
class UpdateChecker(private val context: Context, private val source: UpdateSource, private val settings: UpdatePrefs) {
    private val repoUrl = "https://github.com/${source.repo}"
    private val latestUrl = "https://api.github.com/repos/${source.repo}/releases/latest"
    private val latestPage = "$repoUrl/releases/latest"
    private val apkPattern = Regex(Regex.escape(source.apkPrefix) + """\d+(\.\d+){0,3}\.apk""")
    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state.asStateFlow()

    /** Тихая проверка при запуске: не чаще раза в [AUTO_INTERVAL_MS] и только если включено. */
    suspend fun checkIfDue() {
        if (!settings.autoCheck()) return
        if (System.currentTimeMillis() - settings.lastCheck() < AUTO_INTERVAL_MS) return
        check(silent = true)
    }

    suspend fun check(silent: Boolean = false) {
        if (_state.value is UpdateState.Downloading) return
        if (!silent) _state.value = UpdateState.Checking
        val result = withContext(Dispatchers.IO) { runCatching { fetchLatest() } }
        settings.setLastCheck(System.currentTimeMillis())
        _state.value = result.fold(
            onSuccess = { release -> if (release != null) UpdateState.Available(release) else if (silent) UpdateState.Idle else UpdateState.UpToDate },
            onFailure = { if (silent) UpdateState.Idle else UpdateState.Failed(it.message ?: it.javaClass.simpleName) },
        )
    }

    /**
     * Скачивает APK: сначала с GitHub, затем через зеркала. Файл с каждого источника проверяется —
     * размер, SHA-256 из релиза (если известен), имя пакета, версия и подпись (та же, что у установленного приложения), —
     * поэтому подменённый зеркалом файл не дойдёт до установщика.
     */
    suspend fun download(release: Release) {
        // Второе нажатие во время скачивания не запускает параллельную запись в тот же файл.
        if (_state.value is UpdateState.Downloading) return
        _state.value = UpdateState.Downloading(release, 0f)
        val result = withContext(Dispatchers.IO) {
            runCatching {
                val dir = File(context.cacheDir, "updates").apply { mkdirs() }
                dir.listFiles()?.forEach { it.delete() }
                val file = File(dir, "${source.apkPrefix}${release.version}.apk")
                var lastError: Exception? = null
                for (url in mirrorsOf(release.apkUrl)) {
                    try {
                        fetchTo(url, file, release)
                        verify(file, release)
                        return@runCatching file
                    } catch (e: Exception) {
                        lastError = e
                        file.delete()
                        _state.value = UpdateState.Downloading(release, 0f)
                    }
                }
                throw lastError ?: IllegalStateException("no mirrors")
            }
        }
        _state.value = result.fold(
            onSuccess = { UpdateState.Ready(release, it) },
            onFailure = { UpdateState.Failed(it.message ?: it.javaClass.simpleName) },
        )
    }

    private fun fetchTo(url: String, file: File, release: Release) {
        val connection = open(url)
        try {
            connection.inputStream.use { input ->
                file.outputStream().use { output ->
                    val total = connection.contentLengthLong.takeIf { it > 0 } ?: release.apkSize
                    val buffer = ByteArray(64 * 1024)
                    var read = 0L
                    var shown = -1
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        output.write(buffer, 0, n)
                        read += n
                        if (read > MAX_APK_BYTES) error("file too large")
                        // Не чаще раза на процент: каждое значение перерисовывает экран.
                        val percent = if (total > 0) (read * 100 / total).toInt() else -1
                        if (percent != shown && percent >= 0) {
                            shown = percent
                            _state.value = UpdateState.Downloading(release, (percent / 100f).coerceAtMost(1f))
                        }
                    }
                }
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun verify(file: File, release: Release) {
        if (release.apkSize > 0 && file.length() != release.apkSize) error("size mismatch")
        release.sha256?.let { expected ->
            val md = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    md.update(buffer, 0, n)
                }
            }
            val actual = md.digest().joinToString("") { "%02x".format(it) }
            if (!actual.equals(expected, ignoreCase = true)) error("checksum mismatch")
        }
        val pm = context.packageManager
        val archive = archiveInfo(pm, file.absolutePath) ?: error("not an APK")
        if (archive.packageName != context.packageName) error("wrong package")
        if (!isNewer(archive.versionName.orEmpty(), source.currentVersion)) error("not newer")
        val installed = signatures(installedInfo(pm))
        // Часть прошивок не отдаёт signingInfo архива — тогда старый способ (подписи v1/v2 тем же сертификатом).
        @Suppress("DEPRECATION")
        val incoming = signatures(archive).ifEmpty {
            pm.getPackageArchiveInfo(file.absolutePath, PackageManager.GET_SIGNATURES)?.signatures.orEmpty().map { it.toCharsString() }.toSet()
        }
        // Без подписи или с чужой — не ставим (если подпись установленного неизвестна, решит установщик Android).
        if (incoming.isEmpty()) error("unsigned")
        if (installed.isNotEmpty() && installed != incoming) error("signature mismatch")
    }

    @Suppress("DEPRECATION")
    private fun archiveInfo(pm: PackageManager, path: String): PackageInfo? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) pm.getPackageArchiveInfo(path, PackageManager.GET_SIGNING_CERTIFICATES)
        else pm.getPackageArchiveInfo(path, PackageManager.GET_SIGNATURES)

    @Suppress("DEPRECATION")
    private fun installedInfo(pm: PackageManager): PackageInfo? = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        else pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNATURES)
    }.getOrNull()

    @Suppress("DEPRECATION")
    private fun signatures(info: PackageInfo?): Set<String> {
        val list = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info?.signingInfo?.apkContentsSigners else info?.signatures
        return list.orEmpty().map { it.toCharsString() }.toSet()
    }

    /** Разрешено ли приложению ставить APK (Android 8+ спрашивает отдельно для каждого источника). */
    fun canInstall(): Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.O || context.packageManager.canRequestPackageInstalls()

    fun installPermissionIntent(): Intent =
        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))

    fun installIntent(apk: File): Intent {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", apk)
        return Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    /** Для превью и скриншотов: показать найденный релиз без сети. */
    fun previewAvailable(release: Release) {
        _state.value = UpdateState.Available(release)
    }

    fun dismiss() {
        _state.value = UpdateState.Idle
    }

    /**
     * Где узнать последнюю версию, по очереди: API GitHub, API через зеркало, страница релиза (редирект на тег)
     * напрямую и через зеркала. API даёт ещё и SHA-256 файла; страница — только номер версии.
     */
    private fun fetchLatest(): Release? {
        var lastError: Exception? = null
        val sources: List<() -> Release?> = listOf<() -> Release?>(
            { fetchFromApi(latestUrl) },
            // Через зеркало берём только версию и размер: контрольной суммой от зеркала проверять его же файл бессмысленно.
            { fetchFromApi(API_MIRROR + latestUrl)?.copy(sha256 = null) },
            { fetchFromPage(latestPage) },
        ) + PAGE_MIRRORS.map { mirror -> { fetchFromPage(mirror + latestPage) } }
        for (source in sources) {
            try {
                return source()
            } catch (e: Exception) {
                lastError = e
            }
        }
        throw lastError ?: IllegalStateException("no sources")
    }

    private fun fetchFromApi(url: String): Release? {
        val connection = open(url, "Accept" to "application/vnd.github+json")
        val json = try {
            // Не больше 1 МБ: ответ может прийти через зеркало — бесконечный ответ не должен уронить приложение.
            JSONObject(connection.inputStream.use { input ->
                val out = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(16 * 1024)
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    out.write(buffer, 0, n)
                    if (out.size() > MAX_API_BYTES) error("response too large")
                }
                String(out.toByteArray(), Charsets.UTF_8)
            })
        } finally {
            connection.disconnect()
        }
        val tag = json.getString("tag_name").takeIf { TagPattern.matches(it) } ?: error("bad tag")
        val version = tag.removePrefix("v")
        if (!isNewer(version, source.currentVersion)) return null
        val assets = json.getJSONArray("assets")
        val apk = (0 until assets.length()).map { assets.getJSONObject(it) }
            .firstOrNull { apkPattern.matches(it.getString("name")) } ?: return null
        return Release(
            version = version,
            notes = json.optString("body"),
            // Адрес собираем сами: ответ мог прийти через зеркало.
            apkUrl = "$repoUrl/releases/download/$tag/${apk.getString("name")}",
            apkSize = apk.optLong("size"),
            pageUrl = "$repoUrl/releases/tag/$tag",
            sha256 = apk.optString("digest").removePrefix("sha256:").takeIf { it.length == 64 },
        )
    }

    /** …/releases/latest перенаправляет на …/tag/vX.Y; APK у каждого релиза называется <apkPrefix>X.Y.apk. */
    private fun fetchFromPage(url: String): Release? {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 20_000
            instanceFollowRedirects = false
            setRequestProperty("User-Agent", source.userAgent)
        }
        val location = try {
            connection.getHeaderField("Location") ?: error("HTTP ${connection.responseCode}")
        } finally {
            connection.disconnect()
        }
        val tag = location.substringAfterLast("/tag/", "").takeIf { TagPattern.matches(it) } ?: error("no release")
        val version = tag.removePrefix("v")
        if (!isNewer(version, source.currentVersion)) return null
        return Release(
            version = version,
            notes = "",
            apkUrl = "$repoUrl/releases/download/$tag/${source.apkPrefix}$version.apk",
            apkSize = 0,
            pageUrl = "$repoUrl/releases/tag/$tag",
        )
    }

    /** Заголовки задаются до подключения: после обращения к responseCode менять запрос уже нельзя. */
    private fun open(url: String, vararg headers: Pair<String, String>): HttpURLConnection {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 20_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", source.userAgent)
            headers.forEach { (name, value) -> setRequestProperty(name, value) }
        }
        val code = connection.responseCode
        if (code !in 200..299) {
            connection.disconnect()
            error("HTTP $code")
        }
        return connection
    }

    companion object {
        private const val MAX_API_BYTES = 1 shl 20

        private const val AUTO_INTERVAL_MS = 6 * 60 * 60_000L
        private const val MAX_APK_BYTES = 100L * 1024 * 1024

        /** Прокси GitHub для сетей, где github.com или его CDN недоступны (проверены: отдают APK целиком). */
        private val DOWNLOAD_MIRRORS = listOf("https://ghfast.top/", "https://gh-proxy.com/", "https://gh-proxy.org/", "https://ghproxy.net/")
        private const val API_MIRROR = "https://gh-proxy.com/"
        private val PAGE_MIRRORS = listOf("https://ghfast.top/", "https://ghproxy.net/")

        private val TagPattern = Regex("""v\d+(\.\d+){0,3}""")

        /** Прямая ссылка, затем она же через зеркала. */
        fun mirrorsOf(url: String): List<String> = listOf(url) + DOWNLOAD_MIRRORS.map { it + url }

        /** Сравнение версий по числам: 0.10 > 0.9, 1.0 > 0.9.9. */
        fun isNewer(candidate: String, current: String): Boolean {
            val a = candidate.split('.', '-').map { it.toIntOrNull() ?: 0 }
            val b = current.split('.', '-').map { it.toIntOrNull() ?: 0 }
            for (i in 0 until maxOf(a.size, b.size)) {
                val x = a.getOrElse(i) { 0 }
                val y = b.getOrElse(i) { 0 }
                if (x != y) return x > y
            }
            return false
        }
    }
}
