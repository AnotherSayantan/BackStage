package dev.legendsayantan.backstage.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.legendsayantan.backstage.core.backend.MountedDriveDetector
import dev.legendsayantan.backstage.core.backend.MountedStorageVolume
import dev.legendsayantan.backstage.ui.theme.AccentPrimary
import dev.legendsayantan.backstage.ui.theme.AccentSecondary
import dev.legendsayantan.backstage.ui.theme.BgBase
import dev.legendsayantan.backstage.ui.theme.BgSurface
import dev.legendsayantan.backstage.ui.theme.BorderColor
import dev.legendsayantan.backstage.ui.theme.DangerColor
import dev.legendsayantan.backstage.ui.theme.TextMain
import dev.legendsayantan.backstage.ui.theme.TextMuted
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

expect val supportsNativeOsFilePicker: Boolean

expect suspend fun openNativeOsFilePicker(
    initialPath: String,
    selectDirectories: Boolean,
    allowedExtensions: List<String> = emptyList(),
    dialogTitle: String = "Select Path",
): String?

enum class GlyphType {
    LOCK,
    FOLDER,
    FILE,
    DRIVE,
    CLOUD,
    PLUS,
    ARROW_UP,
    ARROW_LEFT,
    CLOSE,
    SLIDERS,
    VAULT,
}

/**
 * Custom architectural 1.5dp stroke vector glyph drawn on Canvas — zero emojis.
 */
