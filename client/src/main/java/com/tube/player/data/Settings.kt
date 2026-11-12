package com.tube.player.data

import android.content.Context
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.tube.byedpi.Strategies
import com.tube.byedpi.Strategy
import com.tube.player.sponsorblock.SegmentAction
import com.tube.player.sponsorblock.SponsorCategory
import com.tube.strategy.StrategySettings
import com.tube.ui.i18n.AppLanguage
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

enum class ThemeMode { System, Light, Dark }

/** Обход: встроенный byedpi или напрямую (пользователь уже в VPN или живёт не в России). */
enum class BypassMode { Auto, Off }

/** Предел качества по умолчанию; Auto — без предела (плеер выбирает сам по скорости). */
enum class QualityCap(val height: Int) { Auto(Int.MAX_VALUE), P2160(2160), P1440(1440), P1080(1080), P720(720), P480(480), P360(360) }

/** Все настройки плеера одним снимком — для экрана. */
data class PlayerSettings(
    val theme: ThemeMode = ThemeMode.System,
    val amoled: Boolean = false,
    val dynamicColors: Boolean = false,
    val language: AppLanguage = AppLanguage.Russian,
    val bypass: BypassMode = BypassMode.Auto,
    val strategyId: String = Strategies.default.id,
    val watchdog: Boolean = true,
    val qualityWifi: QualityCap = QualityCap.P1080,
    val qualityMobile: QualityCap = QualityCap.P480,
    val background: Boolean = true,
    val autoPip: Boolean = true,
    val hideShorts: Boolean = false,
    val returnDislikes: Boolean = true,
    val adBlock: Boolean = true,
    val rememberSpeed: Boolean = false,
    val speed: Float = 1f,
    val gestures: Boolean = true,
    val sponsorBlock: Boolean = true,
    val sponsor: Map<SponsorCategory, SegmentAction> = SponsorCategory.entries.associateWith { it.default },
)

private val Context.dataStore by preferencesDataStore(name = "player_settings")

class SettingsRepository(private val context: Context) : StrategySettings {
    private val theme = stringPreferencesKey("theme")
    private val amoled = booleanPreferencesKey("amoled")
    private val dynamicColors = booleanPreferencesKey("dynamic_colors")
    private val language = stringPreferencesKey("language")
    private val bypass = stringPreferencesKey("bypass")
    private val strategy = stringPreferencesKey("strategy")
    private val customArgs = stringPreferencesKey("custom_args")
    private val fakeSni = stringPreferencesKey("fake_sni")
    private val watchdog = booleanPreferencesKey("watchdog")
    private val qualityWifi = stringPreferencesKey("quality_wifi")
    private val qualityMobile = stringPreferencesKey("quality_mobile")
    private val background = booleanPreferencesKey("background")
    private val autoPip = booleanPreferencesKey("auto_pip")
    private val hideShorts = booleanPreferencesKey("hide_shorts")
    private val returnDislikes = booleanPreferencesKey("return_dislikes")
    private val adBlock = booleanPreferencesKey("ad_block")
    private val rememberSpeed = booleanPreferencesKey("remember_speed")
    private val speed = floatPreferencesKey("speed")
    private val gestures = booleanPreferencesKey("gestures")
    private val sponsorBlock = booleanPreferencesKey("sponsor_block")
    private val launches = intPreferencesKey("launches")
    private fun sponsorKey(c: SponsorCategory) = stringPreferencesKey("sb_" + c.key)

    private val data: Flow<Preferences> get() = context.dataStore.data

