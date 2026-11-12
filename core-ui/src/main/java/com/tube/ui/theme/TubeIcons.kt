package com.tube.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/** Собственный минималистичный набор линейных иконок (скруглённые концы, толщина 2) — как у Woody. */
object TubeIcons {
    val Power by lazy { icon("Power", "M12 3v8M7.05 6.05a7 7 0 1 0 9.9 0") }
    val Play by lazy { icon("Play", "M8 5.5v13l10.5-6.5z") }
    val Apps by lazy {
        icon(
            "Apps",
            "M6 4h3a2 2 0 0 1 2 2v3a2 2 0 0 1-2 2H6a2 2 0 0 1-2-2V6a2 2 0 0 1 2-2z" +
                "M15 4h3a2 2 0 0 1 2 2v3a2 2 0 0 1-2 2h-3a2 2 0 0 1-2-2V6a2 2 0 0 1 2-2z" +
                "M6 13h3a2 2 0 0 1 2 2v3a2 2 0 0 1-2 2H6a2 2 0 0 1-2-2v-3a2 2 0 0 1 2-2z" +
                "M15 13h3a2 2 0 0 1 2 2v3a2 2 0 0 1-2 2h-3a2 2 0 0 1-2-2v-3a2 2 0 0 1 2-2z",
        )
    }
    val Sliders by lazy { icon("Sliders", "M4 7h9M17 7h3M4 17h3M11 17h9M15 5v4M9 15v4") }
    val Bolt by lazy { icon("Bolt", "M13 3L5 13.5h6L10 21l8-10.5h-6z") }
    val Search by lazy { icon("Search", "M11 4a7 7 0 1 0 0 14a7 7 0 1 0 0-14zM16.5 16.5L20 20") }
    val Check by lazy { icon("Check", "M5 12.5l4.5 4.5L19 7.5") }
    val Close by lazy { icon("Close", "M6 6l12 12M18 6L6 18") }
    val ChevronRight by lazy { icon("ChevronRight", "M9 6l6 6-6 6") }
    val ChevronDown by lazy { icon("ChevronDown", "M6 9l6 6 6-6") }
    val Eye by lazy { icon("Eye", "M2.5 12s3.5-6.5 9.5-6.5S21.5 12 21.5 12s-3.5 6.5-9.5 6.5S2.5 12 2.5 12zM12 9.5a2.5 2.5 0 1 0 0 5a2.5 2.5 0 1 0 0-5z") }
    val Shield by lazy { icon("Shield", "M12 3l7 3v5.5c0 4.5-3 8-7 9.5c-4-1.5-7-5-7-9.5V6z") }
    val Battery by lazy { icon("Battery", "M5 7h12a2 2 0 0 1 2 2v6a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V9a2 2 0 0 1 2-2zM21 10.5v3") }
    val Edit by lazy { icon("Edit", "M4 20h4L19 9l-4-4L4 16zM13.5 6.5l4 4") }
    val Info by lazy { icon("Info", "M12 3a9 9 0 1 0 0 18a9 9 0 1 0 0-18zM12 11v5M12 8h.01") }
    val Terminal by lazy { icon("Terminal", "M5 5h14a2 2 0 0 1 2 2v10a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V7a2 2 0 0 1 2-2zM7 10l3 2-3 2M12 15h5") }

