package com.tube.player.ui

import androidx.compose.animation.animateContentSize
import com.tube.ui.theme.Shapes
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.material3.Icon
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.fromHtml
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.media3.common.Player
import com.tube.player.data.PlayerSettings
import com.tube.player.player.PlaybackState
import com.tube.player.youtube.ChannelItem
import com.tube.player.youtube.FeedItem
import com.tube.player.youtube.VideoItem
import com.tube.ui.components.bouncyClickable
import com.tube.ui.i18n.LocalLanguage
import com.tube.ui.i18n.tr
import com.tube.ui.theme.TubeIcons
import com.tube.ui.theme.TubeTheme

/** Действия страницы видео. */
class VideoActions(
    val onMinimize: () -> Unit,
    val onFullscreen: (Boolean) -> Unit,
    val onQuality: () -> Unit,
    val onPopup: () -> Unit,
    val onPip: () -> Unit,
    val onBackground: () -> Unit,
    val onShare: () -> Unit,
    val onSkip: () -> Unit,
    val onUndoSkip: () -> Unit,
    val onRetry: () -> Unit,
    val onOpen: (FeedItem) -> Unit,
    val onChannel: (ChannelItem) -> Unit,
    val onSubscribe: (ChannelItem) -> Unit,
    val onSeekTo: (Long) -> Unit,
)

/**
 * Страница видео: плеер, название по центру, канал и подписка, кнопки, описание и похожие.
 * Телефон боком — плеер на весь экран; планшет и широкий экран — похожие видео справа.
 */
@Composable
fun VideoScreen(
    player: Player?,
    state: PlaybackState,
    settings: PlayerSettings,
    subscribed: Boolean,
    fullscreen: Boolean,
    actions: VideoActions,
    hideShorts: Boolean,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(modifier.fillMaxSize().background(TubeTheme.colors.background)) {
        val landscape = maxWidth > maxHeight
        val wide = maxWidth >= 900.dp && landscape
        val playerBox: @Composable (Modifier) -> Unit = { m ->
            PlayerView(
                player, state, settings, fullscreen = fullscreen,
                onFullscreen = actions.onFullscreen, onMinimize = actions.onMinimize, onQuality = actions.onQuality,
                onPopup = actions.onPopup, onPip = actions.onPip, onSkip = actions.onSkip, onUndoSkip = actions.onUndoSkip,
                onRetry = actions.onRetry, modifier = m,
            )
        }
        when {
            fullscreen || (landscape && !wide) -> playerBox(Modifier.fillMaxSize())
            wide -> Row(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
                LazyColumn(Modifier.weight(0.62f).fillMaxHeight(), contentPadding = PaddingValues(bottom = 24.dp)) {
                    item { playerBox(Modifier.fillMaxWidth().aspectRatio(16f / 9f)) }
                    info(state, subscribed, actions)
                }
                LazyColumn(
                    Modifier.weight(0.38f).fillMaxHeight(),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                ) {
                    related(state, hideShorts, actions)
                }
            }
            else -> Column(Modifier.fillMaxSize()) {
                Box(Modifier.fillMaxWidth().background(Color.Black).padding(WindowInsets.statusBars.asPaddingValues())) {
                    playerBox(Modifier.fillMaxWidth().aspectRatio(16f / 9f))
                }
                LazyColumn(
                    Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = 24.dp + WindowInsets.safeDrawing.asPaddingValues().calculateBottomPadding()),
                ) {
                    info(state, subscribed, actions)
                    related(state, hideShorts, actions, padded = true)
                }
            }
        }
    }
}