@Composable
fun BackStageGlyph(
    type: GlyphType,
    color: Color = TextMain,
    size: Dp = 16.dp,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier = modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        val strokeWidth = (w * 0.10f).coerceAtLeast(1.4f)
        val stroke = Stroke(width = strokeWidth, cap = StrokeCap.Round, join = StrokeJoin.Round)

        when (type) {
            GlyphType.LOCK -> {
                // Shackle arc
                val shackleW = w * 0.44f
                val shackleLeft = (w - shackleW) / 2f
                drawArc(
                    color = color,
                    startAngle = 180f,
                    sweepAngle = 180f,
                    useCenter = false,
                    topLeft = Offset(shackleLeft, h * 0.12f),
                    size = Size(shackleW, h * 0.42f),
                    style = stroke,
                )
                drawLine(
                    color = color,
                    start = Offset(shackleLeft, h * 0.33f),
                    end = Offset(shackleLeft, h * 0.46f),
                    strokeWidth = strokeWidth,
                )
                drawLine(
                    color = color,
                    start = Offset(shackleLeft + shackleW, h * 0.33f),
                    end = Offset(shackleLeft + shackleW, h * 0.46f),
                    strokeWidth = strokeWidth,
                )
                // Lock body
                drawRoundRect(
                    color = color,
                    topLeft = Offset(w * 0.18f, h * 0.46f),
                    size = Size(w * 0.64f, h * 0.44f),
                    cornerRadius = CornerRadius(w * 0.10f, h * 0.10f),
                    style = stroke,
                )
                // Keyhole dot
                drawCircle(
                    color = color,
                    radius = w * 0.06f,
                    center = Offset(w * 0.50f, h * 0.68f),
                )
            }

            GlyphType.FOLDER -> {
                val path = Path().apply {
                    moveTo(w * 0.12f, h * 0.24f)
                    lineTo(w * 0.40f, h * 0.24f)
                    lineTo(w * 0.48f, h * 0.34f)
                    lineTo(w * 0.88f, h * 0.34f)
                    lineTo(w * 0.88f, h * 0.80f)
                    lineTo(w * 0.12f, h * 0.80f)
                    close()
                }
                drawPath(path = path, color = color, style = stroke)
            }

            GlyphType.FILE -> {
                val path = Path().apply {
                    moveTo(w * 0.22f, h * 0.14f)
                    lineTo(w * 0.60f, h * 0.14f)
                    lineTo(w * 0.78f, h * 0.32f)
                    lineTo(w * 0.78f, h * 0.86f)
                    lineTo(w * 0.22f, h * 0.86f)
                    close()
                }
                drawPath(path = path, color = color, style = stroke)
                drawLine(
                    color = color,
                    start = Offset(w * 0.58f, h * 0.14f),
                    end = Offset(w * 0.58f, h * 0.34f),
                    strokeWidth = strokeWidth,
                )
                drawLine(
                    color = color,
                    start = Offset(w * 0.58f, h * 0.34f),
                    end = Offset(w * 0.78f, h * 0.34f),
                    strokeWidth = strokeWidth,
                )
            }

            GlyphType.DRIVE -> {
                drawRoundRect(
                    color = color,
                    topLeft = Offset(w * 0.12f, h * 0.26f),
                    size = Size(w * 0.76f, h * 0.48f),
                    cornerRadius = CornerRadius(w * 0.10f, h * 0.10f),
                    style = stroke,
                )
                drawLine(
                    color = color,
                    start = Offset(w * 0.12f, h * 0.54f),
                    end = Offset(w * 0.88f, h * 0.54f),
                    strokeWidth = strokeWidth * 0.85f,
                )
                drawCircle(
                    color = color,
                    radius = w * 0.055f,
                    center = Offset(w * 0.72f, h * 0.64f),
                )
            }

            GlyphType.CLOUD -> {
                val path = Path().apply {
                    moveTo(w * 0.26f, h * 0.76f)
                    lineTo(w * 0.74f, h * 0.76f)
                    cubicTo(w * 0.88f, h * 0.76f, w * 0.90f, h * 0.52f, w * 0.74f, h * 0.50f)
                    cubicTo(w * 0.72f, h * 0.26f, w * 0.38f, h * 0.24f, w * 0.34f, h * 0.46f)
                    cubicTo(w * 0.14f, h * 0.46f, w * 0.12f, h * 0.76f, w * 0.26f, h * 0.76f)
                    close()
                }
                drawPath(path = path, color = color, style = stroke)
            }

            GlyphType.PLUS -> {
                drawLine(
                    color = color,
                    start = Offset(w * 0.50f, h * 0.20f),
                    end = Offset(w * 0.50f, h * 0.80f),
                    strokeWidth = strokeWidth,
                    cap = StrokeCap.Round,
                )
                drawLine(
                    color = color,
                    start = Offset(w * 0.20f, h * 0.50f),
                    end = Offset(w * 0.80f, h * 0.50f),
                    strokeWidth = strokeWidth,
                    cap = StrokeCap.Round,
                )
            }

            GlyphType.ARROW_UP -> {
                drawLine(
                    color = color,
                    start = Offset(w * 0.50f, h * 0.80f),
                    end = Offset(w * 0.50f, h * 0.22f),
                    strokeWidth = strokeWidth,
                    cap = StrokeCap.Round,
                )
                drawLine(
                    color = color,
                    start = Offset(w * 0.26f, h * 0.46f),
                    end = Offset(w * 0.50f, h * 0.22f),
                    strokeWidth = strokeWidth,
                    cap = StrokeCap.Round,
                )
                drawLine(
                    color = color,
                    start = Offset(w * 0.74f, h * 0.46f),
                    end = Offset(w * 0.50f, h * 0.22f),
                    strokeWidth = strokeWidth,
                    cap = StrokeCap.Round,
                )
            }

            GlyphType.ARROW_LEFT -> {
                drawLine(
                    color = color,
                    start = Offset(w * 0.80f, h * 0.50f),
                    end = Offset(w * 0.22f, h * 0.50f),
                    strokeWidth = strokeWidth,
                    cap = StrokeCap.Round,
                )
                drawLine(
                    color = color,
                    start = Offset(w * 0.46f, h * 0.26f),
                    end = Offset(w * 0.22f, h * 0.50f),
                    strokeWidth = strokeWidth,
                    cap = StrokeCap.Round,
                )
                drawLine(
                    color = color,
                    start = Offset(w * 0.46f, h * 0.74f),
                    end = Offset(w * 0.22f, h * 0.50f),
                    strokeWidth = strokeWidth,
                    cap = StrokeCap.Round,
                )
            }

            GlyphType.CLOSE -> {
                drawLine(
                    color = color,
                    start = Offset(w * 0.24f, h * 0.24f),
                    end = Offset(w * 0.76f, h * 0.76f),
                    strokeWidth = strokeWidth,
                    cap = StrokeCap.Round,
                )
                drawLine(
                    color = color,
                    start = Offset(w * 0.76f, h * 0.24f),
                    end = Offset(w * 0.24f, h * 0.76f),
                    strokeWidth = strokeWidth,
                    cap = StrokeCap.Round,
                )
            }

            GlyphType.SLIDERS -> {
                val y1 = h * 0.30f
                val y2 = h * 0.70f
                drawLine(color, Offset(w * 0.16f, y1), Offset(w * 0.84f, y1), strokeWidth)
                drawLine(color, Offset(w * 0.16f, y2), Offset(w * 0.84f, y2), strokeWidth)
                drawCircle(color, radius = w * 0.10f, center = Offset(w * 0.38f, y1))
                drawCircle(color, radius = w * 0.10f, center = Offset(w * 0.64f, y2))
            }

            GlyphType.VAULT -> {
                drawRoundRect(
                    color = color,
                    topLeft = Offset(w * 0.14f, h * 0.14f),
                    size = Size(w * 0.72f, h * 0.72f),
                    cornerRadius = CornerRadius(w * 0.14f, h * 0.14f),
                    style = stroke,
                )
                drawRoundRect(
                    color = color,
                    topLeft = Offset(w * 0.34f, h * 0.34f),
                    size = Size(w * 0.32f, h * 0.32f),
                    cornerRadius = CornerRadius(w * 0.06f, h * 0.06f),
                    style = stroke,
                )
            }
        }
    }
}

