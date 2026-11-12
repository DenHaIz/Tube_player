package com.tube.player

import com.tube.player.youtube.Comment
import com.tube.player.youtube.VideoItem
import com.tube.player.youtube.sharpAvatar
import com.tube.player.youtube.thumbnailCandidates
import org.junit.Assert.assertEquals
import org.junit.Test

class MediaUrlsTest {

    @Test
    fun hdWebpThumbnailFirstThenOriginal() {
        val v = VideoItem("https://www.youtube.com/watch?v=dQw4w9WgXcQ", "t", thumbnail = "https://i.ytimg.com/vi/dQw4w9WgXcQ/hqdefault.jpg")
        assertEquals(
            listOf("https://i.ytimg.com/vi_webp/dQw4w9WgXcQ/hq720.webp", "https://i.ytimg.com/vi/dQw4w9WgXcQ/hqdefault.jpg"),
            thumbnailCandidates(v),
        )
        // У эфира превью обновляется — берём то, что отдал YouTube.
        assertEquals(listOf("x.jpg"), thumbnailCandidates(v.copy(live = true, thumbnail = "x.jpg")))
    }

    @Test
    fun avatarsAreRequestedLarger() {
        assertEquals("https://yt3.ggpht.com/abc=s176-c-k-c0x00ffffff-no-rj", sharpAvatar("https://yt3.ggpht.com/abc=s88-c-k-c0x00ffffff-no-rj"))
        assertEquals("https://example.com/a.png", sharpAvatar("https://example.com/a.png"))
    }

    @Test
    fun commentPlainText() {
        val c = Comment("1", "a", text = "Hi<br>there &amp; <a href=\"x\">link</a>", html = true)
        assertEquals("Hi there & link", c.plain)
    }
}
