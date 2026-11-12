package com.tube.dpi.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tube.dpi.R
import com.tube.update.UpdateState
import com.tube.dpi.vpn.FailReason
import com.tube.dpi.vpn.TunnelStatus
import com.tube.ui.components.AmbientBackground
import com.tube.ui.components.FitText
import com.tube.ui.components.GlassSheet
import com.tube.ui.components.PillButton
import com.tube.ui.components.bouncyClickable
import com.tube.ui.glass.FrostedCard
import com.tube.ui.glass.LocalHazeState
import com.tube.ui.glass.liquidGlass
import com.tube.ui.i18n.LocalLanguage
import com.tube.ui.i18n.tr
import com.tube.ui.theme.Motion
import com.tube.ui.theme.Shapes
import com.tube.ui.theme.TubeIcons
import com.tube.ui.theme.TubeTheme
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

enum class Sheet { None, Strategy, Apps, Settings, Autostart, Log }

/** Действия, которые требуют активности (системные диалоги и экраны настроек). */
class ShellActions(
    /** Включить обход: при необходимости — системные диалоги согласия на VPN и уведомлений. */
    val start: () -> Unit,
    val openAccessibility: () -> Unit,
    val openClient: (String) -> Unit,
    val openAutostartOem: () -> Unit,
    val openBattery: () -> Unit,
    /** Открыть скачанный APK в системном установщике (сначала — разрешение «Установка приложений»). */
    val installUpdate: (File) -> Unit = {},
)

@Composable
fun TubeAppShell(vm: MainViewModel, actions: ShellActions) {
    val hazeState = rememberHazeState()
    var sheet by rememberSaveable { mutableStateOf(Sheet.None) }
    val status by vm.status.collectAsStateWithLifecycle()
    CompositionLocalProvider(LocalHazeState provides hazeState) {
        Box(Modifier.fillMaxSize()) {
            AmbientBackground(
                colors = emptyList(),
                seed = if (status is TunnelStatus.Running) 7 else 3,
                modifier = Modifier
                    .fillMaxSize()
                    .hazeSource(hazeState, zIndex = 0f),
            )
            HomeScreen(vm, actions, onOpen = { sheet = it })
            val close = { sheet = Sheet.None }
            GlassSheet(sheet == Sheet.Strategy, close) { StrategySheet(vm, onShowLog = { sheet = Sheet.Log }) }
            GlassSheet(sheet == Sheet.Apps, close) { AppsSheet(vm) }
            GlassSheet(sheet == Sheet.Settings, close) {
                SettingsSheet(vm, actions, onAutostart = { sheet = Sheet.Autostart }, onShowLog = { sheet = Sheet.Log })
            }
            GlassSheet(sheet == Sheet.Autostart, close) { AutostartSheet(vm, actions) }
            GlassSheet(sheet == Sheet.Log, close) { LogSheet(vm) }
        }
    }
}

@Composable
private fun HomeScreen(vm: MainViewModel, actions: ShellActions, onOpen: (Sheet) -> Unit) {
    val update by vm.update.collectAsStateWithLifecycle()

    // Экран не прокручивается: всё помещается, кнопка подстраивается под свободное место.
    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            // Системные панели и вырез камеры: в горизонтальном виде они сбоку.
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(horizontal = 20.dp, vertical = 12.dp),
        contentAlignment = Alignment.TopCenter,
    ) {
        // Горизонтальный вид (телефон боком, планшет): кнопка слева, карточки справа.
        val wide = maxWidth > maxHeight && maxWidth >= 560.dp
        Column(
            Modifier
                .fillMaxHeight()
                .widthIn(max = if (wide) 1040.dp else 560.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Header(onOpen)
            UpdateBanner(update, vm, actions)
            if (wide) {
                Row(
                    Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(24.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    PowerArea(vm, actions, onOpen, Modifier.weight(1f).fillMaxHeight())
                    Column(
                        Modifier
                            .weight(1f)
                            .fillMaxHeight(),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Clients(vm, actions)
                        InfoCards(vm, onOpen)
                    }
                }
            } else {
                PowerArea(vm, actions, onOpen, Modifier.weight(1f).fillMaxWidth())
                Clients(vm, actions)
                InfoCards(vm, onOpen)
            }
        }
    }
}

@Composable
private fun Header(onOpen: (Sheet) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Image(painterResource(R.drawable.tube_mark), contentDescription = null, modifier = Modifier.size(44.dp))
        Spacer(Modifier.size(10.dp))
        Text("Tube", style = MaterialTheme.typography.headlineSmall, color = TubeTheme.colors.textPrimary, modifier = Modifier.weight(1f))
        GlassIconButton(TubeIcons.Sliders, tr("Настройки", "Settings")) { onOpen(Sheet.Settings) }
    }
}

