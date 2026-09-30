package dev.legendsayantan.backstage.core.crypto

expect object Argon2Kdf {
    fun derive(
        password: String,
        salt: ByteArray,
        mCost: Int = Argon2Config.M_COST,
        tCost: Int = Argon2Config.T_COST,
        parallelism: Int = Argon2Config.PARALLELISM,
        outputLength: Int = Argon2Config.OUTPUT_LENGTH,
    ): ByteArray
}

expect object CipherSuiteDetector {
    fun detect(): ResolvedCipher
}
