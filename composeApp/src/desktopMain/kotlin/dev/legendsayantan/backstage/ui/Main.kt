package dev.legendsayantan.backstage.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.window.WindowDraggableArea
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.WindowScope
import androidx.compose.ui.window.WindowState
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import dev.legendsayantan.backstage.core.BackStageEngine
import dev.legendsayantan.backstage.core.platform.PlatformContext
import dev.legendsayantan.backstage.ui.theme.AccentPrimary
import dev.legendsayantan.backstage.ui.theme.BgBase
import dev.legendsayantan.backstage.ui.theme.BgSurfaceHover
import dev.legendsayantan.backstage.ui.theme.BorderColor
import dev.legendsayantan.backstage.ui.theme.DangerColor
import dev.legendsayantan.backstage.ui.theme.TextMain
import dev.legendsayantan.backstage.ui.theme.TextMuted

fun main() {
    System.setProperty("skiko.renderApi.logging", "true")
    println("[BackStage] Starting BackStage Desktop application...")
    Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
        System.err.println("[BackStage ERROR] Uncaught exception in thread '${thread.name}':")
        throwable.printStackTrace()
    }

    val engine = BackStageEngine(PlatformContext())
    Runtime.getRuntime().addShutdownHook(Thread {
        println("[BackStage] Shutdown hook triggered, closing engine...")
        engine.close()
    })

    println("[BackStage] Initializing Compose application window...")
    application {
        val windowState = rememberWindowState(
            position = WindowPosition.Aligned(Alignment.Center),
            size = DpSize(1080.dp, 680.dp),
        )
        val appIcon = remember { BackStageIconPainter() }
        Window(
            onCloseRequest = {
                println("[BackStage] Window close requested.")
                engine.close()
                exitApplication()
            },
            title = "BackStage",
            icon = appIcon,
            undecorated = true,
            transparent = false,
            visible = true,
            state = windowState,
        ) {
            LaunchedEffect(window) {
                runCatching {
                    val stream = Thread.currentThread().contextClassLoader.getResourceAsStream("icon.png")
                    stream?.use {
                        val img = javax.imageio.ImageIO.read(it)
                        if (img != null) {
                            window.iconImages = listOf(img)
                        }
                    }
                }
            }
            val topmostResetTimer = remember(window) {
                javax.swing.Timer(1500) {
                    try {
                        window.isAlwaysOnTop = false
                    } catch (_: Throwable) {
                        // The window may already be disposed during application shutdown.
                    }
                }.apply {
                    isRepeats = false
                }
            }

            DisposableEffect(window) {
                onDispose {
                    topmostResetTimer.stop()
                    runCatching { window.isAlwaysOnTop = false }
                }
            }

            fun surfaceWindow() {
                java.awt.EventQueue.invokeLater {
                    try {
                        // Compose Desktop shows a Window on a later AWT tick. This callback can
                        // otherwise run first, making toFront() a no-op against a hidden frame.
                        window.isVisible = true
                        if (window.extendedState and java.awt.Frame.ICONIFIED != 0) {
                            window.extendedState = java.awt.Frame.NORMAL
                        }
                        window.isAlwaysOnTop = true
                        window.toFront()
                        window.requestFocus()
                        window.repaint()

                        // Keep the topmost state across at least one paint/activation cycle.
                        // Resetting it in this same event (the old behavior) races Windows' z-order
                        // processing and can leave the undecorated window behind the launcher.
                        topmostResetTimer.restart()
                        println(
                            "[BackStage] Window surfaced: visible=${window.isVisible}, " +
                                "showing=${window.isShowing}, bounds=${window.bounds}",
                        )
                    } catch (t: Throwable) {
                        System.err.println("[BackStage] Notice during surfaceWindow: ${t.message}")
                    }
                }
            }

            LaunchedEffect(Unit) {
                println("[BackStage] Window composition active, ensuring foreground...")
                surfaceWindow()
            }
            val isMaximized = windowState.placement == WindowPlacement.Maximized
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(BgBase)
                    .then(
                        if (!isMaximized) {
                            Modifier.border(1.dp, BorderColor)
                        } else {
                            Modifier
                        }
                    )
            ) {
                ModernTitleBar(
                    windowState = windowState,
                    onMinimize = { windowState.isMinimized = true },
                    onToggleMaximize = {
                        windowState.placement = if (windowState.placement == WindowPlacement.Maximized) {
                            WindowPlacement.Floating
                        } else {
                            WindowPlacement.Maximized
                        }
                    },
                    onClose = {
                        engine.close()
                        exitApplication()
                    },
                )
                HorizontalDivider(color = BorderColor, thickness = 1.dp)
                Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    BackStageApp(
                        engine = engine,
                        onBringToFront = { surfaceWindow() },
                    )
                }
            }
        }
    }
}

