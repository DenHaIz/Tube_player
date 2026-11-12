package com.tube.player.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import kotlin.math.roundToInt
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.tube.byedpi.Strategies
import com.tube.player.BuildConfig
import com.tube.player.account.Account
import com.tube.player.data.BypassMode
import com.tube.player.data.PlayerSettings
import com.tube.player.data.QualityCap
import com.tube.player.data.SettingsRepository
import com.tube.player.data.ThemeMode
import com.tube.player.net.BypassStatus
import com.tube.player.player.PlaybackState
import com.tube.player.player.qualityLabel
import com.tube.player.sponsorblock.SegmentAction
import com.tube.player.sponsorblock.SponsorCategory
import com.tube.strategy.NetworkResults
import com.tube.strategy.TestProgress
import com.tube.ui.components.PillButton
import com.tube.ui.components.StatusDot
import com.tube.ui.components.bouncyClickable
import com.tube.ui.i18n.AppLanguage
import com.tube.ui.i18n.LocalLanguage
import com.tube.ui.i18n.tr
import com.tube.ui.theme.Shapes
import com.tube.ui.theme.TubeIcons
import com.tube.ui.theme.TubeTheme
import com.tube.ui.theme.dynamicColorsSupported

/**
 * Выбор одного варианта: скруглённые капсулы с переносом строк. В отличие от сегментов, подписи никогда
 * не обрезаются — ни при крупном шрифте, ни на узком экране; выбранная плавно заливается акцентом.
 */
@Composable
fun <T> Segmented(options: List<T>, selected: T, label: @Composable (T) -> String, onSelect: (T) -> Unit, modifier: Modifier = Modifier) {
    val colors = TubeTheme.colors
    androidx.compose.foundation.layout.FlowRow(
        modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        options.forEach { option ->
            val active = option == selected
            val bg by androidx.compose.animation.animateColorAsState(if (active) colors.accent else colors.chip, Anim.fade(), label = "opt")
            val fg by androidx.compose.animation.animateColorAsState(if (active) colors.onAccent else colors.textPrimary, Anim.fade(), label = "optText")
            Text(
                label(option),
                style = MaterialTheme.typography.labelLarge,
                color = fg,
                modifier = Modifier
                    .bouncyClickable(pressedScale = 0.94f) { onSelect(option) }
                    .background(bg, Shapes.Pill)
                    .padding(horizontal = 16.dp, vertical = 9.dp),
            )
        }
    }
}

/** Подпись над переключателем внутри карточки. */
@Composable
private fun Label(text: String) {
    Text(text, style = MaterialTheme.typography.bodyLarge, color = TubeTheme.colors.textPrimary, modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp))
}

@Composable
private fun Gap() = Spacer(Modifier.height(16.dp))

@Composable
fun qualityCapLabel(q: QualityCap) = if (q == QualityCap.Auto) tr("Макс.", "Max") else "${q.height}p"

