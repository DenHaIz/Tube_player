package com.tube.dpi.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tube.byedpi.Strategies
import com.tube.dpi.BuildConfig
import com.tube.dpi.data.ThemeMode
import com.tube.dpi.system.OemPower
import com.tube.update.UpdateState
import com.tube.dpi.vpn.TunnelStatus
import com.tube.dpi.vpn.VpnController
import com.tube.ui.components.LiquidSegmented
import com.tube.ui.components.PermissionRow
import com.tube.ui.components.PillButton
import com.tube.ui.components.SettingsCard
import com.tube.ui.components.SheetTitle
import com.tube.ui.components.ToggleRow
import com.tube.ui.i18n.AppLanguage
import com.tube.ui.i18n.LocalLanguage
import com.tube.ui.i18n.tr
import com.tube.ui.theme.Shapes
import com.tube.ui.theme.TubeIcons
import com.tube.ui.theme.TubeTheme
import com.tube.ui.theme.dynamicColorsSupported

@Composable
fun ColumnScope.SettingsSheet(vm: MainViewModel, actions: ShellActions, onAutostart: () -> Unit, onShowLog: () -> Unit) {
    val lang = LocalLanguage.current
    val context = LocalContext.current
    val blockQuic by vm.blockQuic.collectAsStateWithLifecycle()
    val fakeSni by vm.fakeSni.collectAsStateWithLifecycle()
    val watchdog by vm.watchdogEnabled.collectAsStateWithLifecycle()
    val autostart by vm.autostartEnabled.collectAsStateWithLifecycle()
    val stopDelay by vm.stopDelay.collectAsStateWithLifecycle()
    val theme by vm.themeMode.collectAsStateWithLifecycle()
    val dynamic by vm.dynamicColors.collectAsStateWithLifecycle()
    val language by vm.language.collectAsStateWithLifecycle()
    val checkUpdates by vm.checkUpdates.collectAsStateWithLifecycle()
    val update by vm.update.collectAsStateWithLifecycle()

    SheetTitle(tr("Настройки", "Settings"))
    Spacer(Modifier.height(12.dp))
    Column(
        Modifier
            .weight(1f, fill = false)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        SettingsCard(tr("Обход", "Unblocking")) {
            ToggleRow(
                title = tr("Сторож", "Watchdog"),
                status = tr("Меняет сломавшуюся стратегию", "Replaces a broken strategy"),
                checked = watchdog,
                onChange = vm::setWatchdog,
            )
            Spacer(Modifier.height(14.dp))
            ToggleRow(
                title = tr("Блокировать QUIC", "Block QUIC"),
                status = if (blockQuic) null else tr("Нужно только для QUIC FAKE", "Only for QUIC FAKE"),
                checked = blockQuic,
                onChange = vm::setBlockQuic,
            )
            Spacer(Modifier.height(14.dp))
            Hint(tr("Сайт в фейковых пакетах", "Site in fake packets"))
            Spacer(Modifier.height(8.dp))
            Segmented(
                Strategies.FakeSniOptions.map { if (it.startsWith("fonts.")) "fonts" else it.removeSuffix(".com") },
                Strategies.FakeSniOptions.indexOf(fakeSni).coerceAtLeast(0),
            ) { vm.setFakeSni(Strategies.FakeSniOptions[it]) }
        }

        SettingsCard(tr("Запуск с YouTube", "Start with YouTube")) {
            PermissionRow(
                title = tr("Служба автозапуска", "Autostart service"),
                status = if (autostart) tr("Включена", "On") else tr("Выключена", "Off"),
                ok = autostart,
                action = tr("Настроить", "Set up"),
                onAction = onAutostart,
            )
            Spacer(Modifier.height(14.dp))
            Hint(tr("Выключать после ухода через", "Turn off after leaving in"))
            Spacer(Modifier.height(8.dp))
            Segmented(StopDelayOptions.map { lang.stopDelay(it) }, StopDelayOptions.indexOf(stopDelay).coerceAtLeast(0)) { vm.setStopDelay(StopDelayOptions[it]) }
        }

        SettingsCard(tr("Фоновая работа", "Background")) {
            var refresh by remember { mutableIntStateOf(0) }
            val unrestricted = remember(refresh) { OemPower.isBatteryUnrestricted(context) }
            PermissionRow(
                title = tr("Батарея без ограничений", "Unrestricted battery"),
                status = if (unrestricted) tr("Готово", "Done") else tr("Чтобы обход не выключался", "Keeps unblocking alive"),
                ok = unrestricted,
                action = tr("Разрешить", "Allow"),
                onAction = {
                    actions.openBattery()
                    refresh++
                },
            )
            if (OemPower.needsAutostart) {
                Spacer(Modifier.height(12.dp))
                val allowed = OemPower.autostartAllowed(context)
                PermissionRow(
                    title = tr("Автозапуск прошивки", "OEM autostart"),
                    status = tr("Нужен на этом телефоне", "Needed on this phone"),
                    ok = allowed == true,
                    optional = allowed == null,
                    action = tr("Открыть", "Open"),
                    onAction = actions.openAutostartOem,
                )
            }
        }

        SettingsCard(tr("Оформление", "Appearance")) {
            Segmented(
                listOf(tr("Системная", "System"), tr("Светлая", "Light"), tr("Тёмная", "Dark")),
                ThemeMode.entries.indexOf(theme),
            ) { vm.setThemeMode(ThemeMode.entries[it]) }
            if (dynamicColorsSupported) {
                Spacer(Modifier.height(14.dp))
                ToggleRow(
                    title = tr("Цвета обоев", "Wallpaper colors"),
                    checked = dynamic,
                    onChange = vm::setDynamicColors,
                )
            }
            Spacer(Modifier.height(14.dp))
            Segmented(AppLanguage.entries.map { it.title }, AppLanguage.entries.indexOf(language)) { vm.setLanguage(AppLanguage.entries[it]) }
        }

        SettingsCard(tr("Обновления", "Updates")) {
            ToggleRow(
                title = tr("Проверять на GitHub", "Check on GitHub"),
                checked = checkUpdates,
                onChange = vm::setCheckUpdates,
            )
            Spacer(Modifier.height(12.dp))
            val status = when (val s = update) {
                UpdateState.Checking -> tr("Проверяю…", "Checking…")
                UpdateState.UpToDate -> tr("Установлена последняя версия", "You have the latest version")
                is UpdateState.Available -> tr("Доступна версия ${s.release.version}", "Version ${s.release.version} is available")
                is UpdateState.Downloading -> tr("Скачивание ${(s.progress * 100).toInt()}%", "Downloading ${(s.progress * 100).toInt()}%")
                is UpdateState.Ready -> tr("Готово к установке", "Ready to install")
                is UpdateState.Failed -> tr("Не удалось: ${s.message}", "Failed: ${s.message}")
                UpdateState.Idle -> null
            }
            if (status != null) {
                Hint(status)
                Spacer(Modifier.height(8.dp))
            }
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                when (val s = update) {
                    is UpdateState.Available -> PillButton(tr("Обновить", "Update"), onClick = { vm.downloadUpdate(s.release) }, filled = true, compact = true)
                    is UpdateState.Ready -> PillButton(tr("Установить", "Install"), onClick = { actions.installUpdate(s.apk) }, filled = true, compact = true)
                    else -> PillButton(
                        tr("Проверить сейчас", "Check now"),
                        onClick = vm::checkForUpdate,
                        enabled = update !is UpdateState.Checking && update !is UpdateState.Downloading,
                        compact = true,
                    )
                }
            }
        }

        SettingsCard(tr("О приложении", "About")) {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Tube ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.titleMedium, color = TubeTheme.colors.textPrimary)
                Hint(tr("Обход на телефоне, без серверов. Лицензия GPL-3.0", "On-device bypass, no servers. Licensed GPL-3.0"))
                Spacer(Modifier.height(10.dp))
                PillButton(tr("Журнал", "Log"), onClick = onShowLog, icon = TubeIcons.Terminal, compact = true)
            }
        }
        Spacer(Modifier.height(16.dp))
    }
}