/**
 * Tactile matte-carbon modal dialog container with crisp 1dp border and zero emojis.
 */
@Composable
fun BackStageModalDialog(
    glyph: GlyphType = GlyphType.VAULT,
    title: String,
    subtitle: String? = null,
    width: Dp = 620.dp,
    maxHeight: Dp = 680.dp,
    onDismissRequest: () -> Unit,
    headerTrailingContent: (@Composable RowScope.() -> Unit)? = null,
    confirmButtonText: String,
    confirmEnabled: Boolean = true,
    onConfirm: () -> Unit,
    dismissButtonText: String = "Cancel",
    dismissEnabled: Boolean = true,
    footerLeadingContent: (@Composable RowScope.() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Dialog(
        onDismissRequest = { if (dismissEnabled) onDismissRequest() },
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier
                .width(width)
                .heightIn(max = maxHeight)
                .padding(16.dp)
                .border(1.dp, BorderColor, RoundedCornerShape(12.dp)),
            shape = RoundedCornerShape(12.dp),
            color = BgSurface,
            shadowElevation = 20.dp,
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                // Header
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(BgBase)
                        .padding(horizontal = 22.dp, vertical = 16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.weight(1f),
                    ) {
                        Surface(
                            color = BgSurface,
                            shape = RoundedCornerShape(8.dp),
                            border = BorderStroke(1.dp, BorderColor),
                            modifier = Modifier.size(34.dp),
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                BackStageGlyph(type = glyph, color = AccentPrimary, size = 16.dp)
                            }
                        }
                        Column {
                            Text(
                                text = title,
                                color = TextMain,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.SemiBold,
                            )
                            if (!subtitle.isNullOrBlank()) {
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = subtitle,
                                    color = TextMuted,
                                    fontSize = 12.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        if (headerTrailingContent != null) {
                            headerTrailingContent()
                        }
                        Surface(
                            color = BgSurface,
                            shape = RoundedCornerShape(6.dp),
                            border = BorderStroke(1.dp, BorderColor),
                            modifier = Modifier
                                .size(30.dp)
                                .clip(RoundedCornerShape(6.dp))
                                .clickable(enabled = dismissEnabled) { onDismissRequest() },
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                BackStageGlyph(type = GlyphType.CLOSE, color = TextMuted, size = 13.dp)
                            }
                        }
                    }
                }

                HorizontalDivider(color = BorderColor, thickness = 1.dp)

                // Body
                Column(
                    modifier = Modifier
                        .weight(1f, fill = false)
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 22.dp, vertical = 18.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                    content = content,
                )

                HorizontalDivider(color = BorderColor, thickness = 1.dp)

                // Footer
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(BgBase)
                        .padding(horizontal = 22.dp, vertical = 14.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Row(
                        modifier = Modifier.weight(1f),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (footerLeadingContent != null) {
                            footerLeadingContent()
                        }
                    }

                    Row(
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        OutlinedButton(
                            onClick = onDismissRequest,
                            enabled = dismissEnabled,
                            shape = RoundedCornerShape(8.dp),
                            border = BorderStroke(1.dp, BorderColor),
                            modifier = Modifier.height(38.dp),
                        ) {
                            Text(dismissButtonText, color = TextMuted, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                        }

                        Button(
                            onClick = onConfirm,
                            enabled = confirmEnabled,
                            shape = RoundedCornerShape(8.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = AccentPrimary,
                                contentColor = Color(0xFF12100B),
                                disabledContainerColor = BorderColor.copy(alpha = 0.5f),
                            ),
                            modifier = Modifier.height(38.dp),
                        ) {
                            Text(
                                text = confirmButtonText,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun DialogSectionLabel(
    title: String,
    badge: String? = null,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            color = TextMuted,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            letterSpacing = 0.3.sp,
        )
        if (!badge.isNullOrBlank()) {
            Text(
                text = badge,
                color = AccentSecondary,
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
            )
        }
    }
}

@Composable
fun StyledTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    placeholder: String? = null,
    enabled: Boolean = true,
    singleLine: Boolean = true,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    trailingContent: (@Composable () -> Unit)? = null,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label, fontSize = 12.sp) },
        placeholder = placeholder?.let { { Text(it, color = TextMuted.copy(alpha = 0.6f), fontSize = 12.sp) } },
        singleLine = singleLine,
        enabled = enabled,
        visualTransformation = visualTransformation,
        trailingIcon = trailingContent,
        shape = RoundedCornerShape(8.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedContainerColor = BgBase,
            unfocusedContainerColor = BgBase,
            disabledContainerColor = BgBase.copy(alpha = 0.4f),
            focusedBorderColor = AccentPrimary,
            unfocusedBorderColor = BorderColor,
            focusedLabelColor = AccentPrimary,
            unfocusedLabelColor = TextMuted,
            cursorColor = AccentPrimary,
            focusedTextColor = TextMain,
            unfocusedTextColor = TextMain,
        ),
        modifier = modifier,
    )
}

@Composable
fun DirectoryInputField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    dialogTitle: String = label,
) {
    var showBrowser by remember { mutableStateOf(false) }

    StyledTextField(
        value = value,
        onValueChange = onValueChange,
        label = label,
        enabled = enabled,
        modifier = modifier.fillMaxWidth(),
        trailingContent = {
            Surface(
                color = BgSurface,
                shape = RoundedCornerShape(6.dp),
                border = BorderStroke(1.dp, BorderColor),
                modifier = Modifier
                    .padding(end = 8.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .clickable(enabled = enabled) { showBrowser = true },
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    BackStageGlyph(type = GlyphType.FOLDER, color = AccentPrimary, size = 13.dp)
                    Text(
                        text = "Browse",
                        color = TextMain,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                    )
                }
            }
        },
    )

    if (showBrowser) {
        FileSystemPickerDialog(
            initialPath = value,
            selectDirectories = true,
            allowedExtensions = emptyList(),
            title = dialogTitle,
            onDismiss = { showBrowser = false },
            onSelected = { selectedPath ->
                showBrowser = false
                onValueChange(selectedPath)
            },
        )
    }
}

