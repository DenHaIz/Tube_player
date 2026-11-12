package com.tube.player.ui

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.tube.player.youtube.FeedItem
import com.tube.player.youtube.Paged
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.schabi.newpipe.extractor.Page

/** Список с подгрузкой: первая страница, «ещё» по прокрутке, ошибка с повтором. */
data class FeedState(
    val items: List<FeedItem> = emptyList(),
    val loading: Boolean = true,
    val loadingMore: Boolean = false,
    val error: String? = null,
    val end: Boolean = false,
)

@Stable
class Feed(
    private val scope: CoroutineScope,
    private val first: suspend () -> Paged<FeedItem>,
    private val more: (suspend (Page) -> Paged<FeedItem>)? = null,
) {
    var state by mutableStateOf(FeedState())
        private set
    private var next: Page? = null
    private var job: Job? = null
    private var started = false

    /** Загрузить первую страницу, если ещё не загружали. */
    fun ensure() {
        if (!started) refresh()
    }

    fun refresh() {
        started = true
        job?.cancel()
        state = state.copy(loading = true, error = null)
        job = scope.launch {
            try {
                val page = first()
                next = page.next
                state = FeedState(page.items.distinctBy { it.url }, loading = false, end = page.next == null || more == null)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                state = state.copy(loading = false, error = e.message ?: e.javaClass.simpleName)
            }
        }
    }

    fun loadMore() {
        val page = next ?: return
        val loader = more ?: return
        if (state.loading || state.loadingMore || state.end) return
        state = state.copy(loadingMore = true)
        job = scope.launch {
            try {
                val result = loader(page)
                next = result.next
                state = state.copy(
                    items = (state.items + result.items).distinctBy { it.url },
                    loadingMore = false,
                    end = result.next == null || result.items.isEmpty(),
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                state = state.copy(loadingMore = false, end = true)
            }
        }
    }

    companion object {
        /** Готовый список — для превью и скриншотов. */
        fun of(scope: CoroutineScope, items: List<FeedItem>) = Feed(scope, { Paged(items, null) }).apply {
            started = true
            state = FeedState(items, loading = false, end = true)
        }
    }
}
