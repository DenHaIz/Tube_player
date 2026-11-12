package com.tube.dpi.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tube.byedpi.ProbeGroup
import com.tube.byedpi.ProbeKind
import com.tube.byedpi.Strategies
import com.tube.byedpi.Strategy
import com.tube.byedpi.StrategyScore
import com.tube.strategy.NetworkId
import com.tube.strategy.NetworkKind
import com.tube.strategy.NetworkResults
import com.tube.strategy.TestProgress
import com.tube.ui.components.PillButton
import com.tube.ui.components.SheetTitle
import com.tube.ui.components.bouncyClickable
import com.tube.ui.i18n.AppLanguage
import com.tube.ui.i18n.LocalLanguage
import com.tube.ui.i18n.tr
import com.tube.ui.theme.Motion
import com.tube.ui.theme.Shapes
import com.tube.ui.theme.TubeIcons
import com.tube.ui.theme.TubeTheme

private enum class Group { Current, Working, Partial, Failed, Untested }

@Composable
fun ColumnScope.StrategySheet(vm: MainViewModel, onShowLog: () -> Unit) {
    val lang = LocalLanguage.current
    val selectedId by vm.strategyId.collectAsStateWithLifecycle()
    val customArgs by vm.customArgs.collectAsStateWithLifecycle()
    val results by vm.results.collectAsStateWithLifecycle()
    val network by vm.network.collectAsStateWithLifecycle()
    val test by vm.test.collectAsStateWithLifecycle()
    val testingId by vm.testingId.collectAsStateWithLifecycle()
    val fakeSni by vm.fakeSni.collectAsStateWithLifecycle()
    var showFailed by rememberSaveable { mutableStateOf(false) }
    var expanded by rememberSaveable { mutableStateOf<String?>(null) }

    val scores = results?.scores.orEmpty()
    // Группировка и сортировка — только когда изменились результаты или выбор, а не на каждый кадр/прогресс.
    val grouped = remember(scores, selectedId, customArgs) {
        val custom = customArgs.takeIf { it.isNotBlank() }?.let { Strategy(Strategies.CUSTOM_ID, "CUSTOM", it, "Своя стратегия", "Custom strategy") }
        // Набор ByeByeDPI показываем, только если он проверялся; внутри групп — сначала надёжные и быстрые.
        val candidates = Strategies.builtIn + listOfNotNull(custom) + Strategies.extended.filter { it.id in scores || it.id == selectedId }
        fun groupOf(s: Strategy): Group {
            if (s.id == selectedId) return Group.Current
            val score = scores[s.id] ?: return Group.Untested
            return when {
                score.works -> Group.Working
                score.passed > 0 -> Group.Partial
                else -> Group.Failed
            }
        }
        val passed = candidates.associate { it.id to (scores[it.id]?.passed ?: -1) }
        val speed = candidates.associate { it.id to (scores[it.id]?.avgMillis ?: Long.MAX_VALUE) }
        val order = compareByDescending<Strategy> { passed[it.id] }.thenBy { speed[it.id] }
        candidates.groupBy(::groupOf).mapValues { (_, list) -> list.sortedWith(order) }
    }

    val subtitle = network?.let { id ->
        lang.networkLabel(id) + (results?.testedAt?.takeIf { it > 0 }?.let { " · " + lang.ago(it) } ?: "")
    }
    SheetTitle(tr("Стратегия", "Strategy"), subtitle)
    Spacer(Modifier.height(8.dp))

    // Один экземпляр, пока не изменилось ничего, что видно в строках: прогресс подбора не перерисовывает весь список.
    val running = test.running
    val rowArgs = remember(scores, selectedId, expanded, testingId, running, fakeSni) {
        RowArgs(scores, selectedId, expanded, testingId, running, fakeSni, vm) { id -> expanded = if (expanded == id) null else id }
    }
    LazyColumn(
        Modifier
            .weight(1f, fill = false)
            .padding(horizontal = 12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        item(key = "summary") { Summary(vm, results, test, selectedId) }

        group(Group.Current, grouped, lang.pick("Сейчас", "Current"), rowArgs)
        group(Group.Working, grouped, lang.pick("Работают", "Working"), rowArgs)
        group(Group.Partial, grouped, lang.pick("Частично", "Partly"), rowArgs)
        val failed = grouped[Group.Failed].orEmpty()
        if (failed.isNotEmpty()) {
            item(key = "failed-toggle") {
                Text(
                    (if (showFailed) lang.pick("Скрыть неработающие", "Hide failed") else lang.pick("Не работают", "Failed")) + " · ${failed.size}",
                    style = MaterialTheme.typography.labelLarge,
                    color = TubeTheme.colors.textSecondary,
                    modifier = Modifier
                        .padding(top = 8.dp)
                        .bouncyClickable { showFailed = !showFailed }
                        .padding(8.dp),
                )
            }
            if (showFailed) items(failed, key = { it.id }) { StrategyRow(it, rowArgs) }
        }
        group(Group.Untested, grouped, lang.pick("Не проверены", "Not tested"), rowArgs)

        item(key = "custom-editor") {
            Spacer(Modifier.height(8.dp))
            CustomStrategyEditor(customArgs, selectedId == Strategies.CUSTOM_ID, vm::saveCustomArgs)
        }
        item(key = "log") {
            Text(
                lang.pick("Журнал", "Log"),
                style = MaterialTheme.typography.labelLarge,
                color = TubeTheme.colors.accent,
                modifier = Modifier
                    .padding(8.dp)
                    .bouncyClickable(onClick = onShowLog)
                    .padding(8.dp),
            )
            Spacer(Modifier.height(12.dp))
        }
    }
}

private class RowArgs(
    val scores: Map<String, StrategyScore>,
    val selectedId: String,
    val expanded: String?,
    val testingId: String?,
    val busy: Boolean,
    val fakeSni: String,
    val vm: MainViewModel,
    val onExpand: (String) -> Unit,
)

private fun LazyListScope.group(group: Group, grouped: Map<Group, List<Strategy>>, title: String, args: RowArgs) {
    val list = grouped[group].orEmpty()
    if (list.isEmpty()) return
    item(key = "header-$group") { SectionLabel("$title · ${list.size}", Modifier.padding(top = 10.dp)) }
    items(list, key = { it.id }) { StrategyRow(it, args) }
}

/** Сводка сверху: ход подбора или его итог и лучшая стратегия для этой сети. */
@Composable
private fun Summary(vm: MainViewModel, results: NetworkResults?, test: TestProgress, selectedId: String) {
    val colors = TubeTheme.colors
    Column(
        Modifier
            .fillMaxWidth()
            .clip(Shapes.Card)
            .background(colors.chip)
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        when {
            test.running -> {
                val progress by animateFloatAsState(if (test.total == 0) 0f else test.done / test.total.toFloat(), Motion.soft(), label = "progress")
                Text(
                    tr("Проверено ${test.done} из ${test.total}", "Tested ${test.done} of ${test.total}"),
                    style = MaterialTheme.typography.titleMedium,
                    color = colors.textPrimary,
                )
                Spacer(Modifier.height(10.dp))
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(8.dp)
                        .clip(Shapes.Pill)
                        .background(colors.chip),
                ) {
                    Box(
                        Modifier
                            .fillMaxHeight()
                            .fillMaxWidth(progress)
                            .clip(Shapes.Pill)
                            .background(colors.accent),
                    )
                }
                Spacer(Modifier.height(10.dp))
                PillButton(tr("Остановить", "Stop"), onClick = vm::cancelTest, compact = true)
            }
            results == null || results.testedAt == 0L -> {
                Text(tr("Подбор ещё не запускался", "Not tested yet"), style = MaterialTheme.typography.titleMedium, color = colors.textPrimary)
                Text(
                    tr("Около минуты, ~80 вариантов", "About a minute, ~80 options"),
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.textSecondary,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(12.dp))
                PillButton(tr("Подобрать", "Auto-pick"), onClick = vm::runTest, icon = TubeIcons.Bolt, filled = true)
            }
            else -> {
                val working = results.working.size
                Text(
                    if (working > 0) tr("Работают: $working", "Working: $working") else tr("Рабочих нет", "Nothing works"),
                    style = MaterialTheme.typography.titleLarge,
                    color = if (working > 0) colors.success else colors.danger,
                )
                results.baseline?.let { baseline ->
                    Spacer(Modifier.height(8.dp))
                    Text(tr("Без обхода", "Without bypass"), style = MaterialTheme.typography.bodyMedium, color = colors.textSecondary)
                    Spacer(Modifier.height(4.dp))
                    ProbeChips(baseline)
                }
                if (results.systemDns == false) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        tr("DNS провайдера обходим через DoH", "ISP DNS is bypassed via DoH"),
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.textSecondary,
                        textAlign = TextAlign.Center,
                    )
                }
                if (working == 0) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        tr("Смените сайт для фейков в настройках и проверьте снова", "Change the fake site in settings and retest"),
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.warning,
                        textAlign = TextAlign.Center,
                    )
                }
                val best = results.working.firstOrNull()
                Spacer(Modifier.height(12.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (best != null && best.strategy.id != selectedId) {
                        PillButton(
                            tr("Включить ${best.strategy.name}", "Use ${best.strategy.name}"),
                            onClick = { vm.selectStrategy(best.strategy.id) },
                            filled = true,
                            compact = true,
                        )
                    }
                    PillButton(tr("Проверить снова", "Retest"), onClick = vm::runTest, icon = TubeIcons.Bolt, compact = true)
                }
            }
        }
    }
}

