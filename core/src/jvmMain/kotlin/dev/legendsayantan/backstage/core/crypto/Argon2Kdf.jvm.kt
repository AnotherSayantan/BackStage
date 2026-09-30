package dev.legendsayantan.backstage.core.crypto

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
        val arch = System.getProperty("os.arch").orEmpty().lowercase()
        return if (arch.contains("amd64") || arch.contains("x86_64") || arch.contains("aarch64")) {
            ResolvedCipher.AES_GCM
        } else {
            ResolvedCipher.CHACHA20_POLY1305
        }
    }
}
