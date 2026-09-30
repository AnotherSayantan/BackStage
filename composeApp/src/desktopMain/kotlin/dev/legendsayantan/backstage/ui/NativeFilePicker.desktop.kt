package dev.legendsayantan.backstage.ui

import java.io.File
import javax.swing.JFileChooser
import javax.swing.SwingUtilities
import javax.swing.UIManager
import javax.swing.filechooser.FileNameExtensionFilter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

actual val supportsNativeOsFilePicker: Boolean = true

actual suspend fun openNativeOsFilePicker(
    initialPath: String,
    selectDirectories: Boolean,
    allowedExtensions: List<String>,
    dialogTitle: String,
): String? = withContext(Dispatchers.IO) {
    var selectedPath: String? = null
    runCatching {
        SwingUtilities.invokeAndWait {
            runCatching {
                UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName())
            }
            val startDir = File(initialPath.trim()).let { f ->
                generateSequence(f) { it.parentFile }.firstOrNull { it.exists() }
                    ?: File(System.getProperty("user.home") ?: ".")
            }
            val chooser = JFileChooser(startDir).apply {
                this.dialogTitle = dialogTitle
                fileSelectionMode = if (selectDirectories) {
                    JFileChooser.DIRECTORIES_ONLY
                } else {
                    JFileChooser.FILES_ONLY
                }
                isAcceptAllFileFilterUsed = allowedExtensions.isEmpty() || selectDirectories
                if (!selectDirectories && allowedExtensions.isNotEmpty()) {
                    val desc = "BackStage Vault (${allowedExtensions.joinToString(", ") { "*.$it" }})"
                    fileFilter = FileNameExtensionFilter(desc, *allowedExtensions.toTypedArray())
                }
            }
            val result = chooser.showOpenDialog(null)
            if (result == JFileChooser.APPROVE_OPTION && chooser.selectedFile != null) {
                selectedPath = chooser.selectedFile.absolutePath
            }
        }
    }
    selectedPath
}