@Composable
fun VaultFileInputField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    allowedExtensions: List<String> = listOf("bstage", "7z", "bvault"),
    dialogTitle: String = label,
) {
    var showBrowser by remember { mutableStateOf(false) }

    StyledTextField(
        value = value,
        onValueChange = onValueChange,
        label = label,
        placeholder = "Select an archive file...",
        enabled = enabled,
        modifier = modifier.fillMaxWidth(),
        trailingContent = {
            Surface(
                color = BgSurface,
                shape = RoundedCornerShape(6.dp),
                border = BorderStroke(1.dp, BorderColor),
                modifier = Modifier
                    .padding(end = 8.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .clickable(enabled = enabled) { showBrowser = true },
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    BackStageGlyph(type = GlyphType.FILE, color = AccentPrimary, size = 13.dp)
                    Text(
                        text = "Browse",
                        color = TextMain,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                    )
                }
            }
        },
    )

    if (showBrowser) {
        FileSystemPickerDialog(
            initialPath = value.ifBlank {
                File(System.getProperty("user.home") ?: ".", "BackStageVaults").absolutePath
            },
            selectDirectories = false,
            allowedExtensions = allowedExtensions,
            title = dialogTitle,
            onDismiss = { showBrowser = false },
            onSelected = { selectedPath ->
                showBrowser = false
                onValueChange(selectedPath)
            },
        )
    }
}

