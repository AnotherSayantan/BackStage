package dev.legendsayantan.backstage.core.io

import dev.legendsayantan.backstage.core.error.OpenHandle

/**
 * Queries the host operating system for active processes holding open file handles
 * inside [directoryPath] before an eviction or directory purge begins.
 *
 * - Windows: Win32 Restart Manager API (`RstrtMgr.dll`) / process handle inspection
 * - Linux: `/proc/[pid]/fd` symlink scanning
 * - Android: `/proc/self/fd` scanning
 */
expect class OpenHandleChecker() {
    fun getOpenHandles(directoryPath: String): List<OpenHandle>
}
