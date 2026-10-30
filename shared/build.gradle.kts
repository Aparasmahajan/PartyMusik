import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.kotlinSerialization)
}

val withAndroid = rootProject.extra["pm.android"] as Boolean
val withIos = rootProject.extra["pm.ios"] as Boolean

if (withAndroid) apply(plugin = "com.android.library")

kotlin {
    jvm {
        compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
    }
    if (withAndroid) {
        androidTarget {
            compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
        }
    }
    if (withIos) {
        iosArm64()
        iosSimulatorArm64()
    }

    sourceSets {
        commonMain.dependencies {
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.json)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
        }
        if (withAndroid) {
            androidMain.dependencies {
                implementation(libs.kotlinx.coroutines.android)
                implementation(libs.androidx.core.ktx)
                implementation(libs.androidx.core.telecom)
            }
        }
    }
}

if (withAndroid) {
    extensions.configure<com.android.build.api.dsl.LibraryExtension> {
        namespace = "io.mahajan.partymusik.shared"
        compileSdk = 36
        defaultConfig { minSdk = 31 }
        compileOptions {
            sourceCompatibility = JavaVersion.VERSION_17
            targetCompatibility = JavaVersion.VERSION_17
        }
    }
}