/**
 * Vault storage selector that dynamically queries OS-mounted volumes via [MountedDriveDetector.discoverMountedVolumes].
 * - Only offers local drives and cloud drives that are **actually mounted** on the system.
 * - Never offers unmounted cloud drives.
 * - Direct cloud providers remain disabled (`enabled = false`) until direct cloud support is added.
 */
@Composable
fun VaultStorageBackendSelector(
    vaultDir: String,
    onVaultDirChange: (String) -> Unit,
    enabled: Boolean = true,
) {
    var mountedVolumes by remember { mutableStateOf<List<MountedStorageVolume>>(emptyList()) }

    LaunchedEffect(Unit) {
        mountedVolumes = withContext(Dispatchers.IO) {
            MountedDriveDetector.discoverMountedVolumes()
        }
    }

    Card(
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(containerColor = BgBase),
        border = BorderStroke(1.dp, BorderColor),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // Backend Mode Bar: Local & Mounted Drives (active) vs Cloud Providers (disabled for now)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(BgSurface, RoundedCornerShape(8.dp))
                    .border(1.dp, BorderColor, RoundedCornerShape(8.dp))
                    .padding(3.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Surface(
                    color = BgBase,
                    shape = RoundedCornerShape(6.dp),
                    border = BorderStroke(1.dp, BorderColor),
                    modifier = Modifier.weight(1f),
                ) {
                    Row(
                        modifier = Modifier.padding(vertical = 7.dp, horizontal = 10.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        BackStageGlyph(type = GlyphType.DRIVE, color = AccentPrimary, size = 13.dp)
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "Local & Mounted Drives",
                            color = TextMain,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                        )
                    }
                }

                // Direct Cloud Providers — stays disabled until direct cloud support is implemented
                Surface(
                    color = Color.Transparent,
                    shape = RoundedCornerShape(6.dp),
                    modifier = Modifier
                        .weight(1f)
                        .alpha(0.45f),
                ) {
                    Row(
                        modifier = Modifier.padding(vertical = 7.dp, horizontal = 10.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        BackStageGlyph(type = GlyphType.CLOUD, color = TextMuted, size = 13.dp)
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "Cloud Providers",
                            color = TextMuted,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Normal,
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Surface(
                            color = BgBase,
                            shape = RoundedCornerShape(4.dp),
                            border = BorderStroke(1.dp, BorderColor),
                        ) {
                            Text(
                                text = "Soon",
                                color = TextMuted,
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Medium,
                                modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp),
                            )
                        }
                    }
                }
            }

            // Dynamically discovered local disks & actually mounted cloud drives
            if (mountedVolumes.isNotEmpty()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    for (vol in mountedVolumes) {
                        val defaultPath = vol.defaultVaultDir.absolutePath
                        val mountPath = vol.mountPoint.absolutePath
                        val trimmedCurrent = vaultDir.trim()
                        val isSelected = trimmedCurrent.equals(defaultPath, ignoreCase = true) ||
                            trimmedCurrent.equals(mountPath, ignoreCase = true) ||
                            (trimmedCurrent.startsWith(mountPath, ignoreCase = true) &&
                                mountedVolumes.none { other ->
                                    other != vol &&
                                        other.mountPoint.absolutePath.length > mountPath.length &&
                                        trimmedCurrent.startsWith(other.mountPoint.absolutePath, ignoreCase = true)
                                })

                        val freeGb = (vol.freeBytes / (1024L * 1024L * 1024L)).coerceAtLeast(0L)
                        val totalGb = (vol.totalBytes / (1024L * 1024L * 1024L)).coerceAtLeast(1L)
                        val usedFraction = ((totalGb - freeGb).toFloat() / totalGb.toFloat()).coerceIn(0f, 1f)

                        Surface(
                            color = if (isSelected) AccentPrimary.copy(alpha = 0.12f) else BgSurface,
                            shape = RoundedCornerShape(8.dp),
                            border = BorderStroke(
                                1.dp,
                                if (isSelected) AccentPrimary else BorderColor,
                            ),
                            modifier = Modifier
                                .width(190.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .clickable(enabled = enabled) {
                                    onVaultDirChange(defaultPath)
                                },
                        ) {
                            Column(
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                                        modifier = Modifier.weight(1f),
                                    ) {
                                        BackStageGlyph(
                                            type = if (vol.isCloudMount) GlyphType.CLOUD else GlyphType.DRIVE,
                                            color = if (isSelected) AccentPrimary else if (vol.isCloudMount) AccentSecondary else TextMuted,
                                            size = 13.dp,
                                        )
                                        Text(
                                            text = vol.name,
                                            color = if (isSelected) AccentPrimary else TextMain,
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                    if (vol.isCloudMount) {
                                        Text(
                                            text = "Mounted",
                                            color = AccentSecondary,
                                            fontSize = 10.sp,
                                            fontWeight = FontWeight.Medium,
                                        )
                                    }
                                }

                                Text(
                                    text = vol.subtitle,
                                    color = TextMuted,
                                    fontSize = 10.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )

                                LinearProgressIndicator(
                                    progress = { usedFraction },
                                    color = if (isSelected) AccentPrimary else AccentSecondary,
                                    trackColor = BgBase,
                                    modifier = Modifier.fillMaxWidth().height(3.dp).clip(RoundedCornerShape(2.dp)),
                                )

                                Text(
                                    text = "$freeGb GB free of $totalGb GB",
                                    color = TextMuted,
                                    fontSize = 10.sp,
                                )
                            }
                        }
                    }
                }
            }

            DirectoryInputField(
                value = vaultDir,
                onValueChange = onVaultDirChange,
                label = "Vault Folder",
                enabled = enabled,
                dialogTitle = "Select Vault Folder",
            )
        }
    }
}