/** Настройки плеера. */
@Composable
fun SettingsSheet(
    s: PlayerSettings,
    account: Account?,
    edit: (suspend SettingsRepository.() -> Unit) -> Unit,
    onAccount: () -> Unit,
    onBypass: () -> Unit,
    onUpdates: () -> Unit = {},
) {
    // Аккаунт и обход видны всегда; остальные разделы свёрнуты в «шторки» и раскрываются по нажатию.
    Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SheetTitle(tr("Настройки", "Settings"), "Tube Player ${BuildConfig.VERSION_NAME}")

        SettingsShutter(tr("Аккаунт", "Account"), collapsible = false) {
            // Профиль — по центру под названием раздела.
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                if (account != null) {
                    Avatar(account.avatar, 56.dp)
                    Spacer(Modifier.height(6.dp))
                    Text(account.name ?: tr("Аккаунт Google", "Google account"), style = MaterialTheme.typography.titleMedium, color = TubeTheme.colors.textPrimary, textAlign = TextAlign.Center)
                    account.handle?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = TubeTheme.colors.textSecondary, textAlign = TextAlign.Center) }
                } else {
                    Text(tr("Необязательно: рекомендации и подписки аккаунта", "Optional: your recommendations and subscriptions"), style = MaterialTheme.typography.bodyMedium, color = TubeTheme.colors.textSecondary, textAlign = TextAlign.Center)
                }
                Spacer(Modifier.height(8.dp))
                PillButton(if (account != null) tr("Управление", "Manage") else tr("Войти в Google", "Sign in with Google"), onClick = onAccount, icon = TubeIcons.Account, compact = true)
            }
        }

        SettingsShutter(tr("Обход блокировок", "Unblocking"), collapsible = false) {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.Start) {
                Segmented(BypassMode.entries, s.bypass, { if (it == BypassMode.Auto) tr("Встроенный", "Built-in") else tr("Выключен", "Off") }, { m -> edit { setBypass(m) } })
                Spacer(Modifier.height(8.dp))
                Text(
                    if (s.bypass == BypassMode.Auto) tr("Свой byedpi внутри плеера, VPN не нужен", "Own byedpi inside the player, no VPN needed")
                    else tr("Напрямую — если уже есть VPN или вы не в России", "Direct — if you already use a VPN or live outside Russia"),
                    style = MaterialTheme.typography.bodyMedium, color = TubeTheme.colors.textSecondary,
                )
                Spacer(Modifier.height(8.dp))
                PillButton(tr("Стратегии", "Strategies"), onClick = onBypass, icon = TubeIcons.Bolt, compact = true)
            }
        }

        SettingsShutter(tr("Вид", "Appearance")) {
            Label(tr("Тема", "Theme"))
            Segmented(ThemeMode.entries, s.theme, {
                when (it) {
                    ThemeMode.System -> tr("Система", "System")
                    ThemeMode.Light -> tr("Светлая", "Light")
                    ThemeMode.Dark -> tr("Тёмная", "Dark")
                }
            }, { t -> edit { setTheme(t) } })
            Gap()
            ToggleRow(tr("AMOLED", "AMOLED"), tr("Чёрный фон в тёмной теме", "Pure black in dark theme"), s.amoled) { v -> edit { setAmoled(v) } }
            if (dynamicColorsSupported) {
                Gap()
                ToggleRow(tr("Цвета обоев", "Wallpaper colors"), null, s.dynamicColors) { v -> edit { setDynamicColors(v) } }
            }
            Gap()
            Label(tr("Язык", "Language"))
            Segmented(AppLanguage.entries, s.language, { it.title }, { l -> edit { setLanguage(l) } })
        }

        SettingsShutter(tr("Воспроизведение", "Playback")) {
            val caps = listOf(QualityCap.Auto, QualityCap.P1080, QualityCap.P720, QualityCap.P480, QualityCap.P360)
            Label(tr("Качество по Wi-Fi", "Quality on Wi-Fi"))
            Segmented(caps, s.qualityWifi, { qualityCapLabel(it) }, { q -> edit { setQualityWifi(q) } })
            Gap()
            Label(tr("Качество в мобильной сети", "Quality on mobile data"))
            Segmented(caps, s.qualityMobile, { qualityCapLabel(it) }, { q -> edit { setQualityMobile(q) } })
            Gap()
            ToggleRow(tr("Играть в фоне", "Background play"), tr("Звук продолжается, когда приложение свёрнуто", "Keeps playing when the app is hidden"), s.background) { v -> edit { setBackground(v) } }
            Gap()
            ToggleRow(tr("Картинка в картинке", "Picture-in-picture"), tr("Видео в окошке при выходе на рабочий стол", "Small window when you go home"), s.autoPip) { v -> edit { setAutoPip(v) } }
            Gap()
            ToggleRow(tr("Запоминать скорость", "Remember speed"), null, s.rememberSpeed) { v -> edit { setRememberSpeed(v) } }
        }

        SettingsShutter(tr("Без рекламы", "No ads")) {
            ToggleRow(tr("Блокировщик рекламы", "Ad blocker"), tr("Без роликов, промо и рекламных запросов", "No ad videos, promos or ad requests"), s.adBlock) { v -> edit { setAdBlock(v) } }
            Gap()
            ToggleRow(tr("Скрывать Shorts", "Hide Shorts"), null, s.hideShorts) { v -> edit { setHideShorts(v) } }
            Gap()
            ToggleRow(tr("Дизлайки", "Dislikes"), "Return YouTube Dislike", s.returnDislikes) { v -> edit { setReturnDislikes(v) } }
        }

        SettingsShutter(tr("Обновления", "Updates")) {
            ToggleRow(tr("Проверять при запуске", "Check on launch"), tr("GitHub, при недоступности — зеркала", "GitHub, mirrors if it's unreachable"), s.autoUpdate) { v -> edit { setAutoUpdate(v) } }
            Gap()
            PillButton(tr("Проверить обновления", "Check for updates"), onClick = onUpdates, icon = TubeIcons.Bolt, compact = true)
        }

        SettingsShutter("SponsorBlock") {
            ToggleRow(tr("Пропускать вставки", "Skip segments"), tr("Спонсоры, заставки, просьбы подписаться", "Sponsors, intros, reminders"), s.sponsorBlock) { v -> edit { setSponsorBlock(v) } }
            if (s.sponsorBlock) {
                SponsorCategory.entries.forEach { category ->
                    Gap()
                    Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        StatusDot(category.composeColor())
                        Spacer(Modifier.size(8.dp))
                        Text(LocalLanguage.current.pick(category.ru, category.en), style = MaterialTheme.typography.titleMedium, color = TubeTheme.colors.textPrimary)
                    }
                    Segmented(SegmentAction.entries, s.sponsor[category] ?: category.default, {
                        when (it) {
                            SegmentAction.Skip -> tr("Пропуск", "Skip")
                            SegmentAction.Button -> tr("Кнопка", "Button")
                            SegmentAction.Show -> tr("Метка", "Mark")
                            SegmentAction.Off -> tr("Нет", "Off")
                        }
                    }, { a -> edit { setSponsorAction(category, a) } })
                }
            }
        }
    }
}

