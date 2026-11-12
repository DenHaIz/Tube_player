package com.tube.player.adblock

/**
 * Блокировщик рекламы.
 *
 * 1. Видео берутся из потоков напрямую (NewPipeExtractor), без плеера YouTube, — рекламных роликов
 *    (pre-roll, mid-roll) в них нет в принципе.
 * 2. [isBlocked] обрывает запросы к рекламным и следящим адресам — в OkHttp и в WebView входа.
 * 3. [isAdRenderer] — рекламные и промо-элементы выдачи InnerTube (при входе в аккаунт), их убираем из списков.
 * 4. Спонсорские вставки внутри самих роликов пропускает SponsorBlock.
 */
object AdBlock {
    private val Hosts = setOf(
        "doubleclick.net", "googleadservices.com", "googlesyndication.com", "google-analytics.com",
        "googletagmanager.com", "googletagservices.com", "adservice.google.com", "pagead2.googlesyndication.com",
        "imasdk.googleapis.com", "ade.googlesyndication.com", "static.doubleclick.net", "ad.doubleclick.net",
        "app-measurement.com", "firebaselogging-pa.googleapis.com", "s.youtube.com",
    )

    /** Пути на youtube.com, по которым идут показы рекламы и её статистика. */
    private val Paths = listOf(
        "/pagead/", "/api/stats/ads", "/api/stats/atr", "/ptracking", "/get_midroll_", "/youtubei/v1/log_event",
        "/error_204", "/csi_204",
    )

    fun isBlocked(host: String, path: String): Boolean {
        val h = host.lowercase()
        if (Hosts.any { h == it || h.endsWith(".$it") }) return true
        if (h.endsWith("youtube.com") || h.endsWith("googlevideo.com")) {
            return Paths.any { path.startsWith(it) }
        }
        return false
    }

    /** Типы элементов выдачи InnerTube, которые являются рекламой или промо. */
    val AdRenderers = setOf(
        "adSlotRenderer", "promotedVideoRenderer", "compactPromotedVideoRenderer", "searchPyvRenderer",
        "promotedSparklesWebRenderer", "promotedSparklesTextSearchRenderer", "displayAdRenderer",
        "inFeedAdLayoutRenderer", "bannerPromoRenderer", "statementBannerRenderer", "brandVideoShelfRenderer",
        "brandVideoSingletonRenderer", "movieOfferModuleRenderer", "mealbarPromoRenderer", "adActionInterstitialRenderer",
        "playerLegacyDesktopWatchAdsRenderer", "companionAdRenderer", "actionCompanionAdRenderer",
    )

    fun isAdRenderer(key: String): Boolean = key in AdRenderers
}
