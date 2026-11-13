package com.tube.player.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.tube.player.youtube.ChannelSort
import com.tube.player.youtube.DateRange
import com.tube.ui.components.PillButton
import com.tube.ui.components.bouncyClickable
import com.tube.ui.i18n.tr
import com.tube.ui.theme.TubeIcons
import com.tube.ui.theme.TubeTheme
import java.time.LocalDate

/**
 * Порядок видео канала: «Новые / Популярные / Старые» и справа кнопка-календарь (лист с датами).
 * Выбранный период — чип «01.01.2024 – 31.12.2024» строкой ниже: нажатие — изменить, крестик — сбросить.
 * Чипы как на главной; ряд листается вбок, если не влезает.
 */
@Composable
fun ChannelFilters(sort: ChannelSort, range: DateRange, onSort: (ChannelSort) -> Unit, onPeriod: () -> Unit, onClearPeriod: () -> Unit, modifier: Modifier = Modifier) {
    val colors = TubeTheme.colors
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Row(
                Modifier.weight(1f).horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ChannelSort.entries.forEach { s -> FilterChip(channelSortLabel(s), s == sort, { onSort(s) }) }
            }
            Spacer(Modifier.width(8.dp))
            val bg by androidx.compose.animation.animateColorAsState(if (range.active) colors.accent else colors.chip, label = "periodBg")
            val fg by androidx.compose.animation.animateColorAsState(if (range.active) colors.onAccent else colors.textPrimary, label = "periodFg")
            val periodName = tr("Период", "Dates")
            Box(
                Modifier
                    .size(40.dp, 32.dp)
                    .bouncyClickable(pressedScale = 0.9f, onClick = onPeriod)
                    .background(bg, RoundedCornerShape(8.dp))
                    .semantics { contentDescription = periodName },
                contentAlignment = Alignment.Center,
            ) {
                Icon(TubeIcons.Calendar, null, tint = fg, modifier = Modifier.size(18.dp))
            }
        }
        androidx.compose.animation.AnimatedVisibility(
            range.active,
            enter = androidx.compose.animation.fadeIn(Anim.fade()) + androidx.compose.animation.expandVertically(Anim.move()),
            exit = androidx.compose.animation.fadeOut(Anim.out()) + androidx.compose.animation.shrinkVertically(Anim.move()),
        ) {
            // Последний непустой период — чтобы при сбросе чип уходил со своим текстом, а не пустым.
            var shown by remember { mutableStateOf(range) }
            if (range.active) shown = range
            FilterChip(
                periodLabel(shown).orEmpty(),
                active = true,
                onClick = onPeriod,
                icon = TubeIcons.Calendar,
                onClear = onClearPeriod,
                modifier = Modifier.padding(horizontal = 12.dp),
            )
        }
    }
}

@Composable
fun channelSortLabel(sort: ChannelSort): String = when (sort) {
    ChannelSort.Newest -> tr("Новые", "Latest")
    ChannelSort.Popular -> tr("Популярные", "Popular")
    ChannelSort.Oldest -> tr("Старые", "Oldest")
}

/** «01.01.2020 – 31.12.2020», «с 01.01.2020», «по 31.12.2020»; без дат — null. */
@Composable
fun periodLabel(range: DateRange): String? {
    val from = range.from?.let(::formatDate)
    val to = range.to?.let(::formatDate)
    return when {
        from != null && to != null -> "$from – $to"
        from != null -> tr("с ", "from ") + from
        to != null -> tr("по ", "until ") + to
        else -> null
    }
}

/** Дата для людей: 01.02.2024. */
fun formatDate(d: LocalDate): String = "%02d.%02d.%04d".format(d.dayOfMonth, d.monthValue, d.year)

/** «01022024» (8 цифр, без точек) → дата; неполная или несуществующая — null. */
fun parseDigits(digits: String): LocalDate? {
    if (digits.length != 8 || !digits.all { it.isDigit() }) return null
    return runCatching { LocalDate.of(digits.substring(4).toInt(), digits.substring(2, 4).toInt(), digits.substring(0, 2).toInt()) }.getOrNull()
}

private fun digitsOf(d: LocalDate?) = d?.let { formatDate(it).replace(".", "") }.orEmpty()

/**
 * Лист «Период»: поля «С» и «По» (дд.мм.гггг, точки ставятся сами, цифровая клавиатура), быстрые варианты —
 * неделя, месяц, год и последние годы целиком. Пустое поле — без границы.
 */
