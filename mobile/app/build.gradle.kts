import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// Server the app talks to by default (editable on the sign-in screen).
val serverUrl: String = (findProperty("serverUrl") as String?) ?: "https://phone-monitor-vwpz.onrender.com"
// Web Dashboard, for "open in browser" links.
val dashboardUrl: String = (findProperty("dashboardUrl") as String?) ?: "https://gods-eye-tan.vercel.app"

// Release signing from mobile/keystore.properties (git-ignored), see mobile/README.md.
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

// Firebase push (optional) from mobile/firebase.properties (git-ignored): projectId, appId,
// apiKey, senderId. Without it the app works but receives no push notifications.
val firebaseProps = Properties().apply {
    val f = rootProject.file("firebase.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
fun fb(key: String): String = firebaseProps.getProperty(key, "")

android {
    namespace = "com.hotspotmonitor.dashboard"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.hotspotmonitor.dashboard"
        minSdk = 26
        targetSdk = 35
        versionCode = 2
        versionName = "2.0.0"
        buildConfigField("String", "DEFAULT_SERVER_URL", "\"$serverUrl\"")
        buildConfigField("String", "DASHBOARD_URL", "\"$dashboardUrl\"")
        buildConfigField("String", "FIREBASE_PROJECT_ID", "\"${fb("projectId")}\"")
        buildConfigField("String", "FIREBASE_APP_ID", "\"${fb("appId")}\"")
        buildConfigField("String", "FIREBASE_API_KEY", "\"${fb("apiKey")}\"")
        buildConfigField("String", "FIREBASE_SENDER_ID", "\"${fb("senderId")}\"")
    }

    signingConfigs {
        if (keystoreProps.getProperty("storeFile") != null) {
            create("release") {
                storeFile = file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release")
        }
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            // `-PminifyDebug` shrinks a debug build with the release R8 rules, to test them against a
            // plain-http development Server (release builds only allow https).
            if (project.hasProperty("minifyDebug")) {
                isDebuggable = false // R8 only runs its full optimizer on non-debuggable builds
                isMinifyEnabled = true
                isShrinkResources = true
                proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            }
        }
    }

    // The Agent's protocol models (telemetry, commands, settings, events, Finder) are shared
    // verbatim: one definition of the wire format for the phone and this app.
    sourceSets["main"].java.srcDir("../../agent/app/src/main/java/com/hotspotmonitor/agent/protocol")

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    packaging {
        resources.excludes += setOf("META-INF/{AL2.0,LGPL2.1}", "META-INF/LICENSE*", "META-INF/NOTICE*")
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.okhttp)
    implementation(libs.zxing.core)
    implementation(libs.firebase.messaging)
    implementation(libs.kotlinx.coroutines.play.services)
    debugImplementation(libs.androidx.compose.ui.tooling)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
