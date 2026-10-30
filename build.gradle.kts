plugins {
    alias(libs.plugins.kotlinMultiplatform) apply false
    alias(libs.plugins.kotlinSerialization) apply false
    alias(libs.plugins.composeMultiplatform) apply false
    alias(libs.plugins.composeCompiler) apply false
    alias(libs.plugins.androidApplication) apply false
    alias(libs.plugins.androidLibrary) apply false
}

// Targets are enabled per host so the shared core and desktop simulator build anywhere:
//  - Android targets need an Android SDK (local.properties sdk.dir or ANDROID_HOME).
//  - iOS targets need a macOS host with Xcode.
// Override with -Ppartymusik.android=true|false and -Ppartymusik.ios=true|false.
val androidSdkFound: Boolean = run {
    val localProps = rootProject.file("local.properties")
    val inLocalProps = localProps.exists() && localProps.readLines().any { it.trim().startsWith("sdk.dir=") }
    inLocalProps || System.getenv("ANDROID_HOME") != null || System.getenv("ANDROID_SDK_ROOT") != null
}
val macHost: Boolean = System.getProperty("os.name").startsWith("Mac")

extra["pm.android"] = (findProperty("partymusik.android") as String?)?.toBoolean() ?: androidSdkFound
extra["pm.ios"] = (findProperty("partymusik.ios") as String?)?.toBoolean() ?: macHost

logger.lifecycle("PartyMusik targets: jvm=on android=${extra["pm.android"]} ios=${extra["pm.ios"]}")
