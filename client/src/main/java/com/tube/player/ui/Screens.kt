package com.tube.player.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.background
import com.tube.ui.theme.Shapes
import androidx.compose.runtime.getValue
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.tube.player.data.HistoryEntry
import com.tube.player.data.Subscription
import com.tube.player.youtube.ChannelItem
import com.tube.player.youtube.FeedItem
import com.tube.player.youtube.PlaylistItem
import com.tube.player.youtube.SearchFilter
import com.tube.ui.components.PillButton
import com.tube.ui.components.bouncyClickable
import com.tube.ui.i18n.LocalLanguage
import com.tube.ui.i18n.tr
import com.tube.ui.theme.TubeIcons
import com.tube.ui.theme.TubeTheme

@Composable
fun homeSectionLabel(section: HomeSection): String = when (section) {
    HomeSection.ForYou -> tr("Для вас", "For you")
    HomeSection.Music -> tr("Музыка", "Music")
    HomeSection.Gaming -> tr("Игры", "Gaming")
    HomeSection.Movies -> tr("Фильмы", "Movies")
    HomeSection.Podcasts -> tr("Подкасты", "Podcasts")
    HomeSection.Live -> tr("Эфиры", "Live")
}

/** Главная: разделы-чипы по центру и сетка видео. */
@Composable
fun HomeScreen(
    section: HomeSection,
    state: FeedState,
    onSection: (HomeSection) -> Unit,
    onOpen: (FeedItem) -> Unit,
    onMore: () -> Unit,
    onRetry: () -> Unit,
    padding: PaddingValues,
    hideShorts: Boolean,
    reselect: Int = 0,
    banner: @Composable () -> Unit = {},
) {
    FeedGrid(
        state, onOpen, onMore, onRetry,
        contentPadding = gridPadding(padding),
        hideShorts = hideShorts,
        empty = tr("Здесь пока пусто", "Nothing here yet"),
        reselect = reselect,
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) { banner() }
        item(span = { GridItemSpan(maxLineSpan) }) {
            ChipsRow(HomeSection.entries, section, { homeSectionLabel(it) }, onSection, Modifier.padding(bottom = 4.dp))
        }
    }
}

@Composable
fun searchFilterLabel(filter: SearchFilter): String = when (filter) {
    SearchFilter.All -> tr("Всё", "All")
    SearchFilter.Videos -> tr("Видео", "Videos")
    SearchFilter.Channels -> tr("Каналы", "Channels")
    SearchFilter.Playlists -> tr("Плейлисты", "Playlists")
}

/** Поиск: до запроса — подсказки и история, после — фильтры и результаты. */
@Composable
fun SearchScreen(
    query: String,
    filter: SearchFilter,
    results: FeedState?,
    suggestions: List<String>,
    history: List<String>,
    onSearch: (String) -> Unit,
    onRemoveHistory: (String) -> Unit,
    onFilter: (SearchFilter) -> Unit,
    onOpen: (FeedItem) -> Unit,
    onMore: () -> Unit,
    onRetry: () -> Unit,
    padding: PaddingValues,
    hideShorts: Boolean,
) {
    val showResults = results != null && suggestions.isEmpty()
    if (showResults) {
        FeedGrid(results!!, onOpen, onMore, onRetry, gridPadding(padding), hideShorts = hideShorts, empty = tr("Ничего не нашлось", "Nothing found")) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                ChipsRow(SearchFilter.entries, filter, { searchFilterLabel(it) }, onFilter, Modifier.padding(bottom = 4.dp))
            }
        }
        return
    }
    val words = suggestions.ifEmpty { history }
    LazyVerticalGrid(
        columns = GridCells.Fixed(1),
        contentPadding = edgeToEdge(gridPadding(padding)),
        modifier = Modifier.fillMaxSize(),
    ) {
        if (words.isEmpty()) {
            item { Message(tr("Что посмотрим?", "What shall we watch?"), tr("Видео, каналы и плейлисты YouTube", "YouTube videos, channels and playlists"), null, null) }
        } else {
            if (suggestions.isEmpty()) item { SectionTitle(tr("Недавно искали", "Recent searches"), Modifier.padding(horizontal = 16.dp)) }
            items(words.size, key = { words[it] }) { i ->
                val word = words[i]
                Row(
                    Modifier
                        .animateItem(fadeInSpec = Anim.fade(), placementSpec = Anim.move(), fadeOutSpec = Anim.out())
                        .fillMaxWidth()
                        .bouncyClickable(pressedScale = 0.99f) { onSearch(word) }
                        .padding(start = 16.dp, end = 4.dp)
                        .height(52.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(if (suggestions.isEmpty()) TubeIcons.History else TubeIcons.Search, null, tint = TubeTheme.colors.textPrimary, modifier = Modifier.size(24.dp))
                    Spacer(Modifier.size(20.dp))
                    Text(word, style = MaterialTheme.typography.bodyLarge, color = TubeTheme.colors.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    if (suggestions.isEmpty()) {
                        Spacer(Modifier.size(12.dp))
                        BarIcon(TubeIcons.Close, tr("Удалить", "Remove"), { onRemoveHistory(word) }, tint = TubeTheme.colors.textSecondary)
                    }
                }
            }
        }
    }
}

/** Подписки: каналы лентой сверху, ниже — свежие видео. */
@Composable
fun SubscriptionsScreen(
    subscriptions: List<Subscription>,
    state: FeedState,
    signedIn: Boolean,
    onChannel: (ChannelItem) -> Unit,
    onOpen: (FeedItem) -> Unit,
    onRetry: () -> Unit,
    onSignIn: () -> Unit,
    padding: PaddingValues,
    hideShorts: Boolean,
    reselect: Int = 0,
) {
    if (subscriptions.isEmpty() && !signedIn) {
        Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Message(
                    tr("Подписок пока нет", "No subscriptions yet"),
                    tr("Подпишитесь на канал на странице видео — или войдите и перенесите подписки аккаунта.", "Subscribe on a video page — or sign in and import your account's subscriptions."),
                    null, null,
                )
                PillButton(tr("Войти в Google", "Sign in with Google"), onClick = onSignIn, icon = TubeIcons.Account)
            }
        }
        return
    }
    FeedGrid(state, onOpen, {}, onRetry, gridPadding(padding), hideShorts = hideShorts, empty = tr("Новых видео нет", "No new videos"), reselect = reselect) {
        if (subscriptions.isNotEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                androidx.compose.foundation.lazy.LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    items(subscriptions.size, key = { subscriptions[it].channel.url }) { i ->
                        val c = subscriptions[i].channel
                        Column(
                            Modifier.widthIn(max = 84.dp).bouncyClickable { onChannel(c) },
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Avatar(c.avatar, 56.dp)
                            Spacer(Modifier.height(4.dp))
                            Text(c.name, style = MaterialTheme.typography.bodyMedium, color = TubeTheme.colors.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
                        }
                    }
                }
            }
        }
    }
}