/** Обход: состояние, стратегия, подбор и рабочие стратегии этой сети. */
@Composable
fun BypassSheet(
    status: BypassStatus,
    progress: TestProgress,
    results: NetworkResults?,
    networkLabel: String?,
    watchdog: Boolean,
    onPick: () -> Unit,
    onCancel: () -> Unit,
    onChoose: (String) -> Unit,
    onWatchdog: (Boolean) -> Unit,
    onRestart: () -> Unit,
) {
    val colors = TubeTheme.colors
    val lang = LocalLanguage.current
    Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp), horizontalAlignment = Alignment.Start) {
        SheetTitle(tr("Обход блокировок", "Unblocking"), networkLabel)
        val (dot, title, subtitle) = when (status) {
            BypassStatus.Off -> Triple(colors.textTertiary, tr("Выключен", "Off"), tr("Соединения напрямую", "Direct connections"))
            BypassStatus.Starting -> Triple(colors.warning, tr("Запускается", "Starting"), null)
            is BypassStatus.Failed -> Triple(colors.danger, tr("Ошибка", "Error"), status.message)
            is BypassStatus.Running -> Triple(
                when {
                    status.appOk == false -> colors.danger
                    status.check?.ok == false -> colors.warning
                    status.check?.ok == true || status.appOk == true -> colors.success
                    else -> colors.accent
                },
                status.strategy.name,
                when {
                    status.appOk == false -> tr("Приложение не достучалось до YouTube: ", "The app couldn't reach YouTube: ") + status.appError.orEmpty()
                    status.check?.ok == false -> tr("Проверка не прошла — ищу замену", "Check failed — looking for another")
                    status.appOk == true -> tr("YouTube доступен", "YouTube reachable")
                    else -> tr("Проверяю…", "Checking…")
                },
            )
        }
        SettingsCard(tr("Сейчас", "Now")) {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.Start) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    StatusDot(dot)
                    Spacer(Modifier.size(8.dp))
                    Text(title, style = MaterialTheme.typography.titleLarge, color = colors.textPrimary)
                }
                if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = colors.textSecondary, maxLines = 3)
                if (status is BypassStatus.Running) {
                    val description = Strategies.byId(status.strategy.id)?.let { lang.pick(it.descriptionRu, it.descriptionEn) }
                    if (!description.isNullOrBlank()) Text(description, style = MaterialTheme.typography.bodyMedium, color = colors.textSecondary, modifier = Modifier.padding(top = 4.dp))
                }
                if (status is BypassStatus.Failed) {
                    Spacer(Modifier.height(8.dp))
                    PillButton(tr("Перезапустить", "Restart"), onClick = onRestart, compact = true)
                }
            }
        }
        SettingsCard(tr("Подбор", "Auto-pick")) {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.Start) {
                if (progress.running) {
                    Text(tr("Проверено ${progress.done} из ${progress.total}", "Tested ${progress.done} of ${progress.total}"), style = MaterialTheme.typography.titleMedium, color = colors.textPrimary)
                    Spacer(Modifier.height(8.dp))
                    LinearProgressIndicator(
                        progress = { if (progress.total > 0) progress.done.toFloat() / progress.total else 0f },
                        color = colors.accent, trackColor = colors.chip,
                        modifier = Modifier.fillMaxWidth().height(6.dp),
                    )
                    Spacer(Modifier.height(10.dp))
                    PillButton(tr("Стоп", "Stop"), onClick = onCancel, compact = true, color = colors.danger)
                } else {
                    Text(
                        tr("Проверю ~100 стратегий на узлах YouTube и включу лучшую", "Tests ~100 strategies against YouTube and enables the best"),
                        style = MaterialTheme.typography.bodyMedium, color = colors.textSecondary,
                    )
                    Spacer(Modifier.height(10.dp))
                    PillButton(tr("Подобрать", "Find best"), onClick = onPick, icon = TubeIcons.Bolt, filled = true)
                }
                Spacer(Modifier.height(12.dp))
                ToggleRow(tr("Сторож", "Watchdog"), tr("Проверяет обход и сам меняет стратегию", "Checks and switches strategy by itself"), watchdog, onChange = onWatchdog)
            }
        }
        val working = results?.working.orEmpty().take(10)
        if (working.isNotEmpty()) {
            SettingsCard(tr("Работают в этой сети", "Working on this network")) {
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    val current = (status as? BypassStatus.Running)?.strategy?.id
                    working.forEach { score ->
                        val active = score.strategy.id == current
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .bouncyClickable(pressedScale = 0.98f) { onChoose(score.strategy.id) }
                                .background(if (active) colors.accent.copy(alpha = 0.16f) else Color.Transparent, Shapes.Chip)
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            horizontalArrangement = Arrangement.Start,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(score.strategy.name, style = MaterialTheme.typography.titleMedium, color = if (active) colors.accent else colors.textPrimary)
                            Spacer(Modifier.size(10.dp))
                            Text("${score.avgMillis} " + tr("мс", "ms"), style = MaterialTheme.typography.bodyMedium, color = colors.textSecondary)
                        }
                    }
                }
            }
        }
    }
}

