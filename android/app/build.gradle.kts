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
        versionCode = 28
        versionName = "1.2.1"
        // Test APKs from the Actions tab (test-apk.yml): the next build number and a fourth version part, so the app
        // never offers the release it was built on as an update. Never published.
        providers.gradleProperty("testBuild").orNull?.let { build ->
            versionCode = versionCode!! + 1
            versionName = "$versionName.$build"
        }
    }
    signingConfigs {
        // A fixed, public test key (android/test-signing), so each debug APK installs over the last. Never a release key.
        getByName("debug") {
            storeFile = file("../test-signing/debug.p12")
            storeType = "pkcs12"
            storePassword = "android"
            keyPassword = "android"
            keyAlias = "androiddebugkey"
        }
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
            // R8 shrinks and optimises the app and its libraries; proguard-rules.pro keeps what Android reaches by name.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
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
    // Screenshot tests draw every screen on the JVM (Robolectric's native graphics), so no device or emulator is needed.
    // `-PscreenshotsDir=…` puts the pictures somewhere else than build/screenshots.
    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            all { test ->
                test.systemProperty("robolectric.graphicsMode", "NATIVE")
                test.systemProperty("roborazzi.test.record", "true")
                test.systemProperty("screenshots.dir", providers.gradleProperty("screenshotsDir")
                    .getOrElse(layout.buildDirectory.dir("screenshots").get().asFile.path))
                test.maxHeapSize = "3g"
            }
        }
    }
}
dependencies {
    implementation(project(":protocol"))
    implementation(platform("androidx.compose:compose-bom:2025.04.01"))
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.documentfile:documentfile:1.0.1")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.0")
    // Installs the baseline profile (src/main/baseline-prof.txt), so the startup path is compiled ahead of time.
    implementation("androidx.profileinstaller:profileinstaller:1.4.1")
    // Pair with QR code: the camera preview and frames (CameraX), and a pure-Java QR decoder (no Play services or ML Kit).
    implementation("androidx.camera:camera-camera2:1.4.2")
    implementation("androidx.camera:camera-lifecycle:1.4.2")
    implementation("androidx.camera:camera-view:1.4.2")
    implementation("com.google.zxing:core:3.5.3")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation("io.github.takahirom.roborazzi:roborazzi:1.40.1")
    testImplementation("io.github.takahirom.roborazzi:roborazzi-compose:1.40.1")
    testImplementation(platform("androidx.compose:compose-bom:2025.04.01"))
    testImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
