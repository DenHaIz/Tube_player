package com.tube.dpi.data

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.tube.byedpi.Strategies
import com.tube.byedpi.Strategy
import com.tube.strategy.StrategySettings
import com.tube.ui.i18n.AppLanguage
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

enum class ThemeMode { System, Light, Dark }

/** Всё, что нужно VPN-сервису для запуска, — одним снимком. */
data class TunnelSettings(
    val strategy: Strategy,
    val targets: Set<String>,
    val blockQuic: Boolean,
    val fakeSni: String,
)

private val Context.dataStore by preferencesDataStore(name = "settings")

class SettingsRepository(private val context: Context) : StrategySettings {
    private val themeKey = stringPreferencesKey("theme")
    private val languageKey = stringPreferencesKey("language")
    private val dynamicColorsKey = booleanPreferencesKey("dynamic_colors")
    private val strategyKey = stringPreferencesKey("strategy")
    private val customArgsKey = stringPreferencesKey("custom_args")
    private val targetsKey = stringSetPreferencesKey("targets")
    private val blockQuicKey = booleanPreferencesKey("block_quic")
    private val stopDelayKey = intPreferencesKey("stop_delay_minutes")
    private val lastClientKey = stringPreferencesKey("last_client")
    private val fakeSniKey = stringPreferencesKey("fake_sni")
    private val watchdogKey = booleanPreferencesKey("watchdog")
    private val autoUpdateKey = booleanPreferencesKey("check_updates")
    private val lastUpdateCheckKey = androidx.datastore.preferences.core.longPreferencesKey("last_update_check")

    private val data: Flow<Preferences> get() = context.dataStore.data

    val themeMode: Flow<ThemeMode> = data.map { prefs ->
        prefs[themeKey]?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() } ?: ThemeMode.System
    }

    val language: Flow<AppLanguage> = data.map { prefs ->
        prefs[languageKey]?.let { runCatching { AppLanguage.valueOf(it) }.getOrNull() } ?: AppLanguage.Russian
    }

    /** Цвета Material You (из обоев) вместо фирменных. По умолчанию — фирменные. */
    val dynamicColors: Flow<Boolean> = data.map { it[dynamicColorsKey] ?: false }

    val strategyId: Flow<String> = data.map { it[strategyKey] ?: Strategies.default.id }

    /** Строка аргументов своей стратегии; пустая — ещё не задана. */
    val customArgs: Flow<String> = data.map { it[customArgsKey].orEmpty() }

    val strategy: Flow<Strategy> = data.map { prefs -> resolveStrategy(prefs[strategyKey], prefs[customArgsKey]) }

    /** Пакеты, чей трафик идёт через обход. null — пользователь ещё не выбирал (подставляются найденные клиенты). */
    val targets: Flow<Set<String>?> = data.map { it[targetsKey] }

    /** QUIC (UDP/443) запрещён — YouTube откатывается на TCP, где работает desync. */
    val blockQuic: Flow<Boolean> = data.map { it[blockQuicKey] ?: true }

    /** Через сколько минут после ухода из YouTube выключать обход; 0 — не выключать. */
    val stopDelayMinutes: Flow<Int> = data.map { it[stopDelayKey] ?: 5 }

    /** Имя сайта в фейковых ClientHello стратегий ({sni}). */
    val fakeSni: Flow<String> = data.map { it[fakeSniKey] ?: Strategies.FakeSniOptions.first() }

    /** Клиент, который открывает кнопка и ярлык «YouTube». */
    val lastClient: Flow<String?> = data.map { it[lastClientKey] }

    suspend fun tunnelSettings(defaultTargets: () -> Set<String>): TunnelSettings {
        val prefs = data.first()
        return TunnelSettings(
            strategy = resolveStrategy(prefs[strategyKey], prefs[customArgsKey]),
            targets = prefs[targetsKey] ?: defaultTargets(),
            blockQuic = prefs[blockQuicKey] ?: true,
            fakeSni = prefs[fakeSniKey] ?: Strategies.FakeSniOptions.first(),
        )
    }

    private fun resolveStrategy(id: String?, customArgs: String?): Strategy = when {
        id == Strategies.CUSTOM_ID && !customArgs.isNullOrBlank() -> StrategySettings.customStrategy(customArgs)!!
        else -> id?.let { Strategies.byId(it) } ?: Strategies.default
    }

    override suspend fun strategy(): Strategy = strategy.first()
    override suspend fun customArgs(): String = customArgs.first()
    override suspend fun blockQuic(): Boolean = blockQuic.first()
    override suspend fun fakeSni(): String = fakeSni.first()
    override suspend fun watchdog(): Boolean = watchdog.first()

    suspend fun setThemeMode(mode: ThemeMode) = edit { it[themeKey] = mode.name }

    suspend fun setLanguage(language: AppLanguage) = edit { it[languageKey] = language.name }

    suspend fun setDynamicColors(enabled: Boolean) = edit { it[dynamicColorsKey] = enabled }

    suspend fun setStrategy(id: String) = edit { it[strategyKey] = id }

    suspend fun setCustomArgs(args: String) = edit {
        it[customArgsKey] = args
        it[strategyKey] = Strategies.CUSTOM_ID
    }

    suspend fun setTargets(packages: Set<String>) = edit { it[targetsKey] = packages }

    /** Добавить или убрать приложение одной транзакцией: быстрые нажатия подряд не теряются. */
    suspend fun updateTargets(defaults: Set<String>, transform: (Set<String>) -> Set<String>) =
        edit { it[targetsKey] = transform(it[targetsKey] ?: defaults) }

    suspend fun setBlockQuic(enabled: Boolean) = edit { it[blockQuicKey] = enabled }

    suspend fun setStopDelayMinutes(minutes: Int) = edit { it[stopDelayKey] = minutes }

    suspend fun setLastClient(packageName: String) = edit { it[lastClientKey] = packageName }

    suspend fun setFakeSni(sni: String) = edit { it[fakeSniKey] = sni }

    /** Сторож: проверять текущую стратегию и переключаться при сбое или смене сети. */
    val watchdog: Flow<Boolean> = data.map { it[watchdogKey] ?: true }

    suspend fun setWatchdog(enabled: Boolean) = edit { it[watchdogKey] = enabled }

    /** Проверять новые версии на GitHub. */
    val checkUpdates: Flow<Boolean> = data.map { it[autoUpdateKey] ?: true }

    suspend fun setCheckUpdates(enabled: Boolean) = edit { it[autoUpdateKey] = enabled }

    val lastUpdateCheck: Flow<Long> = data.map { it[lastUpdateCheckKey] ?: 0L }

    suspend fun setLastUpdateCheck(time: Long) = edit { it[lastUpdateCheckKey] = time }

    private suspend fun edit(block: (androidx.datastore.preferences.core.MutablePreferences) -> Unit) {
        context.dataStore.edit(block)
    }
}
