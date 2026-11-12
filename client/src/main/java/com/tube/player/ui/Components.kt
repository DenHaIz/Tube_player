package com.tube.player.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.fadeIn
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.background
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.tube.player.youtube.ChannelItem
import com.tube.player.youtube.FeedItem
import com.tube.player.youtube.PlaylistItem
import com.tube.player.youtube.VideoItem
import com.tube.ui.components.PillButton
import com.tube.ui.components.bouncyClickable
import com.tube.ui.glass.liquidGlass
import com.tube.ui.i18n.LocalLanguage
import com.tube.ui.i18n.tr
import com.tube.ui.theme.Shapes
import com.tube.ui.theme.TubeIcons
import com.tube.ui.theme.TubeTheme

val ThumbShape = RoundedCornerShape(12.dp)

/** Форма превью: на телефоне в одну колонку — во всю ширину без скругления, как в YouTube; в сетке — скруглённые. */
val LocalThumbShape = staticCompositionLocalOf<Shape> { ThumbShape }

/** Отступ текста карточки от края: на телефоне превью во всю ширину, а текст — с отступом 12dp. */
val LocalCardInset = staticCompositionLocalOf { 0.dp }

/** Картинка из сети с мягкой подложкой, пока грузится (и в скриншотах, где сети нет). */
@Composable
fun NetImage(url: String?, modifier: Modifier = Modifier, contentScale: ContentScale = ContentScale.Crop) {
    Box(modifier.background(TubeTheme.colors.chip)) {
        if (url != null) AsyncImage(model = url, contentDescription = null, contentScale = contentScale, modifier = Modifier.fillMaxSize())
    }
}

/** Превью 16:9 с длительностью или пометкой «Эфир» и полосой просмотренного. */
@Composable
fun Thumbnail(video: VideoItem, modifier: Modifier = Modifier, progress: Float = 0f) {
    val colors = TubeTheme.colors
    Box(modifier.aspectRatio(16f / 9f).clip(LocalThumbShape.current)) {
        NetImage(video.thumbnail, Modifier.fillMaxSize())
        val badge = when {
            video.live -> tr("Эфир", "Live")
            video.short -> "Shorts"
            else -> Format.duration(video.duration)
        }
        if (badge.isNotEmpty()) {
            Text(
                badge,
                style = MaterialTheme.typography.labelMedium,
                color = Color.White,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(8.dp)
                    .background(if (video.live) colors.danger else Color.Black.copy(alpha = 0.72f), RoundedCornerShape(8.dp))
                    .padding(horizontal = 7.dp, vertical = 2.dp),
            )
        }
        if (progress > 0.02f) {
            Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(4.dp).background(Color.Black.copy(alpha = 0.35f)))
            Box(
                Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth(progress.coerceIn(0f, 1f))
                    .height(4.dp)
                    .background(colors.accent),
            )
        }
    }
}