/** Библиотека: продолжить просмотр и история. */
@Composable
fun LibraryScreen(
    history: List<HistoryEntry>,
    onOpen: (FeedItem) -> Unit,
    onRemove: (String) -> Unit,
    onClear: () -> Unit,
    padding: PaddingValues,
    liked: List<com.tube.player.youtube.VideoItem> = emptyList(),
) {
    if (history.isEmpty() && liked.isEmpty()) {
        Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
            Message(tr("История пуста", "History is empty"), tr("Здесь появятся видео, которые вы смотрели", "Videos you watch will appear here"), null, null)
        }
        return
    }
    val resume = history.filter { it.positionMs > 10_000 && !it.finished }.take(6)
    androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxSize()) {
        val phone = maxWidth < 600.dp
        androidx.compose.runtime.CompositionLocalProvider(
            LocalThumbShape provides if (phone) androidx.compose.ui.graphics.RectangleShape else ThumbShape,
            LocalCardInset provides if (phone) 12.dp else 0.dp,
        ) {
            LazyVerticalGrid(
                columns = if (phone) GridCells.Fixed(1) else GridCells.Adaptive(GridMinCell),
                contentPadding = if (phone) edgeToEdge(gridPadding(padding)) else gridPadding(padding),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                val inset = if (phone) 12.dp else 0.dp
                if (resume.isNotEmpty()) {
                    item(span = { GridItemSpan(maxLineSpan) }) { SectionTitle(tr("Продолжить просмотр", "Continue watching"), Modifier.padding(horizontal = inset)) }
                    historyItems(resume, "c", onOpen, onRemove)
                }
                if (liked.isNotEmpty()) {
                    item(span = { GridItemSpan(maxLineSpan) }) { SectionTitle(tr("Понравившиеся", "Liked videos"), Modifier.padding(horizontal = inset)) }
                    items(liked.size.coerceAtMost(12), key = { "l" + liked[it].id }) { i -> VideoCard(liked[i], onClick = { onOpen(liked[i]) }) }
                }
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Row(Modifier.fillMaxWidth().padding(start = inset), verticalAlignment = Alignment.CenterVertically) {
                        SectionTitle(tr("История", "History"), Modifier.weight(1f))
                        TextAction(tr("Очистить", "Clear"), onClear)
                    }
                }
                historyItems(history, "h", onOpen, onRemove)
            }
        }
    }
}

/** Текстовая кнопка цвета акцента — как «Все» / «Очистить» в YouTube. */
@Composable
fun TextAction(text: String, onClick: () -> Unit) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = TubeTheme.colors.accent,
        modifier = Modifier.bouncyClickable(pressedScale = 0.92f, onClick = onClick).padding(horizontal = 12.dp, vertical = 10.dp),
    )
}

