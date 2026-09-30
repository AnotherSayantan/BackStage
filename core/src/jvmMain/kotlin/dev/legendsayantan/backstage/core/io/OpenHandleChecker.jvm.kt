package dev.legendsayantan.backstage.core.io

import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.Structure
import com.sun.jna.WString
import com.sun.jna.ptr.IntByReference
import com.sun.jna.win32.StdCallLibrary
import dev.legendsayantan.backstage.core.error.OpenHandle
import dev.legendsayantan.backstage.core.platform.Platform
import dev.legendsayantan.backstage.core.platform.currentPlatform
import java.io.File
import java.nio.file.Files

actual class OpenHandleChecker actual constructor() {
    actual fun getOpenHandles(directoryPath: String): List<OpenHandle> {
        val dir = File(directoryPath)
        if (!dir.exists() || !dir.isDirectory) return emptyList()

        return when (currentPlatform()) {
            Platform.WINDOWS -> getWindowsRestartManagerHandles(dir)
            Platform.LINUX, Platform.ANDROID -> getLinuxProcFdHandles(dir)
            Platform.MACOS -> emptyList()
        }
    }

    private fun getLinuxProcFdHandles(targetDir: File): List<OpenHandle> {
        val procRoot = File("/proc")
        if (!procRoot.isDirectory) return emptyList()
        val canonicalTarget = runCatching { targetDir.canonicalPath }.getOrDefault(targetDir.absolutePath)
        val selfPid = ProcessHandle.current().pid().toInt()
        val handles = mutableListOf<OpenHandle>()

        val pidDirs = procRoot.listFiles { f -> f.isDirectory && f.name.all { it.isDigit() } } ?: return emptyList()
        for (pidDir in pidDirs) {
            val pid = pidDir.name.toIntOrNull() ?: continue
            if (pid == selfPid) continue

            val commFile = File(pidDir, "comm")
            val processName = runCatching { commFile.readText().trim() }.getOrDefault("pid-$pid")

            // Check cwd symlink
            val cwdLink = File(pidDir, "cwd").toPath()
            runCatching {
                if (Files.isSymbolicLink(cwdLink)) {
                    val resolved = Files.readSymbolicLink(cwdLink).toFile().canonicalPath
                    val targetCanonical = canonicalTarget.trimEnd('/')
                    if (resolved == targetCanonical || resolved.startsWith("$targetCanonical/")) {
                        handles.add(OpenHandle(pid = pid, processName = processName, filePath = resolved))
                    }
                }
            }

            // Check /proc/<pid>/fd/* symlinks
            val fdDir = File(pidDir, "fd")
            val fdEntries = fdDir.listFiles() ?: continue
            for (fd in fdEntries) {
                runCatching {
                    val path = fd.toPath()
                    if (Files.isSymbolicLink(path)) {
                        // B-16: Strip " (deleted)" suffix for unlinked but still-open files
                        val rawTarget = Files.readSymbolicLink(path).toString()
                        val targetPath = rawTarget.removeSuffix(" (deleted)")
                        val canonicalPath = runCatching {
                            File(targetPath).canonicalPath
                        }.getOrDefault(targetPath)
                        val targetCanonical = canonicalTarget.trimEnd('/')
                        if (canonicalPath == targetCanonical ||
                            canonicalPath.startsWith("$targetCanonical/")) {
                            handles.add(OpenHandle(pid = pid, processName = processName, filePath = canonicalPath))
                        }
                    }
                }
            }
        }
        return handles.distinctBy { "${it.pid}:${it.filePath}" }
    }

    private fun getWindowsRestartManagerHandles(targetDir: File): List<OpenHandle> {
        return try {
            // B-15: Prioritize non-hidden/non-vcs files, register up to 1024 files in batches.
            // Walking non-.git paths first avoids the blindspot where only .git/objects/* are sampled.
            val candidateFiles = targetDir.walkTopDown()
                .onEnter { dir -> dir.name != ".git" && dir.name != ".hg" }
                .filter { it.isFile && it.name != ".bstage_lock" }
                .take(1024)
                .map { WString(it.absolutePath) }
                .toList()
                .toTypedArray()

            if (candidateFiles.isEmpty()) return emptyList()

            val sessionHandle = IntByReference()
            val sessionKey = CharArray(CCH_RM_SESSION_KEY + 1)
            val startRc = RstrtMgr.INSTANCE.RmStartSession(sessionHandle, 0, sessionKey)
            if (startRc != 0) return emptyList()

            try {
                val regRc = RstrtMgr.INSTANCE.RmRegisterResources(
                    sessionHandle.value,
                    candidateFiles.size,
                    candidateFiles,
                    0,
                    Pointer.NULL,
                    0,
                    null
                )
                if (regRc != 0) return emptyList()

                val procInfoNeeded = IntByReference(0)
                val procInfoCount = IntByReference(0)
                val rebootReasons = IntByReference(0)

                val firstListRc = RstrtMgr.INSTANCE.RmGetList(
                    sessionHandle.value,
                    procInfoNeeded,
                    procInfoCount,
                    null,
                    rebootReasons
                )
                if (firstListRc == 0 || procInfoNeeded.value == 0) return emptyList()

                val count = procInfoNeeded.value
                procInfoCount.value = count
                val infoArray = RM_PROCESS_INFO().toArray(count) as Array<RM_PROCESS_INFO>
                val listRc = RstrtMgr.INSTANCE.RmGetList(
                    sessionHandle.value,
                    procInfoNeeded,
                    procInfoCount,
                    infoArray,
                    rebootReasons
                )
                if (listRc != 0) return emptyList()

                val selfPid = ProcessHandle.current().pid().toInt()
                infoArray.take(procInfoCount.value)
                    .mapNotNull { info ->
                        val pid = info.Process.dwProcessId
                        if (pid == selfPid || pid <= 0) {
                            null
                        } else {
                            val name = Native.toString(info.strAppName).ifBlank { "pid-$pid" }
                            OpenHandle(
                                pid = pid,
                                processName = name,
                                filePath = targetDir.absolutePath,
                            )
                        }
                    }
            } finally {
                RstrtMgr.INSTANCE.RmEndSession(sessionHandle.value)
            }
        } catch (_: Throwable) {
            emptyList()
        }
    }

    companion object {
        private const val CCH_RM_SESSION_KEY = 32
        private const val CCH_RM_MAX_APP_NAME = 255
        private const val CCH_RM_MAX_SVC_NAME = 63
    }

    @Structure.FieldOrder("dwProcessId", "ProcessStartTime")
    open class RM_UNIQUE_PROCESS : Structure() {
        @JvmField var dwProcessId: Int = 0
        @JvmField var ProcessStartTime: Long = 0L
    }

    @Structure.FieldOrder(
        "Process",
        "strAppName",
        "strServiceShortName",
        "ApplicationType",
        "AppStatus",
        "TSSessionId",
        "bRestartable"
    )
    open class RM_PROCESS_INFO : Structure() {
        @JvmField var Process: RM_UNIQUE_PROCESS = RM_UNIQUE_PROCESS()
        @JvmField var strAppName: CharArray = CharArray(CCH_RM_MAX_APP_NAME + 1)
        @JvmField var strServiceShortName: CharArray = CharArray(CCH_RM_MAX_SVC_NAME + 1)
        @JvmField var ApplicationType: Int = 0
        @JvmField var AppStatus: Int = 0
        @JvmField var TSSessionId: Int = 0
        @JvmField var bRestartable: Int = 0
    }

    private interface RstrtMgr : StdCallLibrary {
        fun RmStartSession(pSessionHandle: IntByReference, dwSessionFlags: Int, strSessionKey: CharArray): Int
        fun RmRegisterResources(
            dwSessionHandle: Int,
            nFiles: Int,
            rgsFilenames: Array<WString>?,
            nApplications: Int,
            rgApplications: Pointer?,
            nServices: Int,
            rgsServiceNames: Array<WString>?,
        ): Int
        fun RmGetList(
            dwSessionHandle: Int,
            pnProcInfoNeeded: IntByReference,
            pnProcInfo: IntByReference,
            rgAffectedApps: Array<RM_PROCESS_INFO>?,
            lpdwRebootReasons: IntByReference,
        ): Int
        fun RmEndSession(dwSessionHandle: Int): Int

        companion object {
            val INSTANCE: RstrtMgr by lazy {
                Native.load("Rstrtmgr", RstrtMgr::class.java)
            }
        }
    }
}