private data class QuickLocationItem(
    val label: String,
    val file: File,
    val isCloud: Boolean,
)

/**
 * Clean matte-carbon Filesystem Browser Modal.
 */
@Composable
fun FileSystemPickerDialog(
    initialPath: String,
    selectDirectories: Boolean,
    allowedExtensions: List<String>,
    title: String,
    onDismiss: () -> Unit,
    onSelected: (String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val homeDir = remember { File(System.getProperty("user.home") ?: ".").absoluteFile }

    var currentDir by remember {
        val start = File(initialPath.trim()).let { f ->
            val resolved = if (!selectDirectories && f.isFile) f.parentFile else f
            generateSequence(resolved) { it.parentFile }.firstOrNull { it.isDirectory } ?: homeDir
        }
        mutableStateOf(start.absoluteFile)
    }
    var pathInput by remember(currentDir) { mutableStateOf(currentDir.absolutePath) }
    var selectedFile by remember {
        val initFile = File(initialPath.trim())
        mutableStateOf<File?>(if (!selectDirectories && initFile.isFile) initFile else null)
    }
    var searchQuery by remember { mutableStateOf("") }
    var entries by remember { mutableStateOf<List<File>>(emptyList()) }
    var showNewFolderRow by remember { mutableStateOf(false) }
    var newFolderName by remember { mutableStateOf("") }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    var quickLocations by remember { mutableStateOf<List<QuickLocationItem>>(emptyList()) }

    LaunchedEffect(Unit) {
        quickLocations = withContext(Dispatchers.IO) {
            buildList {
                val volumes = MountedDriveDetector.discoverMountedVolumes()
                for (vol in volumes) {
                    add(QuickLocationItem(label = vol.name, file = vol.mountPoint, isCloud = vol.isCloudMount))
                }
                add(QuickLocationItem(label = "Home", file = homeDir, isCloud = false))
                val desktop = File(homeDir, "Desktop")
                if (desktop.isDirectory) {
                    add(QuickLocationItem(label = "Desktop", file = desktop, isCloud = false))
                }
            }
        }
    }

    fun refreshEntries(dir: File) {
        scope.launch(Dispatchers.IO) {
            val children = runCatching {
                dir.listFiles()?.filter { f ->
                    if (f.isDirectory) {
                        true
                    } else if (selectDirectories) {
                        false
                    } else if (allowedExtensions.isEmpty()) {
                        true
                    } else {
                        f.extension.lowercase() in allowedExtensions
                    }
                }?.sortedWith(compareBy<File>({ !it.isDirectory }, { it.name.lowercase() })).orEmpty()
            }.getOrDefault(emptyList())
            withContext(Dispatchers.Main) {
                entries = children
            }
        }
    }

    LaunchedEffect(currentDir) {
        pathInput = currentDir.absolutePath
        errorMsg = null
        refreshEntries(currentDir)
    }

    val filteredEntries = remember(entries, searchQuery) {
        if (searchQuery.isBlank()) {
            entries
        } else {
            entries.filter { it.name.contains(searchQuery.trim(), ignoreCase = true) }
        }
    }

    val canConfirm = if (selectDirectories) {
        pathInput.isNotBlank()
    } else {
        selectedFile?.isFile == true || File(pathInput.trim()).isFile
    }

    BackStageModalDialog(
        glyph = if (selectDirectories) GlyphType.FOLDER else GlyphType.FILE,
        title = title,
        subtitle = null,
        width = 680.dp,
        maxHeight = 660.dp,
        onDismissRequest = onDismiss,
        headerTrailingContent = {
            if (supportsNativeOsFilePicker) {
                Surface(
                    color = BgSurface,
                    shape = RoundedCornerShape(6.dp),
                    border = BorderStroke(1.dp, BorderColor),
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .clickable {
                            scope.launch {
                                val picked = openNativeOsFilePicker(
                                    initialPath = currentDir.absolutePath,
                                    selectDirectories = selectDirectories,
                                    allowedExtensions = allowedExtensions,
                                    dialogTitle = title,
                                )
                                if (!picked.isNullOrBlank()) {
                                    onSelected(picked)
                                }
                            }
                        },
                ) {
                    Text(
                        text = "System Dialog",
                        color = TextMain,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                    )
                }
            }
        },
        confirmButtonText = if (selectDirectories) "Select Folder" else "Select File",
        confirmEnabled = canConfirm,
        onConfirm = {
            val chosen = if (selectDirectories) {
                pathInput.trim().ifBlank { currentDir.absolutePath }
            } else {
                selectedFile?.absolutePath ?: pathInput.trim()
            }
            onSelected(chosen)
        },
        footerLeadingContent = {
            Text(
                text = "${filteredEntries.size} items",
                color = TextMuted,
                fontSize = 11.sp,
            )
        },
    ) {
        // Dynamically discovered drives & quick locations
        if (quickLocations.isNotEmpty()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                for (item in quickLocations) {
                    val isCurrent = currentDir.absolutePath.equals(item.file.absolutePath, ignoreCase = true)
                    Surface(
                        color = if (isCurrent) AccentPrimary.copy(alpha = 0.14f) else BgBase,
                        shape = RoundedCornerShape(6.dp),
                        border = BorderStroke(1.dp, if (isCurrent) AccentPrimary else BorderColor),
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .clickable { currentDir = item.file.absoluteFile },
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            BackStageGlyph(
                                type = if (item.isCloud) GlyphType.CLOUD else GlyphType.DRIVE,
                                color = if (isCurrent) AccentPrimary else if (item.isCloud) AccentSecondary else TextMuted,
                                size = 12.dp,
                            )
                            Text(
                                text = item.label,
                                color = if (isCurrent) AccentPrimary else TextMain,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium,
                            )
                        }
                    }
                }
            }
        }

        // Current Path Bar + Up + New Folder
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedButton(
                onClick = {
                    currentDir.parentFile?.let { parent ->
                        if (parent.isDirectory) currentDir = parent.absoluteFile
                    }
                },
                enabled = currentDir.parentFile != null,
                shape = RoundedCornerShape(8.dp),
                border = BorderStroke(1.dp, BorderColor),
                modifier = Modifier.padding(top = 6.dp).height(52.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                    BackStageGlyph(type = GlyphType.ARROW_UP, color = TextMain, size = 12.dp)
                    Text("Up", color = TextMain, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                }
            }

            StyledTextField(
                value = pathInput,
                onValueChange = { typed ->
                    pathInput = typed
                    val candidate = File(typed.trim())
                    if (candidate.isDirectory) {
                        currentDir = candidate.absoluteFile
                    } else if (!selectDirectories && candidate.isFile) {
                        selectedFile = candidate.absoluteFile
                    }
                },
                label = "Path",
                modifier = Modifier.weight(1f),
            )

            if (selectDirectories) {
                OutlinedButton(
                    onClick = { showNewFolderRow = !showNewFolderRow },
                    shape = RoundedCornerShape(8.dp),
                    border = BorderStroke(1.dp, BorderColor),
                    modifier = Modifier.padding(top = 6.dp).height(52.dp),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(5.dp),
                    ) {
                        BackStageGlyph(type = GlyphType.PLUS, color = AccentPrimary, size = 12.dp)
                        Text("New Folder", color = TextMain, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                    }
                }
            }
        }

        // Inline New Folder Creation Row
        if (showNewFolderRow) {
            Surface(
                color = BgBase,
                shape = RoundedCornerShape(8.dp),
                border = BorderStroke(1.dp, BorderColor),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    modifier = Modifier.padding(10.dp).fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    StyledTextField(
                        value = newFolderName,
                        onValueChange = { newFolderName = it },
                        label = "Folder name",
                        placeholder = "Enter name...",
                        modifier = Modifier.weight(1f),
                    )
                    Button(
                        enabled = newFolderName.isNotBlank(),
                        shape = RoundedCornerShape(8.dp),
                        onClick = {
                            val created = File(currentDir, newFolderName.trim())
                            if (created.mkdirs() || created.isDirectory) {
                                newFolderName = ""
                                showNewFolderRow = false
                                currentDir = created.absoluteFile
                            } else {
                                errorMsg = "Could not create folder: ${created.absolutePath}"
                            }
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = AccentPrimary,
                            contentColor = Color(0xFF12100B),
                        ),
                        modifier = Modifier.padding(top = 6.dp).height(48.dp),
                    ) {
                        Text("Create", fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }

        // Filter input
        StyledTextField(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            label = "Filter",
            placeholder = "Filter current folder...",
            modifier = Modifier.fillMaxWidth(),
        )

        // Directory listing
        Surface(
            color = BgBase,
            shape = RoundedCornerShape(8.dp),
            border = BorderStroke(1.dp, BorderColor),
            modifier = Modifier.fillMaxWidth().height(240.dp),
        ) {
            if (filteredEntries.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxWidth().padding(24.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = if (searchQuery.isNotBlank()) {
                            "No matching items"
                        } else {
                            "Empty folder"
                        },
                        color = TextMuted,
                        fontSize = 12.sp,
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().padding(6.dp),
                    verticalArrangement = Arrangement.spacedBy(3.dp),
                ) {
                    items(filteredEntries, key = { it.absolutePath }) { item ->
                        val isDir = item.isDirectory
                        val isSelectedFile = !isDir && selectedFile?.absolutePath == item.absolutePath
                        Surface(
                            color = if (isSelectedFile) AccentPrimary.copy(alpha = 0.16f) else BgSurface,
                            shape = RoundedCornerShape(6.dp),
                            border = BorderStroke(
                                1.dp,
                                if (isSelectedFile) AccentPrimary else BorderColor.copy(alpha = 0.5f),
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(6.dp))
                                .clickable {
                                    if (isDir) {
                                        searchQuery = ""
                                        currentDir = item.absoluteFile
                                    } else {
                                        selectedFile = item.absoluteFile
                                        pathInput = item.absolutePath
                                    }
                                },
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp).fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                                    modifier = Modifier.weight(1f),
                                ) {
                                    BackStageGlyph(
                                        type = if (isDir) GlyphType.FOLDER else GlyphType.FILE,
                                        color = if (isDir) AccentPrimary else TextMuted,
                                        size = 14.dp,
                                    )
                                    Text(
                                        text = item.name,
                                        color = if (isSelectedFile) AccentPrimary else TextMain,
                                        fontSize = 13.sp,
                                        fontWeight = if (isDir || isSelectedFile) FontWeight.Medium else FontWeight.Normal,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                                if (isDir) {
                                    Text("Open", color = TextMuted, fontSize = 11.sp)
                                } else {
                                    val kb = (item.length() / 1024L).coerceAtLeast(1L)
                                    Text(
                                        text = "$kb KB",
                                        color = TextMuted,
                                        fontSize = 11.sp,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        if (errorMsg != null) {
            Surface(
                color = DangerColor.copy(alpha = 0.14f),
                shape = RoundedCornerShape(6.dp),
                border = BorderStroke(1.dp, DangerColor),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = errorMsg!!,
                    color = DangerColor,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                )
            }
        }
    }
}
