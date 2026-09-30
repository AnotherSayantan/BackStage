package dev.legendsayantan.backstage.core.io

import android.os.Process
import dev.legendsayantan.backstage.core.error.OpenHandle
import java.io.File
import java.nio.file.Files

actual class OpenHandleChecker actual constructor() {
    actual fun getOpenHandles(directoryPath: String): List<OpenHandle> {
        val targetDir = File(directoryPath)
        if (!targetDir.exists()) return emptyList()
        val canonicalTarget = runCatching { targetDir.canonicalPath }.getOrDefault(targetDir.absolutePath)
        val fdDir = File("/proc/self/fd")
        val fds = fdDir.listFiles() ?: return emptyList()
        val pid = Process.myPid()
        val handles = mutableListOf<OpenHandle>()

        for (fd in fds) {
            runCatching {
                val p = fd.toPath()
                if (Files.isSymbolicLink(p)) {
                    val resolved = Files.readSymbolicLink(p).toString()
                    if (resolved == canonicalTarget || resolved.startsWith("$canonicalTarget/")) {
                        handles.add(OpenHandle(pid = pid, processName = "backstage-android", filePath = resolved))
                    }
                }
            }
        }
        return handles
    }
}
