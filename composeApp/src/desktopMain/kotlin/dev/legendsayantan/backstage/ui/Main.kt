package dev.legendsayantan.backstage.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import dev.legendsayantan.backstage.core.BackStageEngine
import dev.legendsayantan.backstage.core.platform.PlatformContext
import dev.legendsayantan.backstage.ui.theme.AccentPrimary

fun main() {
    val engine = BackStageEngine(PlatformContext())
    Runtime.getRuntime().addShutdownHook(Thread {
        engine.close()
    })

    application {
        val windowState = rememberWindowState(
            position = WindowPosition.Aligned(Alignment.Center),
            size = DpSize(1120.dp, 760.dp),
        )
        val appIcon = remember { BackStageIconPainter() }
        Window(
            onCloseRequest = {
                engine.close()
                exitApplication()
            },
            title = "BackStage",
            icon = appIcon,
            state = windowState,
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                BackStageApp(engine = engine)
            }
        }
    }
}

private class BackStageIconPainter : Painter() {
    override val intrinsicSize: Size = Size(64f, 64f)

    override fun DrawScope.onDraw() {
        drawBackStageEmblem()
    }
}

private fun DrawScope.drawBackStageEmblem() {
    val w = size.width
    val h = size.height

    // Outer architectural frame
    drawRoundRect(
        color = AccentPrimary,
        topLeft = Offset(w * 0.08f, h * 0.08f),
        size = Size(w * 0.84f, h * 0.84f),
        cornerRadius = CornerRadius(w * 0.16f, h * 0.16f),
        style = Stroke(width = w * 0.10f),
    )

    // Offset inner vault square
    drawRoundRect(
        color = AccentPrimary,
        topLeft = Offset(w * 0.30f, h * 0.30f),
        size = Size(w * 0.40f, h * 0.40f),
        cornerRadius = CornerRadius(w * 0.06f, h * 0.06f),
    )
}