@Composable
private fun StrategyRow(strategy: Strategy, args: RowArgs) {
    val colors = TubeTheme.colors
    val lang = LocalLanguage.current
    val selected = strategy.id == args.selectedId
    val score = args.scores[strategy.id]
    val isExpanded = args.expanded == strategy.id
    val testing = args.testingId == strategy.id
    Column(
        Modifier
            .fillMaxWidth()
            .bouncyClickable(pressedScale = 0.98f) { args.onExpand(strategy.id) }
            .clip(Shapes.Chip)
            .background(if (selected) colors.accent.copy(alpha = 0.14f) else colors.chip.copy(alpha = colors.chip.alpha * 0.6f))
            .then(if (selected) Modifier.border(1.5.dp, colors.accent.copy(alpha = 0.6f), Shapes.Chip) else Modifier)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (selected) {
                Icon(TubeIcons.Check, contentDescription = null, tint = colors.accent, modifier = Modifier.size(18.dp))
                Spacer(Modifier.size(6.dp))
            }
            Text(strategy.name, style = MaterialTheme.typography.titleMedium, color = colors.textPrimary, maxLines = 1)
            if (score != null) {
                Spacer(Modifier.size(8.dp))
                ScoreBadge(score)
            }
        }
        Text(
            lang.pick(strategy.descriptionRu, strategy.descriptionEn),
            style = MaterialTheme.typography.bodyMedium,
            color = colors.textSecondary,
            textAlign = TextAlign.Center,
        )
        if (score != null && !score.works && score.results.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            ProbeChips(score)
        }
        AnimatedVisibility(isExpanded) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                Spacer(Modifier.height(8.dp))
                Text(
                    strategy.args.replace(Strategies.SNI, args.fakeSni),
                    style = MaterialTheme.typography.labelMedium.copy(fontFamily = FontFamily.Monospace),
                    color = colors.textTertiary,
                    textAlign = TextAlign.Center,
                )
                score?.results?.filter { !it.ok }?.forEach { r ->
                    val kind = if (r.probe.kind == ProbeKind.ChromeHello) " (Chrome)" else ""
                    Text(
                        "${r.probe.host}$kind: ${r.error ?: "${r.bytes} B"}",
                        style = MaterialTheme.typography.labelMedium,
                        color = colors.danger.copy(alpha = 0.85f),
                        textAlign = TextAlign.Center,
                    )
                }
                Spacer(Modifier.height(10.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (!selected) {
                        PillButton(lang.pick("Включить", "Use"), onClick = { args.vm.selectStrategy(strategy.id) }, filled = true, compact = true)
                    }
                    PillButton(
                        if (testing) lang.pick("Проверяю…", "Testing…") else lang.pick("Проверить", "Test"),
                        onClick = { args.vm.testStrategy(strategy) },
                        enabled = !testing && !args.busy && args.testingId == null,
                        compact = true,
                    )
                }
            }
        }
    }
}