@Composable
private fun WindowScope.ModernTitleBar(
    windowState: WindowState,
    onMinimize: () -> Unit,
    onToggleMaximize: () -> Unit,
    onClose: () -> Unit,
) {
    val isMaximized = windowState.placement == WindowPlacement.Maximized
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(38.dp)
            .background(BgBase),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        WindowDraggableArea(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .pointerInput(Unit) {
                    detectTapGestures(
                        onDoubleTap = { onToggleMaximize() }
                    )
                }
        ) {
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Canvas(modifier = Modifier.size(16.dp)) {
                    drawBackStageEmblem()
                }
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = "BackStage",
                    color = TextMain,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 0.6.sp,
                )
            }
        }

        // Window Control Buttons (Minimize, Maximize/Restore, Close)
        Row(
            modifier = Modifier.fillMaxHeight(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            WindowControlButton(
                isDanger = false,
                onClick = onMinimize,
            ) { iconColor ->
                val y = size.height / 2f
                drawLine(
                    color = iconColor,
                    start = Offset(size.width * 0.2f, y),
                    end = Offset(size.width * 0.8f, y),
                    strokeWidth = 1.5f,
                    cap = StrokeCap.Round,
                )
            }

            WindowControlButton(
                isDanger = false,
                onClick = onToggleMaximize,
            ) { iconColor ->
                val pad = size.width * 0.22f
                val boxSize = size.width - pad * 2f
                if (isMaximized) {
                    val small = boxSize * 0.82f
                    drawRoundRect(
                        color = iconColor,
                        topLeft = Offset(pad + boxSize - small, pad),
                        size = Size(small, small),
                        cornerRadius = CornerRadius(1.5f, 1.5f),
                        style = Stroke(width = 1.4f),
                    )
                    drawRoundRect(
                        color = Color(0xFF0C0F17),
                        topLeft = Offset(pad, pad + boxSize - small),
                        size = Size(small, small),
                        cornerRadius = CornerRadius(1.5f, 1.5f),
                    )
                    drawRoundRect(
                        color = iconColor,
                        topLeft = Offset(pad, pad + boxSize - small),
                        size = Size(small, small),
                        cornerRadius = CornerRadius(1.5f, 1.5f),
                        style = Stroke(width = 1.4f),
                    )
                } else {
                    drawRoundRect(
                        color = iconColor,
                        topLeft = Offset(pad, pad),
                        size = Size(boxSize, boxSize),
                        cornerRadius = CornerRadius(2f, 2f),
                        style = Stroke(width = 1.5f),
                    )
                }
            }

            WindowControlButton(
                isDanger = true,
                onClick = onClose,
            ) { iconColor ->
                val pad = size.width * 0.24f
                drawLine(
                    color = iconColor,
                    start = Offset(pad, pad),
                    end = Offset(size.width - pad, size.height - pad),
                    strokeWidth = 1.6f,
                    cap = StrokeCap.Round,
                )
                drawLine(
                    color = iconColor,
                    start = Offset(size.width - pad, pad),
                    end = Offset(pad, size.height - pad),
                    strokeWidth = 1.6f,
                    cap = StrokeCap.Round,
                )
            }
        }
    }
}

@Composable
private fun WindowControlButton(
    isDanger: Boolean,
    onClick: () -> Unit,
    drawIcon: DrawScope.(Color) -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()
    val bgColor = when {
        isHovered && isDanger -> DangerColor
        isHovered -> BgSurfaceHover
        else -> Color.Transparent
    }
    val iconColor = when {
        isHovered && isDanger -> Color.White
        isHovered -> TextMain
        else -> TextMuted
    }

    Box(
        modifier = Modifier
            .width(46.dp)
            .fillMaxHeight()
            .background(bgColor)
            .hoverable(interactionSource)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(modifier = Modifier.size(16.dp)) {
            drawIcon(iconColor)
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
