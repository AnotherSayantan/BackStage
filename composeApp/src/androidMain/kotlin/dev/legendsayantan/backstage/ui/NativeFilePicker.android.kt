package dev.legendsayantan.backstage.ui

actual val supportsNativeOsFilePicker: Boolean = false

actual suspend fun openNativeOsFilePicker(
    initialPath: String,
    selectDirectories: Boolean,
    allowedExtensions: List<String>,
    dialogTitle: String,
): String? = null
