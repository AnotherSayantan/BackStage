import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidLibrary)
    alias(libs.plugins.kotlinSerialization)
}

kotlin {
    jvm {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
            freeCompilerArgs.add("-Xexpect-actual-classes")
        }
    }

    androidTarget {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
            freeCompilerArgs.add("-Xexpect-actual-classes")
        }
    }

    sourceSets {
        commonMain.dependencies {
            implementation(libs.coroutines.core)
            implementation(libs.serialization.json)
            implementation(libs.kotlinx.io.core)
            implementation(libs.datetime)
            implementation(libs.bouncycastle.prov)
            implementation(libs.bouncycastle.pkix)
            implementation(libs.commons.compress)
            implementation(libs.ktor.client.core)
            implementation(libs.ktor.client.cio)
        }

        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.coroutines.test)
        }

        jvmMain.dependencies {
            implementation(libs.jna)
            implementation(libs.jna.platform)
            implementation(libs.sqlite.jdbc)
            implementation(libs.zstd.jni)
        }

        androidMain.dependencies {
            implementation(libs.coroutines.android)
            api(libs.androidx.biometric)
            implementation(libs.androidx.security.crypto)
            implementation(libs.sqlite.jdbc)
        }
    }
}

android {
    namespace = "dev.legendsayantan.backstage.core"
    compileSdk = 35

    defaultConfig {
        minSdk = 26
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
