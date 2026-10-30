import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
}

val withAndroid = rootProject.extra["pm.android"] as Boolean
val withIos = rootProject.extra["pm.ios"] as Boolean

if (withAndroid) apply(plugin = "com.android.application")

kotlin {
    // Desktop target hosts the UI prototype against the simulated platform (no phone needed).
    jvm("desktop") {
        compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
    }
    if (withAndroid) {
        androidTarget {
            compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
        }
    }
    if (withIos) {
        listOf(iosArm64(), iosSimulatorArm64()).forEach { target ->
            target.binaries.framework {
                baseName = "ComposeApp"
                isStatic = true
                export(projects.shared)
            }
        }
    }

    sourceSets {
        commonMain.dependencies {
            api(projects.shared)
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.material3)
            implementation(compose.ui)
            implementation(libs.kotlinx.coroutines.core)
        }
        val desktopMain by getting {
            dependencies {
                implementation(compose.desktop.currentOs)
                implementation(libs.kotlinx.coroutines.swing)
            }
        }
        if (withAndroid) {
            androidMain.dependencies {
                implementation(libs.androidx.activity.compose)
                implementation(libs.androidx.core.ktx)
                implementation(libs.androidx.media3.session)
                implementation(libs.androidx.media3.exoplayer)
                implementation(libs.kotlinx.coroutines.android)
            }
        }
    }
}

if (withAndroid) {
    extensions.configure<com.android.build.api.dsl.ApplicationExtension> {
        namespace = "io.mahajan.partymusik"
        compileSdk = 36
        defaultConfig {
            applicationId = "io.mahajan.partymusik"
            minSdk = 31
            targetSdk = 36
            versionCode = 1
            versionName = "0.1.0-poc"
        }
        compileOptions {
            sourceCompatibility = JavaVersion.VERSION_17
            targetCompatibility = JavaVersion.VERSION_17
        }
        packaging {
            resources { excludes += "/META-INF/{AL2.0,LGPL2.1}" }
        }
    }
}

compose.desktop {
    application {
        mainClass = "io.mahajan.partymusik.desktop.MainKt"
        nativeDistributions {
            packageName = "PartyMusik"
            packageVersion = "1.0.0"
        }
    }
}