@Composable
fun PeriodSheet(range: DateRange, onApply: (DateRange) -> Unit, today: LocalDate = LocalDate.now()) {
    val colors = TubeTheme.colors
    var from by remember(range) { mutableStateOf(digitsOf(range.from)) }
    var to by remember(range) { mutableStateOf(digitsOf(range.to)) }
    val fromDate = parseDigits(from)
    val toDate = parseDigits(to)
    val fromBad = from.isNotEmpty() && fromDate == null
    val toBad = to.isNotEmpty() && toDate == null
    val reversed = fromDate != null && toDate != null && fromDate > toDate
    val error = when {
        fromBad || toBad -> tr("Дата — дд.мм.гггг", "Use dd.mm.yyyy")
        reversed -> tr("Начало позже конца", "Start is after end")
        else -> null
    }
    Column(
        Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SheetTitle(tr("Период", "Dates"), tr("Видео, опубликованные в эти дни", "Videos published on these days"))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            DateField(tr("С", "From"), from, { from = it }, fromBad || reversed, Modifier.weight(1f))
            DateField(tr("По", "To"), to, { to = it }, toBad || reversed, Modifier.weight(1f))
        }
        Text(
            error ?: tr("Пустое поле — без ограничения", "Leave empty for no limit"),
            style = MaterialTheme.typography.bodyMedium,
            color = if (error != null) colors.danger else colors.textSecondary,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        val presets = listOf(
            tr("Неделя", "Week") to (today.minusDays(6) to today),
            tr("Месяц", "Month") to (today.minusMonths(1).plusDays(1) to today),
            tr("Год", "Year") to (today.minusYears(1).plusDays(1) to today),
        ) + (1..2).map { back ->
            val y = today.year - back
            "$y" to (LocalDate.of(y, 1, 1) to LocalDate.of(y, 12, 31))
        }
        androidx.compose.foundation.layout.FlowRow(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            presets.forEach { (label, dates) ->
                val chosen = fromDate == dates.first && toDate == dates.second
                FilterChip(label, chosen, {
                    from = digitsOf(dates.first)
                    to = digitsOf(dates.second)
                })
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            PillButton(tr("Сбросить", "Clear"), onClick = { onApply(DateRange()) }, modifier = Modifier.weight(1f))
            PillButton(
                tr("Показать", "Show"),
                onClick = { onApply(DateRange(fromDate, toDate)) },
                modifier = Modifier.weight(1f),
                filled = true,
                enabled = error == null,
            )
        }
    }
}

/** Поле даты: подпись над ним, 8 цифр с точками-разделителями, подсказка «дд.мм.гггг». */
@Composable
private fun DateField(label: String, digits: String, onChange: (String) -> Unit, bad: Boolean, modifier: Modifier) {
    val colors = TubeTheme.colors
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, style = MaterialTheme.typography.labelLarge, color = colors.textSecondary, modifier = Modifier.padding(start = 4.dp))
        BasicTextField(
            value = digits,
            onValueChange = { v -> onChange(v.filter { it.isDigit() }.take(8)) },
            singleLine = true,
            textStyle = MaterialTheme.typography.titleMedium.copy(color = colors.textPrimary, textAlign = TextAlign.Center),
            cursorBrush = SolidColor(colors.accent),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Next),
            visualTransformation = DateMask,
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp)
                .background(colors.card, RoundedCornerShape(14.dp))
                .border(1.dp, if (bad) colors.danger else colors.chip, RoundedCornerShape(14.dp)),
            decorationBox = { inner ->
                Box(Modifier.padding(PaddingValues(horizontal = 12.dp)), contentAlignment = Alignment.Center) {
                    if (digits.isEmpty()) Text(tr("дд.мм.гггг", "dd.mm.yyyy"), style = MaterialTheme.typography.titleMedium, color = colors.textTertiary)
                    inner()
                }
            },
        )
    }
}

/** «01022024» показывается как «01.02.2024»: точки после дня и месяца, по мере ввода. */
private object DateMask : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        val d = text.text
        val out = buildString {
            d.forEachIndexed { i, c ->
                if (i == 2 || i == 4) append('.')
                append(c)
            }
        }
        val mapping = object : OffsetMapping {
            override fun originalToTransformed(offset: Int) = when {
                offset <= 2 -> offset
                offset <= 4 -> offset + 1
                else -> offset + 2
            }.coerceAtMost(out.length)

            override fun transformedToOriginal(offset: Int) = when {
                offset <= 2 -> offset
                offset <= 5 -> offset - 1
                else -> offset - 2
            }.coerceIn(0, d.length)
        }
        return TransformedText(AnnotatedString(out), mapping)
    }
}