private fun LazyListScope.info(state: PlaybackState, subscribed: Boolean, actions: VideoActions) {
    val item = state.details?.item ?: state.item ?: return
    item(key = "title") {
        val lang = LocalLanguage.current
        Column(Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 12.dp)) {
            Text(item.title, style = VideoTitle, color = TubeTheme.colors.textPrimary, maxLines = 3, overflow = TextOverflow.Ellipsis)
            val meta = Format.meta(Format.views(item.views, lang), item.uploaded)
            if (meta.isNotEmpty()) {
                Text(meta, style = MaterialTheme.typography.bodyMedium, color = TubeTheme.colors.textSecondary, modifier = Modifier.padding(top = 4.dp))
            }
        }
    }
    item(key = "channel") {
        val lang = LocalLanguage.current
        val channel = ChannelItem(item.channelUrl ?: "", item.channel ?: "", item.channelAvatar, state.details?.subscribers ?: -1)
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                Modifier.weight(1f).bouncyClickable(pressedScale = 0.98f) { if (channel.url.isNotEmpty()) actions.onChannel(channel) },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Avatar(item.channelAvatar, 40.dp)
                Spacer(Modifier.width(12.dp))
                Text(item.channel.orEmpty(), style = CardTitle, color = TubeTheme.colors.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                val subs = Format.compact(channel.subscribers, lang)
                if (subs.isNotEmpty()) {
                    Spacer(Modifier.width(8.dp))
                    Text(subs, style = MaterialTheme.typography.bodyMedium, color = TubeTheme.colors.textSecondary, maxLines = 1)
                }
            }
            if (channel.url.isNotEmpty()) {
                Spacer(Modifier.width(8.dp))
                SubscribeButton(subscribed) { actions.onSubscribe(channel) }
            }
        }
    }
    item(key = "actions") {
        val lang = LocalLanguage.current
        androidx.compose.foundation.lazy.LazyRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(horizontal = 12.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            item {
                // Лайки и дизлайки — одна разделённая кнопка, как в YouTube.
                Row(Modifier.height(36.dp).background(TubeTheme.colors.chip, Shapes.Pill), verticalAlignment = Alignment.CenterVertically) {
                    Row(Modifier.padding(start = 14.dp, end = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(TubeIcons.ThumbUp, null, tint = TubeTheme.colors.textPrimary, modifier = Modifier.size(20.dp))
                        state.details?.likes?.takeIf { it >= 0 }?.let {
                            Spacer(Modifier.width(6.dp))
                            Text(Format.compact(it, lang), style = ActionText, color = TubeTheme.colors.textPrimary)
                        }
                    }
                    Box(Modifier.width(1.dp).height(20.dp).background(TubeTheme.colors.textTertiary.copy(alpha = 0.4f)))
                    Row(Modifier.padding(start = 12.dp, end = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(TubeIcons.ThumbDown, null, tint = TubeTheme.colors.textPrimary, modifier = Modifier.size(20.dp))
                        state.dislikes?.let {
                            Spacer(Modifier.width(6.dp))
                            Text(Format.compact(it, lang), style = ActionText, color = TubeTheme.colors.textPrimary)
                        }
                    }
                }
            }
            item { ActionPill(TubeIcons.Share, tr("Поделиться", "Share"), actions.onShare) }
            item { ActionPill(TubeIcons.Headphones, tr("Фон", "Background"), actions.onBackground) }
            item { ActionPill(TubeIcons.Popup, tr("Окно", "Window"), actions.onPopup) }
            item { ActionPill(TubeIcons.Gear, tr("Качество", "Quality"), actions.onQuality) }
        }
    }
    val details = state.details ?: return
    if (details.description.isNotBlank()) {
        item(key = "description") { Description(details, actions.onSeekTo) }
    }
}

/** Кнопка «Подписаться» как в YouTube: капсула 36dp, инвертированная; после подписки — серая. */
@Composable
fun SubscribeButton(subscribed: Boolean, onClick: () -> Unit) {
    val colors = TubeTheme.colors
    val bg by animateColorAsState(if (subscribed) colors.chip else colors.textPrimary, Anim.fade(), label = "subBg")
    val fg by animateColorAsState(if (subscribed) colors.textPrimary else colors.background, Anim.fade(), label = "subFg")
    Row(
        Modifier
            .height(36.dp)
            .bouncyClickable(pressedScale = 0.94f, onClick = onClick)
            .background(bg, Shapes.Pill)
            .padding(horizontal = 16.dp)
            .animateContentSize(Anim.move()),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (subscribed) {
            Icon(TubeIcons.Bell, null, tint = fg, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
        }
        Text(if (subscribed) tr("Вы подписаны", "Subscribed") else tr("Подписаться", "Subscribe"), style = ActionText, color = fg)
    }
}

/** Серая капсула 36dp со значком 20dp — кнопки под видео. */
@Composable
fun ActionPill(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String, onClick: () -> Unit) {
    Row(
        Modifier
            .height(36.dp)
            .bouncyClickable(pressedScale = 0.94f, onClick = onClick)
            .background(TubeTheme.colors.chip, Shapes.Pill)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = TubeTheme.colors.textPrimary, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(6.dp))
        Text(text, style = ActionText, color = TubeTheme.colors.textPrimary)
    }
}

private val VideoTitle = androidx.compose.ui.text.TextStyle(fontSize = 19.sp, lineHeight = 25.sp, fontWeight = FontWeight.Bold)
private val ActionText = androidx.compose.ui.text.TextStyle(fontSize = 14.sp, lineHeight = 18.sp, fontWeight = FontWeight.Medium)

/** Описание как в YouTube: серая карточка, сверху просмотры и дата, по нажатию — целиком с главами. */
@Composable
private fun Description(details: com.tube.player.youtube.VideoDetails, onSeek: (Long) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val colors = TubeTheme.colors
    val accent = colors.accent
    val lang = LocalLanguage.current
    val annotated = remember(details.description, details.descriptionHtml) {
        if (details.descriptionHtml) AnnotatedString.fromHtml(details.description, linkStyles = TextLinkStyles(SpanStyle(color = accent)))
        else AnnotatedString(details.description)
    }
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 12.dp)
            .background(colors.chip, RoundedCornerShape(12.dp))
            .bouncyClickable(pressedScale = 0.99f) { expanded = !expanded }
            .animateContentSize(Anim.move())
            .padding(12.dp),
    ) {
        Text(
            Format.meta(Format.views(details.item.views, lang), details.item.uploaded),
            style = ActionText.copy(fontWeight = FontWeight.SemiBold),
            color = colors.textPrimary,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            annotated,
            style = MaterialTheme.typography.bodyMedium,
            color = colors.textPrimary,
            maxLines = if (expanded) Int.MAX_VALUE else 2,
            overflow = TextOverflow.Ellipsis,
        )
        if (!expanded) {
            Text(tr("ещё", "more"), style = ActionText, color = colors.textSecondary, modifier = Modifier.padding(top = 2.dp))
        }
        if (expanded && details.chapters.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            Text(tr("Главы", "Chapters"), style = CardTitle, color = colors.textPrimary)
            details.chapters.forEach { c ->
                Row(
                    Modifier.fillMaxWidth().bouncyClickable(pressedScale = 0.98f) { onSeek(c.startSeconds * 1000L) }.padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(Format.duration(c.startSeconds.toLong()).ifEmpty { "0:00" }, style = ActionText, color = accent)
                    Spacer(Modifier.width(12.dp))
                    Text(c.title, style = MaterialTheme.typography.bodyMedium, color = colors.textPrimary)
                }
            }
        }
    }
}

