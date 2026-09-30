package dev.legendsayantan.backstage.core.crypto

import android.os.Build
import org.bouncycastle.crypto.generators.Argon2BytesGenerator
import org.bouncycastle.crypto.params.Argon2Parameters

actual object Argon2Kdf {
    actual fun derive(
        password: String,
        salt: ByteArray,
        mCost: Int,
        tCost: Int,
        parallelism: Int,
        outputLength: Int,
    ): ByteArray {
        val passwordBytes = password.encodeToByteArray()
        return try {
            val params = Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
                .withVersion(Argon2Parameters.ARGON2_VERSION_13)
                .withMemoryAsKB(mCost)
                .withIterations(tCost)
                .withParallelism(parallelism)
                .withSalt(salt)
                .build()

            val generator = Argon2BytesGenerator()
            generator.init(params)
            val output = ByteArray(outputLength)
            generator.generateBytes(passwordBytes, output, 0, outputLength)
            output
        } finally {
            passwordBytes.fill(0)
        }
    }
}

actual object CipherSuiteDetector {
    actual fun detect(): ResolvedCipher {
        val abis = Build.SUPPORTED_ABIS.map { it.lowercase() }
        val isModern64Bit = abis.any { it.contains("arm64-v8a") || it.contains("x86_64") }
        return if (isModern64Bit) {
            ResolvedCipher.AES_GCM
        } else {
            ResolvedCipher.CHACHA20_POLY1305
        }
    }
}