private fun LazyGridScope.historyItems(list: List<HistoryEntry>, prefix: String, onOpen: (FeedItem) -> Unit, onRemove: (String) -> Unit) {
    items(list.size, key = { prefix + list[it].video.id }) { i ->
        val entry = list[i]
        val progress = if (entry.durationMs > 0) entry.positionMs.toFloat() / entry.durationMs else 0f
        VideoCard(entry.video, onClick = { onOpen(entry.video) }, progress = progress, onLongClick = { onRemove(entry.video.id) })
    }
}

/** Канал: баннер, аватар, подписка и видео. */
@Composable
fun ChannelScreen(
    name: String,
    header: ChannelItem?,
    banner: String?,
    state: FeedState,
    subscribed: Boolean,
    onSubscribe: (ChannelItem) -> Unit,
    onOpen: (FeedItem) -> Unit,
    onMore: () -> Unit,
    onRetry: () -> Unit,
    padding: PaddingValues,
    hideShorts: Boolean,
) {
    val lang = LocalLanguage.current
    FeedGrid(state, onOpen, onMore, onRetry, gridPadding(padding), hideShorts = hideShorts, empty = tr("Видео нет", "No videos")) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            Column(Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
                if (banner != null) {
                    NetImage(banner, Modifier.fillMaxWidth().aspectRatio(6f).padding(horizontal = 12.dp).clip(RoundedCornerShape(12.dp)))
                    Spacer(Modifier.height(16.dp))
                }
                Row(Modifier.padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Avatar(header?.avatar, 72.dp)
                    Spacer(Modifier.width(16.dp))
                    Column(Modifier.weight(1f)) {
                        Text(header?.name ?: name, style = MaterialTheme.typography.headlineSmall, color = TubeTheme.colors.textPrimary, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        header?.subscribers?.let { Format.subscribers(it, lang) }?.takeIf { it.isNotEmpty() }?.let {
                            Text(it, style = MaterialTheme.typography.bodyMedium, color = TubeTheme.colors.textSecondary)
                        }
                    }
                }
                header?.description?.takeIf { it.isNotBlank() }?.let {
                    Text(it, style = MaterialTheme.typography.bodyMedium, color = TubeTheme.colors.textSecondary, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 12.dp).padding(top = 12.dp))
                }
                if (header != null) {
                    Spacer(Modifier.height(12.dp))
                    Box(Modifier.padding(horizontal = 12.dp).fillMaxWidth()) { SubscribeWide(subscribed) { onSubscribe(header) } }
                }
            }
        }
    }
}

/** «Подписаться» во всю ширину — как на странице канала YouTube. */
@Composable
private fun SubscribeWide(subscribed: Boolean, onClick: () -> Unit) {
    val colors = TubeTheme.colors
    val bg by androidx.compose.animation.animateColorAsState(if (subscribed) colors.chip else colors.textPrimary, Anim.fade(), label = "subBg")
    val fg by androidx.compose.animation.animateColorAsState(if (subscribed) colors.textPrimary else colors.background, Anim.fade(), label = "subFg")
    Box(
        Modifier.fillMaxWidth().height(36.dp).bouncyClickable(pressedScale = 0.97f, onClick = onClick).background(bg, Shapes.Pill),
        contentAlignment = Alignment.Center,
    ) {
        Text(if (subscribed) tr("Вы подписаны", "Subscribed") else tr("Подписаться", "Subscribe"), style = MaterialTheme.typography.labelLarge, color = fg)
    }
}

@Composable
fun PlaylistScreen(name: String, header: PlaylistItem?, state: FeedState, onOpen: (FeedItem) -> Unit, onMore: () -> Unit, onRetry: () -> Unit, padding: PaddingValues) {
    FeedGrid(state, onOpen, onMore, onRetry, gridPadding(padding)) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp).padding(bottom = 8.dp)) {
                Text(header?.name ?: name, style = MaterialTheme.typography.headlineSmall, color = TubeTheme.colors.textPrimary)
                val meta = Format.meta(header?.uploader, header?.count?.takeIf { it >= 0 }?.let { "$it " + tr("видео", "videos") })
                if (meta.isNotEmpty()) Text(meta, style = MaterialTheme.typography.bodyMedium, color = TubeTheme.colors.textSecondary)
            }
        }
    }
}

/** Отступы сетки: под стеклянными панелями и по бокам. */
fun gridPadding(bars: PaddingValues) = PaddingValues(
    start = 16.dp + bars.calculateLeftPadding(androidx.compose.ui.unit.LayoutDirection.Ltr),
    end = 16.dp + bars.calculateRightPadding(androidx.compose.ui.unit.LayoutDirection.Ltr),
    top = bars.calculateTopPadding() + 8.dp,
    bottom = bars.calculateBottomPadding() + 16.dp,
)
