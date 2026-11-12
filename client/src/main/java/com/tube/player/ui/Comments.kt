package com.tube.player.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.fromHtml
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.tube.player.youtube.Comment
import com.tube.player.youtube.Repository
import com.tube.ui.components.bouncyClickable
import com.tube.ui.i18n.LocalLanguage
import com.tube.ui.i18n.tr
import com.tube.ui.theme.TubeIcons
import com.tube.ui.theme.TubeTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.schabi.newpipe.extractor.Page

/** Комментарии одного видео: страницы по мере прокрутки и ответы, раскрываемые под комментарием. */
@Stable
class CommentsModel(private val scope: CoroutineScope, private val repo: Repository, val url: String) {
    var items by mutableStateOf<List<Comment>>(emptyList())
        private set
    var count by mutableStateOf(-1)
        private set
    var loading by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var disabled by mutableStateOf(false)
        private set
    var end by mutableStateOf(false)
        private set
    private var next: Page? = null
    private var started = false

    /** Ответы по id комментария; null в [repliesLoading] — не грузятся. */
    val replies = mutableStateMapOf<String, List<Comment>>()
    val repliesLoading = mutableStateMapOf<String, Boolean>()

    fun ensure() {
        if (started) return
        started = true
        load(null)
    }

    fun retry() {
        error = null
        load(next)
    }

    fun more() {
        if (loading || end || error != null || next == null) return
        load(next)
    }

    private fun load(page: Page?) {
        loading = true
        scope.launch {
            try {
                val result = repo.comments(url, page)
                if (page == null) {
                    count = result.count
                    disabled = result.disabled
                }
                items = (items + result.items).distinctBy { it.id }
                next = result.next
                end = result.next == null || result.items.isEmpty()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = e.message ?: e.javaClass.simpleName
            } finally {
                loading = false
            }
        }
    }

    /** Показать или спрятать ответы. */
    fun toggleReplies(comment: Comment) {
        if (replies.containsKey(comment.id)) {
            replies.remove(comment.id)
            return
        }
        val page = comment.repliesPage ?: return
        repliesLoading[comment.id] = true
        scope.launch {
            val list = runCatching { repo.comments(url, page).items }.getOrDefault(emptyList())
            replies[comment.id] = list
            repliesLoading.remove(comment.id)
        }
    }

    companion object {
        /** Готовые комментарии — для превью и скриншотов. */
        fun preview(scope: CoroutineScope, repo: Repository, items: List<Comment>, count: Int) =
            CommentsModel(scope, repo, "").apply {
                started = true
                this.items = items
                this.count = count
                end = true
            }
    }
}

