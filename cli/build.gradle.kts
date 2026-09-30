import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.kotlinSerialization)
    alias(libs.plugins.shadow)
    application
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

application {
    mainClass.set("dev.legendsayantan.backstage.cli.BackStageCliKt")
    applicationName = "bstage"
}

dependencies {
    implementation(projects.core)
    implementation(libs.coroutines.core)
    implementation(libs.serialization.json)
    implementation(libs.datetime)
    implementation(libs.clikt)
    testImplementation(libs.kotlin.test)
    testImplementation(libs.coroutines.test)
}