/** Пояснение по центру обычным, а не мелким шрифтом. */
@Composable
private fun Hint(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = TubeTheme.colors.textSecondary,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
fun Segmented(options: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    val colors = TubeTheme.colors
    LiquidSegmented(
        count = options.size,
        selectedIndex = selected,
        onSelect = onSelect,
        pillColor = colors.accent.copy(alpha = 0.22f),
        modifier = Modifier
            .fillMaxWidth()
            .height(44.dp)
            .clip(Shapes.Pill)
            .background(colors.chip),
    ) { index, isSelected ->
        Text(
            options[index],
            style = MaterialTheme.typography.labelLarge,
            color = if (isSelected) colors.textPrimary else colors.textSecondary,
            maxLines = 1,
        )
    }
}

/**
 * Включение автозапуска. Явно объясняем, зачем служба спецвозможностей и что она не делает, —
 * этого требуют и здравый смысл, и правила Google для таких служб.
 */
@Composable
fun ColumnScope.AutostartSheet(vm: MainViewModel, actions: ShellActions) {
    val autostart by vm.autostartEnabled.collectAsStateWithLifecycle()
    val status by vm.status.collectAsStateWithLifecycle()
    val context = LocalContext.current

    SheetTitle(tr("Запуск с YouTube", "Start with YouTube"), tr("Через службу спецвозможностей", "Via an accessibility service"))
    Column(
        Modifier
            .weight(1f, fill = false)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Disclosure(
            tr("Что делает", "What it does"),
            tr("Видит только, какое приложение открыто, и включает обход для YouTube", "Sees only which app is open and turns on unblocking for YouTube"),
        )
        Disclosure(
            tr("Чего не делает", "What it doesn't do"),
            tr("Не читает экран и нажатия, ничего не отправляет", "Doesn't read the screen or taps, sends nothing"),
        )
        Disclosure(
            tr("Как включить", "How to turn on"),
            tr(
                "Настройки → Установленные приложения → Tube.\nЕсли переключатель серый: Сведения о приложении → ⋮ → Разрешить ограниченные настройки.",
                "Settings → Installed apps → Tube.\nIf the switch is grey: App info → ⋮ → Allow restricted settings.",
            ),
        )
        PermissionRow(
            title = tr("Служба автозапуска", "Autostart service"),
            status = if (autostart) tr("Включена", "On") else tr("Выключена", "Off"),
            ok = autostart,
            action = tr("Открыть", "Open"),
            onAction = actions.openAccessibility,
        )
        val consent = VpnController.hasConsent(context)
        PermissionRow(
            title = tr("Разрешение VPN", "VPN permission"),
            status = if (consent) tr("Выдано", "Granted") else tr("Включите обход один раз", "Turn it on once"),
            ok = consent || status is TunnelStatus.Running,
            action = tr("Выдать", "Grant"),
            onAction = actions.start,
        )
        if (!autostart) {
            PillButton(
                tr("Сведения о приложении", "App info"),
                onClick = {
                    runCatching {
                        context.startActivity(
                            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:${context.packageName}".toUri())
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        )
                    }
                },
                compact = true,
            )
        }
        Spacer(Modifier.height(4.dp))
    }
}

@Composable
private fun Disclosure(title: String, text: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
        Text(title, style = MaterialTheme.typography.titleMedium, color = TubeTheme.colors.textPrimary, textAlign = TextAlign.Center)
        Text(text, style = MaterialTheme.typography.bodyMedium, color = TubeTheme.colors.textSecondary, textAlign = TextAlign.Center)
    }
}