/** Итог по каждому узлу: сайт, превью, видео. Переносится на новую строку, если не помещается. */
@Composable
fun ProbeChips(score: StrategyScore, modifier: Modifier = Modifier) {
    val colors = TubeTheme.colors
    val lang = LocalLanguage.current
    FlowRow(
        modifier,
        horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        // Узел считается доступным, только если прошли обе проверки: полный TLS и ClientHello как у YouTube.
        score.results.groupBy { it.probe.group }.toSortedMap().forEach { (group, list) ->
            val ok = list.all { it.ok }
            val label = when (group) {
                ProbeGroup.Video -> lang.pick("видео", "video")
                ProbeGroup.Thumbs -> lang.pick("превью", "thumbs")
                ProbeGroup.Site -> lang.pick("сайт", "site")
            }
            val color = if (ok) colors.success else colors.danger
            Text(
                (if (ok) "✓ " else "✕ ") + label,
                style = MaterialTheme.typography.labelLarge,
                color = color,
                maxLines = 1,
                softWrap = false,
                modifier = Modifier
                    .clip(Shapes.Pill)
                    .background(color.copy(alpha = 0.12f))
                    .padding(horizontal = 10.dp, vertical = 3.dp),
            )
        }
    }
}

@Composable
private fun ScoreBadge(score: StrategyScore) {
    val colors = TubeTheme.colors
    val lang = LocalLanguage.current
    val color = when {
        score.works -> colors.success
        score.passed > 0 -> colors.warning
        else -> colors.danger
    }
    val text = when {
        score.startError != null -> lang.pick("ошибка", "error")
        score.works -> "${score.avgMillis} ${lang.pick("мс", "ms")}"
        else -> "${score.passed}/${score.total}"
    }
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = color,
        maxLines = 1,
        softWrap = false,
        modifier = Modifier
            .clip(Shapes.Pill)
            .background(color.copy(alpha = 0.14f))
            .padding(horizontal = 8.dp, vertical = 1.dp),
    )
}