/** Карточка видео как в YouTube: превью, ниже аватар канала слева, заголовок и подпись рядом. */
@Composable
fun VideoCard(video: VideoItem, onClick: () -> Unit, modifier: Modifier = Modifier, progress: Float = 0f, onLongClick: (() -> Unit)? = null) {
    val lang = LocalLanguage.current
    Column(
        modifier
            .fillMaxWidth()
            .bouncyClickable(pressedScale = 0.98f, onLongClick = onLongClick, onClick = onClick)
            .padding(bottom = 8.dp),
    ) {
        Thumbnail(video, Modifier.fillMaxWidth(), progress)
        Row(Modifier.fillMaxWidth().padding(top = 12.dp, start = LocalCardInset.current, end = LocalCardInset.current)) {
            if (video.channelAvatar != null || video.channel != null) {
                Avatar(video.channelAvatar, 36.dp)
                Spacer(Modifier.width(12.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(
                    video.title,
                    style = CardTitle,
                    color = TubeTheme.colors.textPrimary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                val meta = Format.meta(video.channel, Format.views(video.views, lang).takeIf { !video.live || video.views > 0 }, Format.uploaded(video, lang))
                if (meta.isNotEmpty()) {
                    Text(
                        meta,
                        style = MaterialTheme.typography.bodyMedium,
                        color = TubeTheme.colors.textSecondary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
            }
        }
    }
}

/** Канал в выдаче: крупный аватар слева, имя и подписчики справа. */
@Composable
fun ChannelCard(channel: ChannelItem, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val lang = LocalLanguage.current
    Row(
        modifier.fillMaxWidth().bouncyClickable(pressedScale = 0.98f, onClick = onClick).padding(vertical = 8.dp, horizontal = LocalCardInset.current),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.weight(0.4f), contentAlignment = Alignment.Center) { Avatar(channel.avatar, 88.dp) }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(0.6f)) {
            Text(channel.name, style = MaterialTheme.typography.titleMedium, color = TubeTheme.colors.textPrimary, maxLines = 2, overflow = TextOverflow.Ellipsis)
            val subs = Format.subscribers(channel.subscribers, lang)
            if (subs.isNotEmpty()) Text(subs, style = MaterialTheme.typography.bodyMedium, color = TubeTheme.colors.textSecondary)
        }
    }
}

@Composable
fun PlaylistCard(playlist: PlaylistItem, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().bouncyClickable(pressedScale = 0.98f, onClick = onClick).padding(bottom = 8.dp)) {
        Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(LocalThumbShape.current)) {
            NetImage(playlist.thumbnail, Modifier.fillMaxSize())
            Row(
                Modifier
                    .align(Alignment.BottomEnd)
                    .padding(8.dp)
                    .background(Color.Black.copy(alpha = 0.72f), RoundedCornerShape(8.dp))
                    .padding(horizontal = 7.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(TubeIcons.Library, null, tint = Color.White, modifier = Modifier.size(14.dp))
                if (playlist.count >= 0) {
                    Spacer(Modifier.width(4.dp))
                    Text(playlist.count.toString(), style = MaterialTheme.typography.labelMedium, color = Color.White)
                }
            }
        }
        Column(Modifier.padding(horizontal = LocalCardInset.current)) {
            Text(
                playlist.name,
                style = CardTitle,
                color = TubeTheme.colors.textPrimary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 12.dp),
            )
            Text(Format.meta(playlist.uploader, tr("Плейлист", "Playlist")), style = MaterialTheme.typography.bodyMedium, color = TubeTheme.colors.textSecondary, maxLines = 1)
        }
    }
}

@Composable
fun FeedItemCard(item: FeedItem, onOpen: (FeedItem) -> Unit, progress: Float = 0f) = when (item) {
    is VideoItem -> VideoCard(item, onClick = { onOpen(item) }, progress = progress)
    is ChannelItem -> ChannelCard(item, onClick = { onOpen(item) })
    is PlaylistItem -> PlaylistCard(item, onClick = { onOpen(item) })
}

/** Ширина колонки сетки: на телефоне в портрете — одна колонка, шире — несколько. */
val GridMinCell: Dp = 300.dp

/**
 * Сетка выдачи с подгрузкой при прокрутке. [header] — элементы во всю ширину сверху (шапка канала, чипы).
 * [contentPadding] — под стеклянные панели сверху и снизу.
 */
@Composable
fun FeedGrid(
    state: FeedState,
    onOpen: (FeedItem) -> Unit,
    onMore: () -> Unit,
    onRetry: () -> Unit,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
    hideShorts: Boolean = false,
    gridState: LazyGridState = rememberLazyGridState(),
    empty: String? = null,
    header: (LazyGridScope.() -> Unit)? = null,
) {
    val items = if (hideShorts) state.items.filter { it !is VideoItem || !it.short } else state.items
    val nearEnd by remember(gridState) {
        derivedStateOf {
            val info = gridState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()?.index ?: 0
            info.totalItemsCount > 0 && last >= info.totalItemsCount - 6
        }
    }
    LaunchedEffect(nearEnd, state.items.size) { if (nearEnd) onMore() }
    BoxWithConstraints(modifier.fillMaxSize()) {
        // Телефон: одна колонка, превью во всю ширину (как в приложении YouTube); шире — сетка карточек.
        val phone = maxWidth < 600.dp
        CompositionLocalProvider(
            LocalThumbShape provides if (phone) RectangleShape else ThumbShape,
            LocalCardInset provides if (phone) 12.dp else 0.dp,
        ) {
            LazyVerticalGrid(
                columns = if (phone) GridCells.Fixed(1) else GridCells.Adaptive(GridMinCell),
                state = gridState,
                contentPadding = if (phone) edgeToEdge(contentPadding) else contentPadding,
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalArrangement = Arrangement.spacedBy(if (phone) 12.dp else 20.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                header?.invoke(this)
                when {
                    state.loading && items.isEmpty() -> items(if (phone) 3 else 8, key = { "skeleton$it" }) { SkeletonCard(Modifier.animateItem()) }
                    state.error != null && items.isEmpty() -> item(span = { GridItemSpan(maxLineSpan) }) {
                        Message(tr("Не удалось загрузить", "Couldn't load"), state.error, tr("Повторить", "Retry"), onRetry)
                    }
                    items.isEmpty() && empty != null -> item(span = { GridItemSpan(maxLineSpan) }) { Message(empty, null, null, null) }
                    else -> {
                        items(items.size, key = { items[it].url }, contentType = { items[it]::class }) { i ->
                            Box(Modifier.animateItem(fadeInSpec = Anim.fade(), placementSpec = Anim.move(), fadeOutSpec = Anim.out())) {
                                FeedItemCard(items[i], onOpen)
                            }
                        }
                        if (state.loadingMore) item(span = { GridItemSpan(maxLineSpan) }) { Loading(Modifier.padding(16.dp)) }
                    }
                }
            }
        }
    }
}

/** Отступы без боковых полей — для ленты во всю ширину. */
fun edgeToEdge(p: PaddingValues) = PaddingValues(top = p.calculateTopPadding(), bottom = p.calculateBottomPadding())

/** Заголовок карточки: 16sp средней насыщенности — как в YouTube, без мелкого шрифта. */
val CardTitle = TextStyle(fontSize = 16.sp, lineHeight = 21.sp, fontWeight = FontWeight.Medium)

/**
 * Заготовка карточки, пока лента грузится: превью и две строки текста мягко «дышат».
 * Одна бесконечная анимация на карточку и только пока виден скелетон — в покое ничего не перерисовывается.
 */
@Composable
fun SkeletonCard(modifier: Modifier = Modifier) {
    val pulse = androidx.compose.animation.core.rememberInfiniteTransition(label = "skeleton")
    val alpha by pulse.animateFloat(
        0.45f, 0.9f,
        androidx.compose.animation.core.infiniteRepeatable(tween(700), androidx.compose.animation.core.RepeatMode.Reverse),
        label = "alpha",
    )
    val color = TubeTheme.colors.chip
    Column(modifier.fillMaxWidth().graphicsLayer { this.alpha = alpha }) {
        Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(LocalThumbShape.current).background(color))
        Row(Modifier.fillMaxWidth().padding(top = 12.dp, start = LocalCardInset.current, end = LocalCardInset.current)) {
            Box(Modifier.size(36.dp).clip(CircleShape).background(color))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Box(Modifier.fillMaxWidth(0.9f).height(16.dp).clip(RoundedCornerShape(6.dp)).background(color))
                Spacer(Modifier.height(8.dp))
                Box(Modifier.fillMaxWidth(0.55f).height(14.dp).clip(RoundedCornerShape(6.dp)).background(color))
            }
        }
    }
}

