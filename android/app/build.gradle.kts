plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Release signing comes only from the environment (set by the release workflow from repository secrets).
// Without it, release builds are unsigned and local debug builds use Android's debug key as usual.
val releaseKeystore: String? = System.getenv("OPENBRIDGE_KEYSTORE")

android {
    namespace = "org.openbridge.android"
    compileSdk = 35
    defaultConfig {
        applicationId = "org.openbridge.android"
        minSdk = 26
        targetSdk = 35
        versionCode = 24
        versionName = "1.1.0"
    }
    signingConfigs {
        if (releaseKeystore != null) {
            create("release") {
                storeFile = file(releaseKeystore)
                storeType = "pkcs12"
                // PKCS12 keystores use one password for the store and the key.
                storePassword = System.getenv("OPENBRIDGE_KEYSTORE_PASSWORD")
                keyPassword = System.getenv("OPENBRIDGE_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("OPENBRIDGE_KEY_ALIAS")
            }
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            if (releaseKeystore != null) signingConfig = signingConfigs.getByName("release")
        }
    }
    buildFeatures { compose = true; buildConfig = true }
    // The animated sun and the palette are shared with the Windows app.
    sourceSets["main"].java.srcDir("../../shared/compose")
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}
dependencies {
    implementation(project(":protocol"))
    implementation(platform("androidx.compose:compose-bom:2025.04.01"))
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.documentfile:documentfile:1.0.1")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.0")
}