/**
 * Качество и скорость. Невысокий экран (полноэкранный режим, телефон боком) — без заголовка, оба ползунка
 * рядом в одну строку: лист маленький и не листается; иначе — друг под другом.
 */
@Composable
fun QualitySheet(state: PlaybackState, onQuality: (Int?) -> Unit, onSpeed: (Float) -> Unit) {
    val compact = androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp < 520
    Column(
        Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = if (compact) 8.dp else 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (!compact) SheetTitle(tr("Качество", "Quality"), if (state.currentHeight > 0) tr("Сейчас ", "Now ") + qualityLabel(state.currentHeight, state.currentFps) else null)
        if (compact) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                QualityBlock(state, onQuality, Modifier.weight(1f))
                SpeedBlock(state, onSpeed, Modifier.weight(1f))
            }
        } else {
            QualityBlock(state, onQuality, Modifier.fillMaxWidth())
            SpeedBlock(state, onSpeed, Modifier.fillMaxWidth())
        }
    }
}

/** Ползунок качества: слева «Авто», дальше качества по возрастанию; применяется, когда палец отпущен. */
@Composable
private fun QualityBlock(state: PlaybackState, onQuality: (Int?) -> Unit, modifier: Modifier) {
    val colors = TubeTheme.colors
    SliderCard(modifier) {
        if (state.qualities.isEmpty()) {
            SliderHeader(tr("Качество", "Quality"), if (state.currentHeight > 0) qualityLabel(state.currentHeight, state.currentFps) else "")
            Text(tr("Это видео в одном качестве", "This video has a single quality"), style = MaterialTheme.typography.bodyMedium, color = colors.textSecondary, modifier = Modifier.fillMaxWidth().padding(vertical = 14.dp))
            return@SliderCard
        }
        val stops = listOf<Int?>(null) + state.qualities.sortedBy { it }
        var pos by remember(state.selectedHeight, stops) { mutableFloatStateOf(stops.indexOf(state.selectedHeight).coerceAtLeast(0).toFloat()) }
        val current = stops[pos.roundToInt().coerceIn(0, stops.lastIndex)]
        val auto = tr("Авто", "Auto") + if (state.currentHeight > 0) " · " + qualityLabel(state.currentHeight, state.currentFps) else ""
        SliderHeader(tr("Качество", "Quality"), current?.let { qualityLabel(it) } ?: auto)
        TubeSlider(
            value = pos,
            onChange = { pos = it },
            range = 0f..stops.lastIndex.toFloat(),
            steps = (stops.size - 2).coerceAtLeast(0),
            onDone = { onQuality(stops[pos.roundToInt().coerceIn(0, stops.lastIndex)]) },
        )
        SliderEnds(tr("Авто", "Auto"), qualityLabel(stops.last() ?: 0))
    }
}