@Composable
fun Loading(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(color = TubeTheme.colors.accent, strokeWidth = 3.dp, modifier = Modifier.size(36.dp))
    }
}

/** Сообщение по центру: заголовок, пояснение, кнопка. */
@Composable
fun Message(title: String, details: String?, action: String?, onAction: (() -> Unit)?, modifier: Modifier = Modifier) {
    Column(
        modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(title, style = MaterialTheme.typography.titleLarge, color = TubeTheme.colors.textPrimary, textAlign = TextAlign.Center)
        if (details != null) {
            Text(details, style = MaterialTheme.typography.bodyMedium, color = TubeTheme.colors.textSecondary, textAlign = TextAlign.Center, maxLines = 4, modifier = Modifier.widthIn(max = 420.dp))
        }
        if (action != null && onAction != null) {
            Spacer(Modifier.height(4.dp))
            PillButton(action, onClick = onAction, filled = true)
        }
    }
}

/** Круглая стеклянная кнопка с иконкой. */
@Composable
fun GlassIconButton(icon: ImageVector, description: String, onClick: () -> Unit, modifier: Modifier = Modifier, size: Dp = 48.dp, tint: Color = TubeTheme.colors.textPrimary) {
    Box(
        modifier
            .size(size)
            .bouncyClickable(pressedScale = 0.9f, onClick = onClick)
            .liquidGlass(RoundedCornerShape(50))
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, null, tint = tint, modifier = Modifier.size(size * 0.46f))
    }
}

/** Подзаголовок раздела по центру. */
@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.titleLarge,
        color = TubeTheme.colors.textPrimary,
        modifier = modifier.fillMaxWidth().padding(top = 12.dp, bottom = 4.dp),
    )
}

/** Чипы как в YouTube: высота 32dp, скругление 8dp, выбранный — инвертированный. */
@Composable
fun <T> ChipsRow(options: List<T>, selected: T, label: @Composable (T) -> String, onSelect: (T) -> Unit, modifier: Modifier = Modifier) {
    val colors = TubeTheme.colors
    LazyRow(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(horizontal = 12.dp),
    ) {
        items(options.size) { i ->
            val option = options[i]
            val active = option == selected
            val bg by animateColorAsState(if (active) colors.textPrimary else colors.chip, label = "chip")
            val fg by animateColorAsState(if (active) colors.background else colors.textPrimary, label = "chipText")
            Box(
                Modifier
                    .height(32.dp)
                    .bouncyClickable(pressedScale = 0.94f) { onSelect(option) }
                    .background(bg, RoundedCornerShape(8.dp))
                    .padding(horizontal = 12.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(label(option), style = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Medium), color = fg)
            }
        }
    }
}

/** Ряд кнопок с переносом, от левого края. */
@Composable
fun CenteredRow(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    androidx.compose.foundation.layout.FlowRow(
        modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) { content() }
}

@Composable
fun Avatar(url: String?, size: Dp, modifier: Modifier = Modifier) = NetImage(url, modifier.size(size).clip(CircleShape))

/** Маленькая надпись-пилюля (качество, «Эфир»). */
@Composable
fun Pill(text: String, modifier: Modifier = Modifier, color: Color = Color.Black.copy(alpha = 0.55f), textColor: Color = Color.White) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = textColor,
        modifier = modifier.background(color, Shapes.Pill).padding(horizontal = 10.dp, vertical = 4.dp),
    )
}
