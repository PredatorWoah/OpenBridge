import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.compose")
}

// The Windows app: Compose for Desktop on the JVM, speaking the same protocol module as the phone.
java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}
kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }

// The animated sun and palette are shared with the Android app.
sourceSets["main"].kotlin.srcDir("../shared/compose")

dependencies {
    implementation(project(":protocol"))
    implementation(compose.desktop.currentOs)
    implementation(compose.materialIconsExtended)
    implementation("org.jmdns:jmdns:3.5.12")
    testImplementation(kotlin("test"))
}

tasks.test { useJUnitPlatform() }

val appVersion = providers.gradleProperty("appVersion").orElse("0.9.9")

compose.desktop {
    application {
        mainClass = "org.openbridge.windows.MainKt"
        nativeDistributions {
            // A setup .exe that installs for the current user only: no administrator prompt.
            targetFormats(TargetFormat.Exe)
            packageName = "OpenBridge"
            packageVersion = appVersion.get()
            description = "Move files between an Android phone and a PC"
            vendor = "OpenBridge"
            includeAllModules = true
            // scripts/fetch-scrcpy-windows.sh puts the mirroring tools here before packaging.
            appResourcesRootDir.set(layout.buildDirectory.dir("app-resources"))
            windows {
                iconFile.set(project.file("src/main/resources/OpenBridge.ico"))
                menuGroup = "OpenBridge"
                menu = true
                shortcut = true
                perUserInstall = true
                dirChooser = false
                upgradeUuid = "6c1a8f27-3b9e-4d55-9a0e-6f0b3f5c2e41"
            }
        }
    }
}
