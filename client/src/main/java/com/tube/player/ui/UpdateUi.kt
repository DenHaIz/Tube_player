package com.tube.player.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.tube.ui.components.PillButton
import com.tube.ui.components.bouncyClickable
import com.tube.ui.i18n.AppLanguage
import com.tube.ui.i18n.LocalLanguage
import com.tube.ui.i18n.tr
import com.tube.ui.theme.TubeIcons
import com.tube.ui.theme.TubeTheme
import com.tube.update.NotesSection
import com.tube.update.Release
import com.tube.update.ReleaseNotes
import com.tube.update.UpdateState

/** Релиз из состояния обновления (если он есть). */
val UpdateState.release: Release?
    get() = when (this) {
        is UpdateState.Available -> release
        is UpdateState.Downloading -> release
        is UpdateState.Ready -> release
        else -> null
    }

/** Короткая полоса «Доступна версия X» над лентой; по нажатию — лист с подробностями. */
@Composable
fun UpdateBanner(state: UpdateState, onOpen: () -> Unit, modifier: Modifier = Modifier) {
    AnimatedVisibility(state.release != null, enter = expandVertically(Anim.move()) + fadeIn(Anim.fade()), exit = shrinkVertically(Anim.move()) + fadeOut(Anim.out())) {
        state.release?.let { UpdateBannerRow(state, it, onOpen, modifier) }
    }
}

@Composable
private fun UpdateBannerRow(state: UpdateState, release: Release, onOpen: () -> Unit, modifier: Modifier) {
    val colors = TubeTheme.colors
    Row(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .background(colors.accent.copy(alpha = 0.14f), RoundedCornerShape(12.dp))
            .bouncyClickable(pressedScale = 0.98f, onClick = onOpen)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(TubeIcons.Bolt, null, tint = colors.accent, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(10.dp))
        Text(
            when (state) {
                is UpdateState.Downloading -> tr("Скачивание ${(state.progress * 100).toInt()}%", "Downloading ${(state.progress * 100).toInt()}%")
                is UpdateState.Ready -> tr("Версия ${release.version} готова к установке", "Version ${release.version} is ready to install")
                else -> tr("Доступна версия ${release.version}", "Version ${release.version} is available")
            },
            style = MaterialTheme.typography.bodyLarge,
            color = colors.textPrimary,
            modifier = Modifier.weight(1f),
        )
        Icon(TubeIcons.ChevronRight, null, tint = colors.textSecondary, modifier = Modifier.size(20.dp))
    }
}

/**
 * Лист обновления: версия, что нового — по категориям в свёрнутых «шторках» (окно не превращается
 * в длинный список), кнопка «Обновить» → прогресс → «Установить».
 */
@Composable
fun UpdateSheet(state: UpdateState, onCheck: () -> Unit, onDownload: (Release) -> Unit, onInstall: () -> Unit, onLater: () -> Unit) {
    val colors = TubeTheme.colors
    val lang = LocalLanguage.current
    val release = state.release
    Column(
        Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            if (release != null) "Tube Player ${release.version}" else tr("Обновления", "Updates"),
            style = MaterialTheme.typography.headlineSmall,
            color = colors.textPrimary,
            modifier = Modifier.padding(horizontal = 4.dp),
        )
        val status = when (state) {
            UpdateState.Idle -> null
            UpdateState.Checking -> tr("Проверяю…", "Checking…")
            UpdateState.UpToDate -> tr("Установлена последняя версия", "You have the latest version")
            is UpdateState.Available -> tr("Доступна новая версия", "A new version is available")
            is UpdateState.Downloading -> tr("Скачивание ${(state.progress * 100).toInt()}%", "Downloading ${(state.progress * 100).toInt()}%")
            is UpdateState.Ready -> tr("Скачано и проверено — можно ставить", "Downloaded and verified — ready to install")
            is UpdateState.Failed -> tr("Не удалось: ", "Failed: ") + state.message
        }
        status?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = colors.textSecondary, modifier = Modifier.padding(horizontal = 4.dp)) }
        if (state is UpdateState.Downloading) {
            LinearProgressIndicator(
                progress = { state.progress },
                color = colors.accent,
                trackColor = colors.chip,
                modifier = Modifier.fillMaxWidth().height(6.dp),
            )
        }
        if (release != null) {
            val sections = ReleaseNotes.sections(release.notes, english = lang == AppLanguage.English)
            sections.forEach { section -> NotesShutter(section, startExpanded = sections.size == 1 && section.items.size <= 3) }
        }
        Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            when (state) {
                is UpdateState.Available -> PillButton(tr("Обновить", "Update"), onClick = { onDownload(state.release) }, filled = true, icon = TubeIcons.Bolt)
                is UpdateState.Ready -> PillButton(tr("Установить", "Install"), onClick = onInstall, filled = true, icon = TubeIcons.Check)
                is UpdateState.Downloading -> Unit
                else -> PillButton(tr("Проверить", "Check now"), onClick = onCheck, filled = true, enabled = state !is UpdateState.Checking)
            }
            if (release != null && state !is UpdateState.Downloading) PillButton(tr("Позже", "Later"), onClick = onLater)
        }
    }
}

/** «Шторка» категории: заголовок с числом пунктов, по нажатию плавно раскрывается список. */
@Composable
private fun NotesShutter(section: NotesSection, startExpanded: Boolean) {
    var expanded by remember { mutableStateOf(startExpanded) }
    val colors = TubeTheme.colors
    val rotation by animateFloatAsState(if (expanded) 180f else 0f, Anim.move(), label = "chevron")
    Column(
        Modifier
            .fillMaxWidth()
            .background(colors.card, RoundedCornerShape(16.dp))
            .animateContentSize(Anim.move()),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .bouncyClickable(pressedScale = 0.99f) { expanded = !expanded }
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                section.title.ifEmpty { tr("Что нового", "What's new") },
                style = MaterialTheme.typography.bodyLarge,
                color = colors.textPrimary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text(section.items.size.toString(), style = MaterialTheme.typography.bodyMedium, color = colors.textSecondary)
            Spacer(Modifier.width(8.dp))
            Icon(TubeIcons.ChevronDown, null, tint = colors.textSecondary, modifier = Modifier.size(20.dp).graphicsLayer { rotationZ = rotation })
        }
        AnimatedVisibility(expanded, enter = expandVertically(Anim.move()) + fadeIn(Anim.fade()), exit = shrinkVertically(Anim.move()) + fadeOut(Anim.out())) {
            Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                section.items.forEach { item ->
                    Row {
                        Text("•", style = MaterialTheme.typography.bodyMedium, color = colors.accent)
                        Spacer(Modifier.width(8.dp))
                        Text(item, style = MaterialTheme.typography.bodyMedium, color = colors.textPrimary)
                    }
                }
            }
        }
    }
}