@Composable
private fun CustomStrategyEditor(args: String, selected: Boolean, onSave: (String) -> String?) {
    val colors = TubeTheme.colors
    var text by remember { mutableStateOf(args) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(args) { if (text.isEmpty()) text = args }
    Column(
        Modifier
            .fillMaxWidth()
            .clip(Shapes.Chip)
            .background(colors.chip.copy(alpha = colors.chip.alpha * 0.6f))
            .padding(14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(tr("Своя стратегия", "Custom strategy"), style = MaterialTheme.typography.titleMedium, color = colors.textPrimary)
        Text(tr("Аргументы byedpi", "byedpi arguments"), style = MaterialTheme.typography.bodyMedium, color = colors.textSecondary)
        Spacer(Modifier.height(8.dp))
        BasicTextField(
            value = text,
            onValueChange = {
                text = it
                error = null
            },
            textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace, color = colors.textPrimary, textAlign = TextAlign.Center),
            cursorBrush = SolidColor(colors.accent),
            modifier = Modifier
                .fillMaxWidth()
                .clip(Shapes.Chip)
                .background(colors.chip)
                .padding(12.dp),
            decorationBox = { inner ->
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    if (text.isEmpty()) Text("-d1 -s1+s -r1+s", style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace), color = colors.textTertiary)
                    inner()
                }
            },
        )
        AnimatedVisibility(error != null) {
            Text(
                tr("Не понимаю «${error.orEmpty()}»", "Can't parse “${error.orEmpty()}”"),
                style = MaterialTheme.typography.bodyMedium,
                color = colors.danger,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
        Spacer(Modifier.height(8.dp))
        PillButton(
            if (selected && text.trim() == args) tr("Используется", "In use") else tr("Сохранить", "Save"),
            onClick = { error = onSave(text) },
            enabled = text.isNotBlank() && !(selected && text.trim() == args),
            compact = true,
        )
    }
}

fun AppLanguage.networkLabel(id: NetworkId): String = when (id.kind) {
    NetworkKind.Wifi -> "Wi-Fi"
    NetworkKind.Cellular -> id.operator ?: pick("Мобильная сеть", "Mobile network")
    NetworkKind.Other -> pick("Сеть", "Network")
}

/** «5 мин назад», «2 ч назад», «3 дн назад». */
fun AppLanguage.ago(time: Long): String {
    val minutes = ((System.currentTimeMillis() - time) / 60_000).coerceAtLeast(0)
    return when {
        minutes < 1 -> pick("только что", "just now")
        minutes < 60 -> pick("$minutes мин назад", "$minutes min ago")
        minutes < 60 * 24 -> pick("${minutes / 60} ч назад", "${minutes / 60} h ago")
        else -> pick("${minutes / 60 / 24} дн назад", "${minutes / 60 / 24} d ago")
    }
}
