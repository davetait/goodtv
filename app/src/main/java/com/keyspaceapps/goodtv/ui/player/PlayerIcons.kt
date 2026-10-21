package com.keyspaceapps.goodtv.ui.player

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/** The player's few icons, drawn on a 24-unit grid; tinted by whoever shows them. */
object PlayerIcons {
    private fun icon(name: String, block: ImageVector.Builder.() -> Unit) =
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply(block).build()

    private val fill = SolidColor(Color.White)

    private fun ImageVector.Builder.line(block: androidx.compose.ui.graphics.vector.PathBuilder.() -> Unit) =
        path(stroke = fill, strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round, pathBuilder = block)

    val Play: ImageVector = icon("Play") {
        path(fill = fill, strokeLineJoin = StrokeJoin.Round) {
            moveTo(8f, 5.5f); lineTo(8f, 18.5f); lineTo(18.5f, 12f); close()
        }
    }

    val Pause: ImageVector = icon("Pause") {
        path(fill = fill) {
            moveTo(6.5f, 5f); lineTo(10f, 5f); lineTo(10f, 19f); lineTo(6.5f, 19f); close()
            moveTo(14f, 5f); lineTo(17.5f, 5f); lineTo(17.5f, 19f); lineTo(14f, 19f); close()
        }
    }

    /** A caption box with two lines of text. */
    val Subtitles: ImageVector = icon("Subtitles") {
        line {
            moveTo(5f, 5f); lineTo(19f, 5f)
            arcTo(2f, 2f, 0f, false, true, 21f, 7f); lineTo(21f, 17f)
            arcTo(2f, 2f, 0f, false, true, 19f, 19f); lineTo(5f, 19f)
            arcTo(2f, 2f, 0f, false, true, 3f, 17f); lineTo(3f, 7f)
            arcTo(2f, 2f, 0f, false, true, 5f, 5f); close()
        }
        line { moveTo(7f, 12.5f); lineTo(9f, 12.5f); moveTo(12f, 12.5f); lineTo(17f, 12.5f) }
        line { moveTo(7f, 15.5f); lineTo(13f, 15.5f); moveTo(16f, 15.5f); lineTo(17f, 15.5f) }
    }

    /** A speaker with two sound waves. */
    val Audio: ImageVector = icon("Audio") {
        path(fill = fill, strokeLineJoin = StrokeJoin.Round) {
            moveTo(4f, 9.5f); lineTo(7.5f, 9.5f); lineTo(12f, 5.5f); lineTo(12f, 18.5f); lineTo(7.5f, 14.5f); lineTo(4f, 14.5f); close()
        }
        line { moveTo(15.5f, 9.5f); arcTo(3.5f, 3.5f, 0f, false, true, 15.5f, 14.5f) }
        line { moveTo(18f, 6.5f); arcTo(7.5f, 7.5f, 0f, false, true, 18f, 17.5f) }
    }

    val Check: ImageVector = icon("Check") {
        line { moveTo(5f, 12.5f); lineTo(9.5f, 17f); lineTo(19f, 7.5f) }
    }
}
