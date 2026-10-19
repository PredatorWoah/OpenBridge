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
    // DPAPI, to keep saved phones' secrets readable only by this Windows account.
    implementation("net.java.dev.jna:jna-platform:5.14.0")
    testImplementation(kotlin("test"))
}

tasks.test { useJUnitPlatform() }

val appVersion = providers.gradleProperty("appVersion").orElse("0.12.2")

// The version for development runs (the installed app gets it from its launcher): diagnostics, the phone-app check.
val versionResource = tasks.register("versionResource") {
    val output = layout.buildDirectory.dir("generated/version")
    inputs.property("version", appVersion)
    outputs.dir(output)
    doLast { output.get().file("openbridge-version.txt").asFile.writeText(appVersion.get()) }
}
sourceSets["main"].resources.srcDir(versionResource)

compose.desktop {
    application {
        mainClass = "org.openbridge.desktop.MainKt"
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