private fun LazyListScope.related(state: PlaybackState, hideShorts: Boolean, actions: VideoActions, padded: Boolean = false) {
    val related = state.details?.related.orEmpty().filter { !hideShorts || it !is VideoItem || !it.short }
    if (related.isEmpty()) return
    items(related.size, key = { "r" + related[it].url }) { i ->
        androidx.compose.runtime.CompositionLocalProvider(
            LocalThumbShape provides if (padded) androidx.compose.ui.graphics.RectangleShape else ThumbShape,
            LocalCardInset provides if (padded) 12.dp else 0.dp,
        ) {
            Box(Modifier.fillMaxWidth().padding(bottom = 12.dp)) { FeedItemCard(related[i], actions.onOpen) }
        }
    }
}

/** Мини-плеер над вкладками, как в YouTube: превью слева, название и канал, «пауза» и «закрыть»; тап — развернуть. */
@Composable
fun MiniPlayer(
    state: PlaybackState,
    playing: Boolean,
    onExpand: () -> Unit,
    onPlayPause: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val item = state.item ?: return
    Row(
        modifier
            .fillMaxWidth()
            .height(60.dp)
            .bouncyClickable(pressedScale = 0.99f, onClick = onExpand),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        NetImage(item.thumbnail, Modifier.fillMaxHeight().aspectRatio(16f / 9f))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(item.title, style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium), color = TubeTheme.colors.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            item.channel?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = TubeTheme.colors.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
        BarIcon(if (playing) TubeIcons.Pause else TubeIcons.Play, if (playing) tr("Пауза", "Pause") else tr("Играть", "Play"), onPlayPause)
        BarIcon(TubeIcons.Close, tr("Закрыть", "Close"), onClose)
    }
}