/** Карточка под описанием, как в YouTube: «Комментарии · 1,2 тыс.» и первый комментарий; нажатие — открыть все. */
@Composable
fun CommentsPreview(model: CommentsModel?, onOpen: () -> Unit) {
    val colors = TubeTheme.colors
    val lang = LocalLanguage.current
    LaunchedEffect(model) { model?.ensure() }
    if (model?.disabled == true) return
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .background(colors.chip, RoundedCornerShape(12.dp))
            .bouncyClickable(pressedScale = 0.99f, onClick = onOpen)
            .padding(12.dp)
            .animateContentSize(Anim.move()),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(tr("Комментарии", "Comments"), style = CardTitle, color = colors.textPrimary)
            val count = model?.count ?: -1
            if (count >= 0) {
                Spacer(Modifier.width(8.dp))
                Text(Format.compact(count.toLong(), lang), style = MaterialTheme.typography.bodyMedium, color = colors.textSecondary)
            }
            Spacer(Modifier.weight(1f))
            Icon(TubeIcons.ChevronDown, null, tint = colors.textSecondary, modifier = Modifier.size(20.dp))
        }
        val first = model?.items?.firstOrNull()
        if (first != null) {
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Avatar(first.avatar, 24.dp)
                Spacer(Modifier.width(10.dp))
                Text(first.plain, style = MaterialTheme.typography.bodyMedium, color = colors.textPrimary, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** Панель комментариев: выезжает снизу под плеером, закрывается крестиком, свайпом назад или жестом «назад». */
@Composable
fun CommentsPanel(model: CommentsModel, onClose: () -> Unit, modifier: Modifier = Modifier) {
    val colors = TubeTheme.colors
    val lang = LocalLanguage.current
    val list = rememberLazyListState()
    LaunchedEffect(model) { model.ensure() }
    val nearEnd by remember {
        derivedStateOf {
            val last = list.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            last >= list.layoutInfo.totalItemsCount - 5
        }
    }
    LaunchedEffect(nearEnd, model.items.size) { if (nearEnd) model.more() }
    Column(modifier.fillMaxSize().background(colors.background)) {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(tr("Комментарии", "Comments"), style = MaterialTheme.typography.titleLarge, color = colors.textPrimary)
            if (model.count >= 0) {
                Spacer(Modifier.width(8.dp))
                Text(Format.compact(model.count.toLong(), lang), style = MaterialTheme.typography.bodyLarge, color = colors.textSecondary)
            }
            Spacer(Modifier.weight(1f))
            BarIcon(TubeIcons.Close, tr("Закрыть", "Close"), onClose)
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(commentDivider()))
        LazyColumn(state = list, modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
            when {
                model.disabled -> item { Message(tr("Комментарии отключены", "Comments are turned off"), null, null, null) }
                model.items.isEmpty() && model.loading -> items(4) { CommentSkeleton() }
                model.items.isEmpty() && model.error != null -> item {
                    Message(tr("Не удалось загрузить", "Couldn't load"), model.error, tr("Повторить", "Retry"), model::retry)
                }
                model.items.isEmpty() -> item { Message(tr("Комментариев пока нет", "No comments yet"), null, null, null) }
                else -> {
                    model.items.forEachIndexed { index, comment ->
                        item(key = comment.id) {
                            Column(Modifier.animateItem(fadeInSpec = Anim.fade(), placementSpec = Anim.move(), fadeOutSpec = Anim.out())) {
                                CommentRow(comment, reply = false, onReplies = { model.toggleReplies(comment) }, repliesShown = model.replies.containsKey(comment.id), repliesLoading = model.repliesLoading[comment.id] == true, divider = index > 0)
                            }
                        }
                        model.replies[comment.id]?.forEach { reply ->
                            item(key = comment.id + "/" + reply.id) {
                                Box(Modifier.animateItem(fadeInSpec = Anim.fade(), placementSpec = Anim.move(), fadeOutSpec = Anim.out())) {
                                    CommentRow(reply, reply = true, onReplies = {}, repliesShown = false, repliesLoading = false)
                                }
                            }
                        }
                    }
                    if (model.loading) item { Loading(Modifier.padding(16.dp)) }
                }
            }
        }
    }
}

/**
 * Комментарий как в YouTube: аватар слева, имя автора (выделено) и время, текст (нажатие раскрывает), лайки и ответы.
 * Между комментариями — тонкий разделитель, ответы сдвинуты и отмечены линией слева, — чтобы авторы не сливались.
 */
/**
 * Цвет разделителя между комментариями. На чёрном фоне AMOLED обычный полупрозрачный серый почти не виден —
 * там линия заметно светлее, чтобы авторы не сливались.
 */
@Composable
private fun commentDivider(): Color {
    val colors = TubeTheme.colors
    return if (colors.background == Color.Black) Color.White.copy(alpha = 0.2f) else colors.chip
}

@Composable
private fun CommentRow(comment: Comment, reply: Boolean, onReplies: () -> Unit, repliesShown: Boolean, repliesLoading: Boolean, divider: Boolean = false) {
    val colors = TubeTheme.colors
    val lang = LocalLanguage.current
    var expanded by remember { mutableStateOf(false) }
    val accent = colors.accent
    val text = remember(comment.text, comment.html) {
        if (comment.html) AnnotatedString.fromHtml(comment.text, linkStyles = TextLinkStyles(SpanStyle(color = accent))) else AnnotatedString(comment.text)
    }
    Column(Modifier.fillMaxWidth()) {
        if (!reply && divider) Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp).height(1.dp).background(commentDivider()))
        Row(
            Modifier
                .fillMaxWidth()
                .bouncyClickable(pressedScale = 0.995f) { expanded = !expanded }
                .padding(start = if (reply) 40.dp else 16.dp, end = 16.dp, top = if (reply) 10.dp else 16.dp, bottom = if (reply) 6.dp else 12.dp),
        ) {
            if (reply) {
                // Линия ветки ответов.
                Box(Modifier.padding(end = 12.dp).width(2.dp).height(40.dp).background(colors.accent.copy(alpha = 0.35f), RoundedCornerShape(1.dp)))
            }
            Avatar(comment.avatar, if (reply) 26.dp else 36.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f).animateContentSize(Anim.move())) {
                if (comment.pinned) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 2.dp)) {
                        Text("📌", style = MaterialTheme.typography.bodyMedium)
                        Spacer(Modifier.width(4.dp))
                        Text(tr("Закреплено автором", "Pinned by creator"), style = MaterialTheme.typography.bodyMedium, color = colors.textSecondary)
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        comment.author,
                        style = MaterialTheme.typography.labelLarge,
                        color = colors.textPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .weight(1f, fill = false)
                            .then(if (comment.byOwner) Modifier.background(colors.chip, RoundedCornerShape(10.dp)).padding(horizontal = 8.dp, vertical = 1.dp) else Modifier),
                    )
                    comment.date?.let {
                        Spacer(Modifier.width(8.dp))
                        Text(Format.relative(it, comment.dateAt, lang), style = MaterialTheme.typography.bodyMedium, color = colors.textSecondary, maxLines = 1)
                    }
                }
                Text(
                    text,
                    style = MaterialTheme.typography.bodyLarge,
                    color = colors.textPrimary,
                    maxLines = if (expanded) Int.MAX_VALUE else 4,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 4.dp),
                )
                Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Icon(TubeIcons.ThumbUp, null, tint = colors.textSecondary, modifier = Modifier.size(16.dp))
                    if (comment.likes > 0) Text(comment.likesText ?: Format.compact(comment.likes.toLong(), lang), style = MaterialTheme.typography.bodyMedium, color = colors.textSecondary)
                    if (comment.hearted) {
                        Spacer(Modifier.width(6.dp))
                        Text("♥", style = MaterialTheme.typography.bodyMedium, color = colors.danger)
                    }
                }
                if (!reply && comment.replies > 0) {
                    Row(
                        Modifier.padding(top = 6.dp).bouncyClickable(pressedScale = 0.95f, onClick = onReplies).padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        val rotation by androidx.compose.animation.core.animateFloatAsState(if (repliesShown) 180f else 0f, Anim.move(), label = "replies")
                        Icon(TubeIcons.ChevronDown, null, tint = accent, modifier = Modifier.size(18.dp).graphicsLayer { rotationZ = rotation })
                        Spacer(Modifier.width(4.dp))
                        Text(
                            when {
                                repliesLoading -> tr("Загрузка…", "Loading…")
                                repliesShown -> tr("Скрыть ответы", "Hide replies")
                                else -> "${comment.replies} " + lang.plural(comment.replies, "ответ", "ответа", "ответов", "reply", "replies")
                            },
                            style = MaterialTheme.typography.labelLarge,
                            color = accent,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CommentSkeleton() {
    val color = TubeTheme.colors.chip
    Row(Modifier.fillMaxWidth().padding(16.dp)) {
        Box(Modifier.size(32.dp).background(color, RoundedCornerShape(50)))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Box(Modifier.fillMaxWidth(0.4f).height(12.dp).background(color, RoundedCornerShape(6.dp)))
            Spacer(Modifier.height(8.dp))
            Box(Modifier.fillMaxWidth(0.9f).height(14.dp).background(color, RoundedCornerShape(6.dp)))
            Spacer(Modifier.height(6.dp))
            Box(Modifier.fillMaxWidth(0.7f).height(14.dp).background(color, RoundedCornerShape(6.dp)))
        }
    }
}