/** Ползунок скорости 0,25×…3× с шагом 0,05; скорость меняется сразу, пока тянете. */
@Composable
private fun SpeedBlock(state: PlaybackState, onSpeed: (Float) -> Unit, modifier: Modifier) {
    val colors = TubeTheme.colors
    SliderCard(modifier) {
        var speed by remember(state.speed) { mutableFloatStateOf(state.speed) }
        SliderHeader(tr("Скорость", "Speed"), if (speed == 1f) tr("Обычная", "Normal") else "%.2f×".format(java.util.Locale.ROOT, speed).replace(".00×", "×").replace("0×", "×"))
        TubeSlider(
            value = speed,
            onChange = {
                speed = (Math.round(it * 20f) / 20f)
                onSpeed(speed)
            },
            range = 0.25f..3f,
            steps = 0,
            onDone = {},
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("0.25×", style = MaterialTheme.typography.bodyMedium, color = colors.textSecondary)
            Spacer(Modifier.weight(1f))
            androidx.compose.animation.AnimatedVisibility(speed != 1f, enter = androidx.compose.animation.fadeIn(Anim.fade()), exit = androidx.compose.animation.fadeOut(Anim.out())) {
                Text(
                    tr("Сбросить", "Reset"),
                    style = MaterialTheme.typography.labelLarge,
                    color = colors.accent,
                    modifier = Modifier.bouncyClickable {
                        speed = 1f
                        onSpeed(1f)
                    }.padding(horizontal = 8.dp),
                )
            }
            Spacer(Modifier.weight(1f))
            Text("3×", style = MaterialTheme.typography.bodyMedium, color = colors.textSecondary)
        }
    }
}

/** Карточка ползунка — без отдельного заголовка: название и значение уже в первой строке. */
@Composable
private fun SliderCard(modifier: Modifier, content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    Column(
        modifier
            .background(TubeTheme.colors.card, androidx.compose.foundation.shape.RoundedCornerShape(16.dp))
            .padding(horizontal = 14.dp, vertical = 12.dp),
        content = content,
    )
}

