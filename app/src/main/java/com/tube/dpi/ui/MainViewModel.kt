package com.tube.dpi.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.tube.byedpi.Strategies
import com.tube.byedpi.Strategy
import com.tube.byedpi.StrategyArgs
import com.tube.dpi.TubeApp
import com.tube.dpi.apps.InstalledApp
import com.tube.dpi.apps.TargetApps
import com.tube.dpi.autostart.AutoStartService
import com.tube.strategy.NetworkId
import com.tube.strategy.NetworkResults
import com.tube.dpi.data.ThemeMode
import com.tube.strategy.TestProgress
import com.tube.update.Release
import com.tube.update.UpdateState
import com.tube.dpi.vpn.TunnelState
import com.tube.dpi.vpn.VpnController
import com.tube.ui.i18n.AppLanguage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val container = (app as TubeApp).container
    private val settings = container.settings
    private val context get() = getApplication<Application>()

    val status = TunnelState.status
    val traffic = TunnelState.traffic
    val log = TunnelState.log

    val strategy: StateFlow<Strategy> = settings.strategy.stateIn(viewModelScope, SharingStarted.Eagerly, Strategies.default)
    val strategyId: StateFlow<String> = settings.strategyId.stateIn(viewModelScope, SharingStarted.Eagerly, Strategies.default.id)
    val customArgs: StateFlow<String> = settings.customArgs.stateIn(viewModelScope, SharingStarted.Eagerly, "")
    val blockQuic: StateFlow<Boolean> = settings.blockQuic.stateIn(viewModelScope, SharingStarted.Eagerly, true)
    val fakeSni: StateFlow<String> = settings.fakeSni.stateIn(viewModelScope, SharingStarted.Eagerly, Strategies.FakeSniOptions.first())
    val stopDelay: StateFlow<Int> = settings.stopDelayMinutes.stateIn(viewModelScope, SharingStarted.Eagerly, 5)
    val themeMode: StateFlow<ThemeMode> = settings.themeMode.stateIn(viewModelScope, SharingStarted.Eagerly, ThemeMode.System)
    val dynamicColors: StateFlow<Boolean> = settings.dynamicColors.stateIn(viewModelScope, SharingStarted.Eagerly, false)
    val language: StateFlow<AppLanguage> = settings.language.stateIn(viewModelScope, SharingStarted.Eagerly, AppLanguage.Russian)
    val watchdogEnabled: StateFlow<Boolean> = settings.watchdog.stateIn(viewModelScope, SharingStarted.Eagerly, true)
    val checkUpdates: StateFlow<Boolean> = settings.checkUpdates.stateIn(viewModelScope, SharingStarted.Eagerly, true)
    val watchdog = TunnelState.watchdog

    /** Текущая сеть и сохранённые для неё результаты подбора. */
    val network: StateFlow<NetworkId?> = container.network.current
    val results: StateFlow<NetworkResults?> = combine(container.network.current, container.results.all) { id, all -> id?.let { all[it.key] } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val test: StateFlow<TestProgress> = container.autoPick.progress
    val testLog: StateFlow<List<String>> = container.autoPick.log
    val update: StateFlow<UpdateState> = container.updates.state

    private val _installed = MutableStateFlow<List<InstalledApp>?>(null)
    /** Приложения с доступом в интернет; null — список ещё загружается. */
    val installed: StateFlow<List<InstalledApp>?> = _installed.asStateFlow()

    private val _defaults = MutableStateFlow<Set<String>>(emptySet())

    /** Выбранные пакеты (по умолчанию — найденные клиенты YouTube). */
    val targets: StateFlow<Set<String>> = combine(settings.targets, _defaults) { saved, defaults -> saved ?: defaults }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())

    private val _clients = MutableStateFlow<List<InstalledApp>>(emptyList())
    /** Клиенты YouTube среди выбранных, которые можно открыть кнопкой. */
    val clients: StateFlow<List<InstalledApp>> = _clients.asStateFlow()

    val lastClient: StateFlow<String?> = settings.lastClient.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val _autostartEnabled = MutableStateFlow(false)
    val autostartEnabled: StateFlow<Boolean> = _autostartEnabled.asStateFlow()

    private val _testingId = MutableStateFlow<String?>(null)
    /** Стратегия, которую сейчас проверяют по одной (кнопка «Проверить»). */
    val testingId: StateFlow<String?> = _testingId.asStateFlow()

    init {
        viewModelScope.launch {
            targets.map { it }.collect { refreshClients(it) }
        }
        viewModelScope.launch { container.updates.checkIfDue() }
    }

    /** При каждом возвращении на экран: службу могли включить в настройках, приложения — поставить или удалить. */
    private var packagesSeq = 0

    fun refresh() {
        _autostartEnabled.value = AutoStartService.isEnabled(context)
        container.network.refresh()
        // Перебор всех приложений дорогой: повторяем, только если с прошлого раза что-то ставили или удаляли.
        val pm = context.packageManager
        if (_installed.value != null && pm.getChangedPackages(packagesSeq) == null) return
        packagesSeq = pm.getChangedPackages(0)?.sequenceNumber ?: packagesSeq
        viewModelScope.launch {
            val (defaults, apps) = withContext(Dispatchers.IO) { TargetApps.defaultTargets(context) to TargetApps.installed(context) }
            _defaults.value = defaults
            _installed.value = apps
            refreshClients(targets.value)
        }
    }

    private suspend fun refreshClients(targets: Set<String>) {
        _clients.value = withContext(Dispatchers.IO) { TargetApps.launchableClients(context, targets) }
    }

    fun toggleTunnel() = VpnController.toggle(context)

    fun startTunnel() = VpnController.start(context)

    fun selectStrategy(id: String) = viewModelScope.launch {
        container.chooseStrategy(id)
        VpnController.restartIfRunning(context)
    }

    /** Проверить одну стратегию (результат сохраняется для текущей сети). */
    fun testStrategy(strategy: Strategy) {
        if (_testingId.value != null || test.value.running) return
        _testingId.value = strategy.id
        viewModelScope.launch {
            try {
                container.autoPick.testOne(strategy)
            } finally {
                _testingId.value = null
            }
        }
    }

    fun setWatchdog(enabled: Boolean) = viewModelScope.launch { settings.setWatchdog(enabled) }

    fun setCheckUpdates(enabled: Boolean) = viewModelScope.launch { settings.setCheckUpdates(enabled) }

    fun checkForUpdate() = viewModelScope.launch { container.updates.check() }

    fun downloadUpdate(release: Release) = viewModelScope.launch { container.updates.download(release) }

    fun dismissUpdate() = container.updates.dismiss()

    /** null — строка принята; иначе — опция, на которой споткнулся разбор. */
    fun saveCustomArgs(args: String): String? {
        val parsed = StrategyArgs.parse(args)
        if (parsed is StrategyArgs.Result.Error) return parsed.message
        viewModelScope.launch {
            settings.setCustomArgs(args.trim())
            VpnController.restartIfRunning(context)
        }
        return null
    }

    private var restartJob: Job? = null

    fun setTarget(packageName: String, selected: Boolean) = viewModelScope.launch {
        settings.updateTargets(_defaults.value) { if (selected) it + packageName else it - packageName }
        // Несколько галочек подряд — один перезапуск туннеля.
        restartJob?.cancel()
        restartJob = viewModelScope.launch {
            delay(RESTART_DEBOUNCE_MS)
            VpnController.restartIfRunning(context)
        }
    }

    fun setBlockQuic(enabled: Boolean) = viewModelScope.launch {
        settings.setBlockQuic(enabled)
        VpnController.restartIfRunning(context)
    }

    fun setFakeSni(sni: String) = viewModelScope.launch {
        settings.setFakeSni(sni)
        VpnController.restartIfRunning(context)
    }

    fun setStopDelay(minutes: Int) = viewModelScope.launch { settings.setStopDelayMinutes(minutes) }

    fun setThemeMode(mode: ThemeMode) = viewModelScope.launch { settings.setThemeMode(mode) }

    fun setDynamicColors(enabled: Boolean) = viewModelScope.launch { settings.setDynamicColors(enabled) }

    fun setLanguage(language: AppLanguage) = viewModelScope.launch { settings.setLanguage(language) }

    fun rememberClient(packageName: String) = viewModelScope.launch { settings.setLastClient(packageName) }

    /** Перебор всех стратегий на реальных узлах YouTube; результаты сохраняются для текущей сети. */
    fun runTest() {
        container.autoPick.start()
    }

    fun cancelTest() = container.autoPick.cancel()

    private companion object {
        const val RESTART_DEBOUNCE_MS = 800L
    }
}
