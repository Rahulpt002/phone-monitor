import java.util.Properties

plugins {
    id("com.android.application")
}

// The Dashboard this app opens. Override with -PdashboardUrl=https://... (also update
// web/public/.well-known/assetlinks.json on that host, see mobile/README.md).
val dashboardUrl: String = (findProperty("dashboardUrl") as String?) ?: "https://gods-eye-tan.vercel.app"
val dashboardHost: String = dashboardUrl.removePrefix("https://").removePrefix("http://").substringBefore('/')

// Release signing comes from mobile/keystore.properties (git-ignored): storeFile, storePassword,
// keyAlias, keyPassword. Without it only the debug build can be made.
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    namespace = "com.hotspotmonitor.dashboard"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.hotspotmonitor.dashboard"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"
        manifestPlaceholders["dashboardUrl"] = "$dashboardUrl/"
        manifestPlaceholders["dashboardHost"] = dashboardHost
        resValue("string", "dashboard_url", dashboardUrl)
        resValue(
            "string",
            "asset_statements",
            """[{ "relation": ["delegate_permission/common.handle_all_urls"], "target": { "namespace": "web", "site": "$dashboardUrl" } }]""",
        )
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
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    // Trusted Web Activity launcher, notification delegation and WebView fallback.
    implementation("com.google.androidbrowserhelper:androidbrowserhelper:2.5.0")
}