@Composable
private fun SliderHeader(title: String, value: String) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = MaterialTheme.typography.bodyLarge, color = TubeTheme.colors.textPrimary, modifier = Modifier.weight(1f))
        androidx.compose.animation.AnimatedContent(
            value,
            transitionSpec = { androidx.compose.animation.fadeIn(Anim.fade()) togetherWith androidx.compose.animation.fadeOut(Anim.out()) },
            label = "sliderValue",
        ) { v -> Text(v, style = MaterialTheme.typography.labelLarge, color = TubeTheme.colors.accent) }
    }
}

@Composable
private fun SliderEnds(start: String, end: String) {
    Row(Modifier.fillMaxWidth()) {
        Text(start, style = MaterialTheme.typography.bodyMedium, color = TubeTheme.colors.textSecondary)
        Spacer(Modifier.weight(1f))
        Text(end, style = MaterialTheme.typography.bodyMedium, color = TubeTheme.colors.textSecondary)
    }
}

/** Ползунок в цветах приложения. */
@Composable
private fun TubeSlider(value: Float, onChange: (Float) -> Unit, range: ClosedFloatingPointRange<Float>, steps: Int, onDone: () -> Unit) {
    val colors = TubeTheme.colors
    androidx.compose.material3.Slider(
        value = value,
        onValueChange = onChange,
        valueRange = range,
        steps = steps,
        onValueChangeFinished = onDone,
        colors = androidx.compose.material3.SliderDefaults.colors(
            thumbColor = colors.accent,
            activeTrackColor = colors.accent,
            inactiveTrackColor = colors.chip,
            activeTickColor = colors.onAccent.copy(alpha = 0.5f),
            inactiveTickColor = colors.textTertiary.copy(alpha = 0.5f),
        ),
    )
}

@Composable
private fun QualityChip(text: String, active: Boolean, onClick: () -> Unit) {
    val colors = TubeTheme.colors
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = if (active) colors.onAccent else colors.textPrimary,
        modifier = Modifier
            .bouncyClickable(pressedScale = 0.92f, onClick = onClick)
            .background(if (active) colors.accent else colors.chip, Shapes.Pill)
            .padding(horizontal = 16.dp, vertical = 10.dp),
    )
}

/** Аккаунт Google: вход необязателен. */
@Composable
fun AccountSheet(
    account: Account?,
    importing: Boolean,
    imported: Int?,
    onSignIn: () -> Unit,
    onImport: () -> Unit,
    onSignOut: () -> Unit,
) {
    val colors = TubeTheme.colors
    // Название, профиль и кнопки — по центру.
    Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 16.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        SheetTitle(tr("Аккаунт", "Account"))
        if (account == null) {
            Text(
                tr(
                    "Вход не обязателен — без него всё работает. С аккаунтом появятся ваши рекомендации, лента подписок и перенос подписок в плеер.",
                    "Signing in is optional — everything works without it. With an account you get your recommendations, subscriptions feed and subscription import.",
                ),
                style = MaterialTheme.typography.bodyLarge, color = colors.textSecondary, textAlign = TextAlign.Center,
            )
            Text(
                tr("Вход идёт через страницу Google; пароль приложение не видит. Cookie хранятся зашифрованными на телефоне.", "You sign in on Google's page; the app never sees your password. Cookies stay encrypted on the phone."),
                style = MaterialTheme.typography.bodyMedium, color = colors.textSecondary, textAlign = TextAlign.Center,
            )
            PillButton(tr("Войти в Google", "Sign in with Google"), onClick = onSignIn, icon = TubeIcons.Account, filled = true)
        } else {
            Avatar(account.avatar, 72.dp)
            Text(account.name ?: tr("Аккаунт Google", "Google account"), style = MaterialTheme.typography.titleLarge, color = colors.textPrimary, textAlign = TextAlign.Center)
            account.handle?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = colors.textSecondary, textAlign = TextAlign.Center) }
            PillButton(
                if (importing) tr("Переношу…", "Importing…") else tr("Перенести подписки", "Import subscriptions"),
                onClick = onImport, icon = TubeIcons.Subscriptions, enabled = !importing,
            )
            imported?.let { Text(tr("Перенесено каналов: $it", "Channels imported: $it"), style = MaterialTheme.typography.bodyMedium, color = colors.textSecondary) }
            PillButton(tr("Выйти", "Sign out"), onClick = onSignOut, icon = TubeIcons.Logout, color = colors.danger)
        }
    }
}


