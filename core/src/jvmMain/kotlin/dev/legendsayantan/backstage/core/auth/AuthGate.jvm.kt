package dev.legendsayantan.backstage.core.auth

import dev.legendsayantan.backstage.core.platform.Platform
import dev.legendsayantan.backstage.core.platform.PlatformContext
import dev.legendsayantan.backstage.core.platform.currentPlatform
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

actual open class AuthGate actual constructor(
    private val context: PlatformContext,
) {
    actual open suspend fun authenticate(reason: String): AuthResult = withContext(Dispatchers.IO) {
        when (currentPlatform()) {
            Platform.WINDOWS -> authenticateWindowsHello(reason)
            Platform.LINUX -> authenticateLinuxPolkit(reason)
            Platform.MACOS -> authenticateMacOs(reason)
            Platform.ANDROID -> AuthResult.Unavailable
        }
    }

    private fun authenticateWindowsHello(reason: String): AuthResult {
        val sanitizedReason = reason.replace("'", "''")
        val psScript = """
            ${'$'}ErrorActionPreference = 'Stop'
            try {
                Add-Type -AssemblyName System.Runtime.WindowsRuntime
                ${'$'}null = [Windows.Security.Credentials.UI.UserConsentVerifier, Windows.Security.Credentials.UI, ContentType = WindowsRuntime]
                ${'$'}asTaskGeneric = ([System.WindowsRuntimeSystemExtensions].GetMethods() | Where-Object {
                    ${'$'}_.Name -eq 'AsTask' -and ${'$'}_.GetParameters().Count -eq 1 -and ${'$'}_.GetParameters()[0].ParameterType.Name -eq 'IAsyncOperation`1'
                })[0]
                ${'$'}availTask = ${'$'}asTaskGeneric.MakeGenericMethod([Windows.Security.Credentials.UI.UserConsentVerifierAvailability]).Invoke(${'$'}null, @([Windows.Security.Credentials.UI.UserConsentVerifier]::CheckAvailabilityAsync()))
                ${'$'}avail = ${'$'}availTask.Result
                if (${'$'}avail -eq 'Available') {
                    ${'$'}verifyTask = ${'$'}asTaskGeneric.MakeGenericMethod([Windows.Security.Credentials.UI.UserConsentVerificationResult]).Invoke(${'$'}null, @([Windows.Security.Credentials.UI.UserConsentVerifier]::RequestVerificationAsync('$sanitizedReason')))
                    ${'$'}res = ${'$'}verifyTask.Result
                    if (${'$'}res -eq 'Verified') {
                        Write-Output 'VERIFIED'
                        exit 0
                    } else {
                        Write-Output "FAILED:${'$'}res"
                        exit 1
                    }
                } else {
                    Write-Output "UNAVAILABLE:${'$'}avail"
                    exit 2
                }
            } catch {
                Write-Output "ERROR:${'$'}(${'$'}_.Exception.Message)"
                exit 3
            }
        """.trimIndent()

        return try {
            val process = ProcessBuilder(
                "powershell.exe",
                "-NoProfile",
                "-NonInteractive",
                "-Command",
                psScript
            ).redirectErrorStream(true).start()

            val finished = process.waitFor(120, TimeUnit.SECONDS)
            if (!finished) {
                process.destroyForcibly()
                return AuthResult.Failure("Windows Hello verification timed out")
            }
            val output = process.inputStream.bufferedReader().readText().trim()
            when {
                output.contains("VERIFIED") -> AuthResult.Success
                output.startsWith("UNAVAILABLE:") -> AuthResult.Unavailable
                else -> AuthResult.Failure(output.removePrefix("FAILED:").removePrefix("ERROR:"))
            }
        } catch (e: Exception) {
            AuthResult.Failure("Windows Hello invocation error: ${e.message}")
        }
    }

    private fun authenticateLinuxPolkit(reason: String): AuthResult {
        return try {
            val pid = ProcessHandle.current().pid().toString()
            val cmd = if (File("/usr/bin/pkcheck").canExecute()) {
                listOf(
                    "/usr/bin/pkcheck",
                    "--action-id", "org.freedesktop.policykit.exec",
                    "--process", pid,
                    "--allow-user-interaction",
                    "--detail", "polkit.message", reason
                )
            } else if (File("/usr/bin/pkexec").canExecute()) {
                listOf("/usr/bin/pkexec", "--disable-internal-agent", "/bin/true")
            } else {
                return AuthResult.Unavailable
            }

            val process = ProcessBuilder(cmd).redirectErrorStream(true).start()
            val finished = process.waitFor(120, TimeUnit.SECONDS)
            if (!finished) {
                process.destroyForcibly()
                return AuthResult.Failure("Linux Polkit authentication timed out")
            }
            if (process.exitValue() == 0) {
                AuthResult.Success
            } else {
                val err = process.inputStream.bufferedReader().readText().trim()
                AuthResult.Failure(err.ifBlank { "Linux Polkit authentication rejected (exit ${process.exitValue()})" })
            }
        } catch (e: Exception) {
            AuthResult.Failure("Linux credential check failed: ${e.message}")
        }
    }

    private fun authenticateMacOs(reason: String): AuthResult {
        return AuthResult.Failure("macOS LAContext authentication is deferred: $reason")
    }
}