    // Плеер (Tube Player)
    val Home by lazy { icon("Home", "M4 11l8-7 8 7v8a1 1 0 0 1-1 1h-4v-6H9v6H5a1 1 0 0 1-1-1z") }
    val Subscriptions by lazy { icon("Subscriptions", "M5 8h14a2 2 0 0 1 2 2v8a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-8a2 2 0 0 1 2-2zM6 5h12M10.5 11.5v5l4-2.5z") }
    val Library by lazy { icon("Library", "M5 4v16M9 4v16M13.5 5l5 14") }
    val History by lazy { icon("History", "M12 3a9 9 0 1 0 0 18a9 9 0 1 0 0-18zM12 7v5l3 2") }
    val Pause by lazy { icon("Pause", "M8 5v14M16 5v14") }
    val Fullscreen by lazy { icon("Fullscreen", "M4 9V4h5M20 9V4h-5M4 15v5h5M20 15v5h-5") }
    val FullscreenExit by lazy { icon("FullscreenExit", "M9 4v5H4M15 4v5h5M9 20v-5H4M15 20v-5h5") }
    val Pip by lazy { icon("Pip", "M4 5h16a1 1 0 0 1 1 1v12a1 1 0 0 1-1 1H4a1 1 0 0 1-1-1V6a1 1 0 0 1 1-1zM12 12h6v4h-6z") }
    val Popup by lazy { icon("Popup", "M9 3h11a1 1 0 0 1 1 1v8a1 1 0 0 1-1 1H9a1 1 0 0 1-1-1V4a1 1 0 0 1 1-1zM4 8v11a1 1 0 0 0 1 1h11") }
    val Headphones by lazy { icon("Headphones", "M4 15v-3a8 8 0 0 1 16 0v3M4 15h3v5H5a1 1 0 0 1-1-1zM20 15h-3v5h2a1 1 0 0 0 1-1z") }
    val Skip by lazy { icon("Skip", "M5 5v14l9-7zM18 5v14") }
    val Rewind by lazy { icon("Rewind", "M11 7l-6 5 6 5zM19 7l-6 5 6 5z") }
    val Forward by lazy { icon("Forward", "M13 7l6 5-6 5zM5 7l6 5-6 5z") }
    val Account by lazy { icon("Account", "M12 4a4 4 0 1 0 0 8a4 4 0 1 0 0-8zM4 20c1.5-3.5 4.5-5 8-5s6.5 1.5 8 5") }
    val Logout by lazy { icon("Logout", "M15 4h3a2 2 0 0 1 2 2v12a2 2 0 0 1-2 2h-3M10 8l-4 4 4 4M6 12h10") }
    val ThumbUp by lazy { icon("ThumbUp", "M7 10v10H4V10zM7 10l4-7c1.5 0 2.5 1 2.2 2.6L12.5 9H19a2 2 0 0 1 2 2.3l-1.2 6.6A2.5 2.5 0 0 1 17.3 20H7") }
    val ThumbDown by lazy { icon("ThumbDown", "M7 14V4H4v10zM7 14l4 7c1.5 0 2.5-1 2.2-2.6L12.5 15H19a2 2 0 0 0 2-2.3l-1.2-6.6A2.5 2.5 0 0 0 17.3 4H7") }
    val Share by lazy { icon("Share", "M12 15V3M7 8l5-5 5 5M5 13v6a2 2 0 0 0 2 2h10a2 2 0 0 0 2-2v-6") }
    val Back by lazy { icon("Back", "M15 6l-6 6 6 6") }
    val Plus by lazy { icon("Plus", "M12 5v14M5 12h14") }
    val Speed by lazy { icon("Speed", "M12 13l4-4M4.5 18a9 9 0 1 1 15 0") }
    val Fire by lazy { icon("Fire", "M12 3c1 4 5 5.5 5 10a5 5 0 0 1-10 0c0-2.5 1.5-3.5 2-5.5c1 1.5 2 2 3 2c0-2.5-.5-4.5 0-6.5z") }
    val Music by lazy { icon("Music", "M9 18V5l11-2v13M9 18a3 3 0 1 1-6 0a3 3 0 1 1 6 0zM20 16a3 3 0 1 1-6 0a3 3 0 1 1 6 0z") }
    val Gamepad by lazy { icon("Gamepad", "M7 8h10a4 4 0 0 1 4 4v1a4 4 0 0 1-7 2.6h-4A4 4 0 0 1 3 13v-1a4 4 0 0 1 4-4zM8 11v3M6.5 12.5h3M15.5 12h.01M17.5 14h.01") }
    val Film by lazy { icon("Film", "M5 4h14a1 1 0 0 1 1 1v14a1 1 0 0 1-1 1H5a1 1 0 0 1-1-1V5a1 1 0 0 1 1-1zM8 4v16M16 4v16M4 9h4M4 15h4M16 9h4M16 15h4") }
    val Mic by lazy { icon("Mic", "M12 3a3 3 0 0 1 3 3v6a3 3 0 0 1-6 0V6a3 3 0 0 1 3-3zM5 11a7 7 0 0 0 14 0M12 18v3") }
    val Live by lazy { icon("Live", "M12 10a2 2 0 1 0 0 4a2 2 0 1 0 0-4zM7.8 7.8a6 6 0 0 0 0 8.4M16.2 7.8a6 6 0 0 1 0 8.4M5 5a10 10 0 0 0 0 14M19 5a10 10 0 0 1 0 14") }
    val Gear by lazy {
        icon(
            "Gear",
            "M12 9a3 3 0 1 0 0 6a3 3 0 1 0 0-6zM19.4 15a1.65 1.65 0 0 0 .33 1.82l.06.06a2 2 0 1 1-2.83 2.83l-.06-.06a1.65 1.65 0 0 0-1.82-.33 " +
                "1.65 1.65 0 0 0-1 1.51V21a2 2 0 1 1-4 0v-.09A1.65 1.65 0 0 0 9 19.4a1.65 1.65 0 0 0-1.82.33l-.06.06a2 2 0 1 1-2.83-2.83l.06-.06" +
                "A1.65 1.65 0 0 0 4.68 15a1.65 1.65 0 0 0-1.51-1H3a2 2 0 1 1 0-4h.09A1.65 1.65 0 0 0 4.6 9a1.65 1.65 0 0 0-.33-1.82l-.06-.06" +
                "a2 2 0 1 1 2.83-2.83l.06.06A1.65 1.65 0 0 0 9 4.68a1.65 1.65 0 0 0 1-1.51V3a2 2 0 1 1 4 0v.09a1.65 1.65 0 0 0 1 1.51 1.65 1.65 0 0 0 " +
                "1.82-.33l.06-.06a2 2 0 1 1 2.83 2.83l-.06.06A1.65 1.65 0 0 0 19.4 9a1.65 1.65 0 0 0 1.51 1H21a2 2 0 1 1 0 4h-.09a1.65 1.65 0 0 0-1.51 1z",
        )
    }
    val Expand by lazy { icon("Expand", "M15 3h6v6M9 21H3v-6M21 3l-7 7M3 21l7-7") }
    val Shrink by lazy { icon("Shrink", "M4 14h6v6M20 10h-6V4M14 10l7-7M3 21l7-7") }
    val Comment by lazy { icon("Comment", "M5 4h14a2 2 0 0 1 2 2v9a2 2 0 0 1-2 2h-8l-5 4v-4H5a2 2 0 0 1-2-2V6a2 2 0 0 1 2-2z") }
    val Bell by lazy { icon("Bell", "M6 16V11a6 6 0 0 1 12 0v5l2 2H4zM10 20.5a2 2 0 0 0 4 0") }
    val Trash by lazy { icon("Trash", "M4 7h16M9 7V4h6v3M6 7l1 13h10l1-13") }

    private fun icon(name: String, pathData: String): ImageVector =
        ImageVector.Builder(
            name = name,
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).addPath(
            pathData = addPathNodes(pathData),
            stroke = SolidColor(Color.Black),
            strokeLineWidth = 2f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
        ).build()
}
