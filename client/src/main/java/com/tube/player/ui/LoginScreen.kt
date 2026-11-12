package com.tube.player.ui

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.webkit.ProxyConfig
import androidx.webkit.ProxyController
import androidx.webkit.WebViewFeature
import com.tube.player.account.Account
import com.tube.player.adblock.AdBlock
import com.tube.ui.i18n.tr
import com.tube.ui.theme.TubeIcons
import com.tube.ui.theme.TubeTheme
import java.io.ByteArrayInputStream
import java.util.concurrent.Executor

/** Страница входа Google; после входа она переходит на youtube.com — оттуда берём cookie. */
const val LoginUrl = "https://accounts.google.com/ServiceLogin?service=youtube&uilel=3&passive=true&continue=" +
    "https%3A%2F%2Fwww.youtube.com%2Fsignin%3Faction_handle_signin%3Dtrue%26app%3Ddesktop%26next%3Dhttps%253A%252F%252Fwww.youtube.com%252F"

/**
 * Вход с адресом аккаунта, выбранного в системном списке аккаунтов телефона: Google сразу предлагает подтвердить
 * вход на телефоне («Да, это я») или ключом доступа — без ввода адреса и пароля (если это включено в аккаунте).
 */
fun loginUrl(email: String?): String =
    if (email.isNullOrBlank()) LoginUrl else LoginUrl + "&Email=" + java.net.URLEncoder.encode(email, "UTF-8") + "&login_hint=" + java.net.URLEncoder.encode(email, "UTF-8")

/**
 * Вход в Google в WebView. Трафик WebView идёт через тот же byedpi (ProxyController), пароль видит только
 * страница Google. Как только открылся youtube.com с cookie SAPISID — вход завершён.
 * Если WebView не умеет прокси (очень старый), вход всё равно пробуем — напрямую.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun LoginScreen(proxyPort: Int?, onDone: (Account) -> Unit, onClose: () -> Unit, email: String? = null) {
    var loading by remember { mutableStateOf(true) }
    var proxyReady by remember { mutableStateOf(proxyPort == null || !WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE)) }
    val direct = Executor { it.run() }

    DisposableEffect(proxyPort) {
        if (proxyPort != null && WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE)) {
            val config = ProxyConfig.Builder().addProxyRule("socks5://127.0.0.1:$proxyPort").addDirect().build()
            ProxyController.getInstance().setProxyOverride(config, direct) { proxyReady = true }
        }
        onDispose {
            if (WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE)) {
                runCatching { ProxyController.getInstance().clearProxyOverride(direct) {} }
            }
        }
    }

    Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            GlassIconButton(TubeIcons.Close, tr("Закрыть", "Close"), onClose, size = 44.dp)
            Text(
                tr("Вход в Google", "Sign in with Google"),
                style = MaterialTheme.typography.titleLarge,
                color = TubeTheme.colors.textPrimary,
                textAlign = TextAlign.Center,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.size(44.dp))
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (proxyReady) {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { context ->
                        WebView(context).apply {
                            settings.javaScriptEnabled = true
                            settings.domStorageEnabled = true
                            // Без «wv» в User-Agent: иначе Google называет браузер небезопасным.
                            settings.userAgentString = settings.userAgentString.replace("; wv)", ")").replace(Regex("""Version/\S+ """), "")
                            CookieManager.getInstance().setAcceptCookie(true)
                            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                            webViewClient = object : WebViewClient() {
                                override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                                    val url = request.url
                                    if (AdBlock.isBlocked(url.host.orEmpty(), url.path.orEmpty())) {
                                        return WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))
                                    }
                                    return null
                                }

                                override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                                    loading = true
                                }

                                override fun onPageFinished(view: WebView, url: String?) {
                                    loading = false
                                    val host = url?.let { android.net.Uri.parse(it).host }.orEmpty()
                                    if (!host.endsWith("youtube.com")) return
                                    val cookies = CookieManager.getInstance().getCookie("https://www.youtube.com").orEmpty()
                                    if (Account(cookies).sapisid == null) return
                                    CookieManager.getInstance().flush()
                                    view.evaluateJavascript("(function(){try{return JSON.stringify([ytcfg.get('VISITOR_DATA'),ytcfg.get('DATASYNC_ID')])}catch(e){return null}})()") { result ->
                                        val parts = runCatching { org.json.JSONArray(org.json.JSONTokener(result).nextValue() as String) }.getOrNull()
                                        onDone(
                                            Account(
                                                cookies = cookies,
                                                visitorData = parts?.optString(0)?.takeIf { it.isNotEmpty() && it != "null" },
                                                dataSyncId = parts?.optString(1)?.substringBefore("||")?.takeIf { it.isNotEmpty() && it != "null" },
                                            ),
                                        )
                                    }
                                }
                            }
                            loadUrl(loginUrl(email))
                        }
                    },
                    onRelease = { it.destroy() },
                )
            }
            if (loading) Loading(Modifier.align(Alignment.Center))
        }
    }
}