@Composable
fun ColumnScope.LogSheet(vm: MainViewModel) {
    val colors = TubeTheme.colors
    val context = LocalContext.current
    val log by vm.log.collectAsStateWithLifecycle()
    val status by vm.status.collectAsStateWithLifecycle()
    val strategy by vm.strategy.collectAsStateWithLifecycle()
    val testLog by vm.testLog.collectAsStateWithLifecycle()
    val lines = buildList {
        add("Tube ${BuildConfig.VERSION_NAME}, Android ${android.os.Build.VERSION.RELEASE}, ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}")
        add("strategy: ${strategy.name} ${strategy.args}")
        add("status: $status")
        addAll(log)
        if (testLog.isNotEmpty()) {
            add("")
            add("--- auto-pick ---")
            addAll(testLog)
        }
    }
    SheetTitle(tr("Журнал", "Log"))
    Spacer(Modifier.height(12.dp))
    Box(
        Modifier
            .weight(1f, fill = false)
            .padding(horizontal = 16.dp)
            .fillMaxWidth()
            .clip(Shapes.Chip)
            .background(colors.chip)
            .verticalScroll(rememberScrollState())
            .horizontalScroll(rememberScrollState())
            .padding(12.dp),
    ) {
        Text(
            lines.joinToString("\n"),
            style = MaterialTheme.typography.labelMedium.copy(fontFamily = FontFamily.Monospace),
            color = colors.textSecondary,
        )
    }
    FlowRow(
        Modifier
            .fillMaxWidth()
            .padding(16.dp),
        horizontalArrangement = Arrangement.Center,
    ) {
        PillButton(
            tr("Скопировать", "Copy"),
            onClick = {
                context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("tube", lines.joinToString("\n")))
            },
            compact = true,
        )
    }
}