/** Кнопка обхода и состояние под ней; кнопка занимает всё свободное место по высоте. */
@Composable
private fun PowerArea(vm: MainViewModel, actions: ShellActions, onOpen: (Sheet) -> Unit, modifier: Modifier) {
    val status by vm.status.collectAsStateWithLifecycle()
    val orb = when (status) {
        is TunnelStatus.Running -> OrbState.On
        TunnelStatus.Starting -> OrbState.Starting
        is TunnelStatus.Failed -> OrbState.Failed
        TunnelStatus.Stopped -> OrbState.Off
    }
    BoxWithConstraints(modifier, contentAlignment = Alignment.Center) {
        // Под кнопкой — две-три строки состояния (~90dp).
        val orbSize = (maxHeight - 96.dp).coerceIn(112.dp, 236.dp)
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            PowerOrb(
                orb,
                tr("Включить или выключить обход", "Toggle unblocking"),
                onClick = { if (status is TunnelStatus.Running || status is TunnelStatus.Starting) vm.toggleTunnel() else actions.start() },
                diameter = orbSize,
            )
            StatusText(vm, status, actions, onOpen)
        }
    }
}

@Composable
private fun StatusText(vm: MainViewModel, status: TunnelStatus, actions: ShellActions, onOpen: (Sheet) -> Unit) {
    val colors = TubeTheme.colors
    val lang = LocalLanguage.current
    val traffic by vm.traffic.collectAsStateWithLifecycle()
    val watchdog by vm.watchdog.collectAsStateWithLifecycle()
    AnimatedContent(status, transitionSpec = { fadeIn(Motion.snappy()) togetherWith fadeOut(Motion.snappy()) }, label = "status") { st ->
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            val title = when (st) {
                is TunnelStatus.Running -> tr("Обход включён", "Unblocking is on")
                TunnelStatus.Starting -> tr("Запуск…", "Starting…")
                is TunnelStatus.Failed -> lang.failMessage(st.reason)
                TunnelStatus.Stopped -> tr("Обход выключен", "Unblocking is off")
            }
            FitText(title, style = MaterialTheme.typography.titleLarge, color = if (st is TunnelStatus.Failed) colors.danger else colors.textPrimary)
            val subtitle = when (st) {
                is TunnelStatus.Running -> "↓ ${lang.bytes(traffic.rxBytes)} · ${lang.rate(traffic.rxRate)}"
                TunnelStatus.Stopped -> tr("Нажмите, чтобы включить", "Tap to turn on")
                is TunnelStatus.Failed -> st.details?.lineSequence()?.firstOrNull()?.take(80)
                TunnelStatus.Starting -> null
            }
            if (subtitle != null) {
                Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = colors.textSecondary, textAlign = TextAlign.Center, maxLines = 2, modifier = Modifier.padding(top = 2.dp))
            }
            val check = watchdog
            if (st is TunnelStatus.Running && check != null) {
                Text(
                    if (check.ok) tr("✓ YouTube доступен · ${lang.ago(check.checkedAt)}", "✓ YouTube reachable · ${lang.ago(check.checkedAt)}")
                    else tr("✕ YouTube не отвечает — ищу замену", "✕ YouTube not responding — finding a fix"),
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (check.ok) colors.success else colors.warning,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
            if (st is TunnelStatus.Failed) {
                Spacer(Modifier.height(10.dp))
                when (st.reason) {
                    FailReason.NoConsent, FailReason.Revoked, FailReason.TunnelFailed ->
                        PillButton(tr("Включить снова", "Turn on again"), onClick = actions.start, compact = true)
                    FailReason.NoApps -> PillButton(tr("Выбрать приложения", "Choose apps"), onClick = { onOpen(Sheet.Apps) }, compact = true)
                    FailReason.ProxyFailed -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        PillButton(tr("Стратегия", "Strategy"), onClick = { onOpen(Sheet.Strategy) }, compact = true)
                        PillButton(tr("Журнал", "Log"), onClick = { onOpen(Sheet.Log) }, compact = true)
                    }
                }
            }
        }
    }
}