/** Заголовок листа по центру, подзаголовок — под ним. */
@Composable
fun SheetTitle(title: String, subtitle: String? = null) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(title, style = MaterialTheme.typography.headlineSmall, color = TubeTheme.colors.textPrimary, textAlign = TextAlign.Center)
        if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = TubeTheme.colors.textSecondary, textAlign = TextAlign.Center)
    }
}

/**
 * Раздел настроек в «шторке»: изначально свёрнут — видно только название по центру и стрелку;
 * по нажатию содержимое плавно раскрывается, стрелка поворачивается. [collapsible] = false — раздел открыт всегда.
 */
@Composable
private fun SettingsShutter(title: String, collapsible: Boolean = true, content: @Composable () -> Unit) {
    var expanded by remember { androidx.compose.runtime.mutableStateOf(!collapsible) }
    val colors = TubeTheme.colors
    val rotation by androidx.compose.animation.core.animateFloatAsState(if (expanded) 180f else 0f, Anim.move(), label = "shutter")
    val titleColor by androidx.compose.animation.animateColorAsState(if (expanded) colors.accent else colors.textPrimary, Anim.fade(), label = "shutterTitle")
    Column(
        Modifier
            .fillMaxWidth()
            .background(colors.card, androidx.compose.foundation.shape.RoundedCornerShape(16.dp)),
    ) {
        androidx.compose.foundation.layout.Box(
            Modifier
                .fillMaxWidth()
                .then(if (collapsible) Modifier.bouncyClickable(pressedScale = 0.99f) { expanded = !expanded } else Modifier)
                .padding(horizontal = 16.dp, vertical = 16.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                color = titleColor,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 28.dp),
            )
            if (collapsible) {
                androidx.compose.material3.Icon(
                    TubeIcons.ChevronDown, null, tint = colors.textSecondary,
                    modifier = Modifier.align(Alignment.CenterEnd).size(20.dp).graphicsLayer { rotationZ = rotation },
                )
            }
        }
        androidx.compose.animation.AnimatedVisibility(
            expanded,
            enter = androidx.compose.animation.expandVertically(Anim.move()) + androidx.compose.animation.fadeIn(Anim.fade()),
            exit = androidx.compose.animation.shrinkVertically(Anim.move()) + androidx.compose.animation.fadeOut(Anim.out()),
        ) {
            Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 16.dp)) { content() }
        }
    }
}

/** Группа настроек: заголовок цвета акцента и строки под ним. */
@Composable
private fun SettingsCard(title: String, content: @Composable () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(TubeTheme.colors.card, androidx.compose.foundation.shape.RoundedCornerShape(16.dp))
            .padding(horizontal = 16.dp, vertical = 14.dp),
    ) {
        Text(title, style = MaterialTheme.typography.labelLarge, color = TubeTheme.colors.accent, modifier = Modifier.padding(bottom = 10.dp))
        content()
    }
}

/** Строка с переключателем: название и пояснение слева, переключатель справа. */
@Composable
private fun ToggleRow(title: String, status: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    val colors = TubeTheme.colors
    Row(
        Modifier.fillMaxWidth().bouncyClickable(pressedScale = 0.99f) { onChange(!checked) },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = colors.textPrimary)
            if (status != null) Text(status, style = MaterialTheme.typography.bodyMedium, color = colors.textSecondary)
        }
        Spacer(Modifier.size(12.dp))
        androidx.compose.material3.Switch(
            checked = checked,
            onCheckedChange = onChange,
            colors = androidx.compose.material3.SwitchDefaults.colors(
                checkedTrackColor = colors.accent,
                checkedThumbColor = colors.onAccent,
                uncheckedTrackColor = colors.chip,
                uncheckedBorderColor = colors.textTertiary,
                uncheckedThumbColor = colors.textSecondary,
            ),
        )
    }
}
