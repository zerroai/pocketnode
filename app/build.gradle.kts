plugins {
    id("com.android.application")
    kotlin("android")
    kotlin("plugin.compose")
}

android {
    namespace = "com.pocketnode.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.pocketnode.app"
        minSdk = 24
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
        // Features and tools switched off in this build. Set with -PmcDisableFeatures=voice_call or the environment variable MC_DISABLE_FEATURES
        // (and -PmcDisableTools / MC_DISABLE_TOOLS for tools, such as notes.* or weather.get). See docs/developers/feature-switches.md.
        fun switchList(prop: String, env: String) = ((findProperty(prop) as String?) ?: System.getenv(env)).orEmpty().trim().replace("\"", "")
        buildConfigField("String", "DISABLED_FEATURES", "\"${switchList("mcDisableFeatures", "MC_DISABLE_FEATURES")}\"")
        buildConfigField("String", "DISABLED_TOOLS", "\"${switchList("mcDisableTools", "MC_DISABLE_TOOLS")}\"")
    }

    buildTypes {
        debug {
            buildConfigField("String", "RELAY_URL", "\"${(findProperty("relayUrl") as String?) ?: "http://10.0.2.2:8088"}\"")
        }
        release {
            // Set mcRelayUrl in ~/.gradle/gradle.properties or with -P. Left empty, a release build has no relay.
            val relay = (findProperty("mcRelayUrl") as String?).orEmpty().trim()
            require(relay.isEmpty() || relay.startsWith("https://")) { "mcRelayUrl must be https" }
            buildConfigField("String", "RELAY_URL", "\"$relay\"")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    // The bundled packs (plain source: a manifest and a script each) ship inside the APK.
    sourceSets["main"].assets.srcDir(layout.buildDirectory.dir("generated/preinstalled"))

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    jvmToolchain(17)
}

val copyPreinstalledPacks = tasks.register<Copy>("copyPreinstalledPacks") {
    from("../packs") { include("*/pack.json", "*/pack.js") }
    into(layout.buildDirectory.dir("generated/preinstalled/packs"))
}
tasks.named("preBuild") { dependsOn(copyPreinstalledPacks) }

if (file("google-services.json").exists()) apply(plugin = "com.google.gms.google-services") // push wake-ups need your own Firebase file; the app builds without it

dependencies {
    implementation(project(":core"))
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
    implementation(platform("androidx.compose:compose-bom:2025.10.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.activity:activity-compose:1.11.0")
    // Some library pulls in Fragment 1.1.0; the activity-result API used by MainActivity and CaptureActivity needs 1.3.0 or newer (release lint fails otherwise).
    implementation("androidx.fragment:fragment:1.8.9")
    implementation("androidx.core:core-splashscreen:1.2.0")
    implementation("net.zetetic:sqlcipher-android:4.19.1")
    implementation("androidx.sqlite:sqlite:2.7.0")
    implementation("com.google.zxing:core:3.5.3")
    implementation("com.google.android.gms:play-services-code-scanner:16.1.0")
    implementation(platform("com.google.firebase:firebase-bom:34.19.0"))
    implementation("com.google.firebase:firebase-messaging")
}