@Composable
private fun Clients(vm: MainViewModel, actions: ShellActions) {
    val clients by vm.clients.collectAsStateWithLifecycle()
    AnimatedVisibility(clients.isNotEmpty()) {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                clients.forEach { client ->
                    key(client.packageName) {
                        ClientChip(client.packageName, client.label) { actions.openClient(client.packageName) }
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}

@Composable
private fun InfoCards(vm: MainViewModel, onOpen: (Sheet) -> Unit) {
    val lang = LocalLanguage.current
    val strategy by vm.strategy.collectAsStateWithLifecycle()
    val targets by vm.targets.collectAsStateWithLifecycle()
    val installed by vm.installed.collectAsStateWithLifecycle()
    val autostart by vm.autostartEnabled.collectAsStateWithLifecycle()
    val stopDelay by vm.stopDelay.collectAsStateWithLifecycle()
    val results by vm.results.collectAsStateWithLifecycle()
    val test by vm.test.collectAsStateWithLifecycle()

    val score = results?.scores?.get(strategy.id)
    val working = results?.working?.size ?: 0
    val tested = results?.testedAt?.let { it != 0L } == true
    InfoCard(
        icon = TubeIcons.Bolt,
        title = tr("Стратегия", "Strategy"),
        value = strategy.name + (score?.takeIf { it.works }?.let { " · ${it.avgMillis} ${lang.pick("мс", "ms")}" } ?: ""),
        hint = when {
            test.running -> tr("Подбор: ${test.done} из ${test.total}", "Testing: ${test.done} of ${test.total}")
            !tested -> tr("Нажмите, чтобы подобрать", "Tap to auto-pick")
            else -> tr("Рабочих в этой сети: $working", "Working on this network: $working")
        },
        warning = tested && working == 0,
    ) { onOpen(Sheet.Strategy) }
    Spacer(Modifier.height(10.dp))

    val selectedNames = remember(installed, targets) { installed.orEmpty().filter { it.packageName in targets }.map { it.label } }
    InfoCard(
        icon = TubeIcons.Apps,
        title = tr("Приложения", "Apps"),
        value = when {
            installed == null -> "…"
            selectedNames.isEmpty() -> tr("Не выбраны", "None selected")
            else -> lang.plural(selectedNames.size, "${selectedNames.size} приложение", "${selectedNames.size} приложения", "${selectedNames.size} приложений", "${selectedNames.size} app", "${selectedNames.size} apps")
        },
        hint = selectedNames.take(3).joinToString(", ").ifEmpty { tr("Остальные идут напрямую", "Others go direct") },
        warning = installed != null && selectedNames.isEmpty(),
    ) { onOpen(Sheet.Apps) }
    Spacer(Modifier.height(10.dp))

    InfoCard(
        icon = TubeIcons.Eye,
        title = tr("Запуск с YouTube", "Start with YouTube"),
        value = if (autostart) tr("Включён", "On") else tr("Выключен", "Off"),
        hint = when {
            !autostart -> tr("Нажмите, чтобы настроить", "Tap to set up")
            stopDelay == 0 -> tr("Не выключается сам", "Stays on")
            else -> tr("Выключение через ${lang.stopDelay(stopDelay)}", "Turns off after ${lang.stopDelay(stopDelay)}")
        },
        warning = !autostart,
    ) { onOpen(Sheet.Autostart) }
}

/** Плашка новой версии: что нового и кнопка обновления с прогрессом скачивания. */
@Composable
private fun UpdateBanner(state: UpdateState, vm: MainViewModel, actions: ShellActions) {
    val colors = TubeTheme.colors
    val release = when (state) {
        is UpdateState.Available -> state.release
        is UpdateState.Downloading -> state.release
        is UpdateState.Ready -> state.release
        else -> null
    }
    AnimatedVisibility(release != null) {
        release ?: return@AnimatedVisibility
        FrostedCard(
            Modifier
                .fillMaxWidth()
                .padding(top = 14.dp),
        ) {
            Column(Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(tr("Доступна версия ${release.version}", "Version ${release.version} is available"), style = MaterialTheme.typography.titleMedium, color = colors.textPrimary)
                val notes = release.notes.lineSequence().map { it.trim().removePrefix("**RU**").removePrefix("—").trim() }.firstOrNull { it.isNotEmpty() && !it.startsWith("**EN**") }
                if (notes != null) {
                    Text(notes, style = MaterialTheme.typography.bodyMedium, color = colors.textSecondary, textAlign = TextAlign.Center, maxLines = 3)
                }
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    when (state) {
                        is UpdateState.Downloading -> PillButton(tr("Скачивание ${(state.progress * 100).toInt()}%", "Downloading ${(state.progress * 100).toInt()}%"), onClick = {}, enabled = false, compact = true)
                        is UpdateState.Ready -> PillButton(tr("Установить", "Install"), onClick = { actions.installUpdate(state.apk) }, filled = true, compact = true)
                        else -> {
                            PillButton(tr("Обновить", "Update"), onClick = { vm.downloadUpdate(release) }, filled = true, compact = true)
                            PillButton(tr("Позже", "Later"), onClick = vm::dismissUpdate, compact = true)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = TubeTheme.colors.textSecondary,
        textAlign = TextAlign.Center,
        modifier = modifier
            .fillMaxWidth()
            .padding(bottom = 6.dp),
    )
}

@Composable
fun GlassIconButton(icon: ImageVector, description: String, onClick: () -> Unit) {
    Box(
        Modifier
            .size(48.dp)
            .bouncyClickable(glowColor = TubeTheme.colors.accent, glowShape = Shapes.Pill, onClick = onClick)
            .liquidGlass(Shapes.Pill),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = description, tint = TubeTheme.colors.textPrimary, modifier = Modifier.size(22.dp))
    }
}

/** Карточка главного экрана: всё по центру — подпись со значком, значение крупно, одна строка пояснения. */
@Composable
private fun InfoCard(
    icon: ImageVector,
    title: String,
    value: String,
    hint: String,
    warning: Boolean = false,
    onClick: () -> Unit,
) {
    val colors = TubeTheme.colors
    val iconColor = if (warning) colors.warning else colors.accent
    FrostedCard(
        Modifier
            .fillMaxWidth()
            .bouncyClickable(pressedScale = 0.97f, onClick = onClick),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, contentDescription = null, tint = iconColor, modifier = Modifier.size(18.dp))
                Spacer(Modifier.size(6.dp))
                Text(title, style = MaterialTheme.typography.labelLarge, color = colors.textSecondary)
            }
            FitText(value, style = MaterialTheme.typography.titleLarge, color = colors.textPrimary)
            Text(hint, style = MaterialTheme.typography.bodyMedium, color = if (warning) colors.warning else colors.textTertiary, textAlign = TextAlign.Center, maxLines = 1)
        }
    }
}

@Composable
private fun ClientChip(packageName: String, label: String, onClick: () -> Unit) {
    val colors = TubeTheme.colors
    Row(
        Modifier
            .bouncyClickable(glowColor = colors.accent, glowShape = Shapes.Pill, onClick = onClick)
            .liquidGlass(Shapes.Pill)
            .padding(start = 6.dp, end = 16.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AppIcon(packageName, Modifier.size(32.dp))
        Spacer(Modifier.size(8.dp))
        Text(label, style = MaterialTheme.typography.labelLarge, color = colors.textPrimary, maxLines = 1)
        Spacer(Modifier.size(6.dp))
        Icon(TubeIcons.Play, contentDescription = null, tint = colors.accent, modifier = Modifier.size(16.dp))
    }
}

private val IconCache = android.util.LruCache<String, ImageBitmap>(64)

/** Значок установленного приложения; грузится в фоне, пока — пустое место. */
@Composable
fun AppIcon(packageName: String, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    // Кэш: при прокрутке списка приложений значки не декодируются заново.
    val icon by produceState(IconCache.get(packageName), packageName) {
        if (value != null) return@produceState
        value = withContext(Dispatchers.IO) {
            runCatching { context.packageManager.getApplicationIcon(packageName).toBitmap(96, 96).asImageBitmap() }.getOrNull()
                ?.also { IconCache.put(packageName, it) }
        }
    }
    Box(modifier.clip(Shapes.Chip)) {
        icon?.let { Image(it, contentDescription = null, modifier = Modifier.fillMaxSize()) }
    }
}