    val settings: Flow<PlayerSettings> = data.map { p ->
        PlayerSettings(
            theme = enumOf(p[theme], ThemeMode.System),
            amoled = p[amoled] ?: false,
            dynamicColors = p[dynamicColors] ?: false,
            language = enumOf(p[language], AppLanguage.Russian),
            bypass = enumOf(p[bypass], BypassMode.Auto),
            strategyId = p[strategy] ?: Strategies.default.id,
            watchdog = p[watchdog] ?: true,
            qualityWifi = enumOf(p[qualityWifi], QualityCap.P1080),
            qualityMobile = enumOf(p[qualityMobile], QualityCap.P480),
            background = p[background] ?: true,
            autoPip = p[autoPip] ?: true,
            hideShorts = p[hideShorts] ?: false,
            returnDislikes = p[returnDislikes] ?: true,
            adBlock = p[adBlock] ?: true,
            rememberSpeed = p[rememberSpeed] ?: false,
            speed = p[speed] ?: 1f,
            gestures = p[gestures] ?: true,
            sponsorBlock = p[sponsorBlock] ?: true,
            sponsor = SponsorCategory.entries.associateWith { enumOf(p[sponsorKey(it)], it.default) },
        )
    }

    // StrategySettings — для подбора и сторожа из :core-strategy.
    override suspend fun strategy(): Strategy {
        val p = data.first()
        val id = p[strategy] ?: Strategies.default.id
        return if (id == Strategies.CUSTOM_ID) StrategySettings.customStrategy(p[customArgs]) ?: Strategies.default
        else Strategies.byId(id) ?: Strategies.default
    }
    override suspend fun customArgs(): String = data.first()[customArgs].orEmpty()
    override suspend fun blockQuic(): Boolean = true
    override suspend fun fakeSni(): String = data.first()[fakeSni] ?: Strategies.FakeSniOptions.first()
    override suspend fun watchdog(): Boolean = data.first()[watchdog] ?: true

    suspend fun setTheme(v: ThemeMode) = edit { it[theme] = v.name }
    suspend fun setAmoled(v: Boolean) = edit { it[amoled] = v }
    suspend fun setDynamicColors(v: Boolean) = edit { it[dynamicColors] = v }
    suspend fun setLanguage(v: AppLanguage) = edit { it[language] = v.name }
    suspend fun setBypass(v: BypassMode) = edit { it[bypass] = v.name }
    suspend fun setStrategy(id: String) = edit { it[strategy] = id }
    suspend fun setCustomArgs(args: String) = edit {
        it[customArgs] = args
        it[strategy] = Strategies.CUSTOM_ID
    }
    suspend fun setWatchdog(v: Boolean) = edit { it[watchdog] = v }
    suspend fun setQualityWifi(v: QualityCap) = edit { it[qualityWifi] = v.name }
    suspend fun setQualityMobile(v: QualityCap) = edit { it[qualityMobile] = v.name }
    suspend fun setBackground(v: Boolean) = edit { it[background] = v }
    suspend fun setAutoPip(v: Boolean) = edit { it[autoPip] = v }
    suspend fun setHideShorts(v: Boolean) = edit { it[hideShorts] = v }
    suspend fun setReturnDislikes(v: Boolean) = edit { it[returnDislikes] = v }
    suspend fun setAdBlock(v: Boolean) = edit { it[adBlock] = v }
    suspend fun setRememberSpeed(v: Boolean) = edit { it[rememberSpeed] = v }
    suspend fun setSpeed(v: Float) = edit { it[speed] = v }
    suspend fun setGestures(v: Boolean) = edit { it[gestures] = v }
    suspend fun setSponsorBlock(v: Boolean) = edit { it[sponsorBlock] = v }
    suspend fun setSponsorAction(c: SponsorCategory, a: SegmentAction) = edit { it[sponsorKey(c)] = a.name }

    /** Номер запуска — первый запуск показывает подсказку про обход. */
    suspend fun countLaunch(): Int {
        var n = 0
        edit { n = (it[launches] ?: 0) + 1; it[launches] = n }
        return n
    }

    private suspend fun edit(block: (MutablePreferences) -> Unit) {
        context.dataStore.edit(block)
    }

    private inline fun <reified E : Enum<E>> enumOf(name: String?, default: E): E =
        name?.let { runCatching { enumValueOf<E>(it) }.getOrNull() } ?: default
}
