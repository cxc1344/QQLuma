import java.net.URL

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "app.qqluma"
    compileSdk = 35
    defaultConfig {
        applicationId = "app.qqluma"
        minSdk = 26
        targetSdk = 34
        versionCode = 10
        versionName = "1.1.8"
    }
    // Fixed signing for CI builds (same scheme as BotHive): when KEYSTORE_FILE is present
    // in the environment, builds are signed with the project keystore provided through CI
    // secrets, so a new build installs over the previous one. Without it, the default
    // debug key is used.
    val ciKeystore = System.getenv("KEYSTORE_FILE")?.takeIf { it.isNotBlank() }
    if (ciKeystore != null) {
        signingConfigs {
            create("ci") {
                storeFile = file(ciKeystore)
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                keyAlias = System.getenv("KEY_ALIAS")
                keyPassword = System.getenv("KEY_PASSWORD")
            }
        }
    }
    buildTypes {
        debug {
            if (ciKeystore != null) signingConfig = signingConfigs.getByName("ci")
        }
        release {
            isMinifyEnabled = false
            if (ciKeystore != null) signingConfig = signingConfigs.getByName("ci")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
    packaging {
        jniLibs { useLegacyPackaging = true }
    }
}

// The SnowLuma v2.2.1 runtime tarball (~52MB) is bundled as an asset but is too
// large for the git tree: it is hosted as a release asset on this repo and
// fetched at build time when missing (local builds that already have it in
// src/main/assets/stack/ skip the download).
// NOTE: the asset carries a ".pack" suffix on purpose — aapt2 silently
// gunzips assets whose names end in .gz during packaging, which would hand
// the app a raw tar under a different name.
val snowLumaTar = layout.projectDirectory
    .file("src/main/assets/stack/SnowLuma-v2.2.1-linux-arm64.tar.gz.pack").asFile
val fetchSnowLuma by tasks.registering {
    onlyIf { !snowLumaTar.exists() }
    doLast {
        snowLumaTar.parentFile.mkdirs()
        val url = "https://github.com/cxc1344/QQLuma/releases/download/" +
            "snowluma-2.2.1/SnowLuma-v2.2.1-linux-arm64.tar.gz"
        val tmp = File(snowLumaTar.parentFile, snowLumaTar.name + ".part")
        URL(url).openStream().use { input ->
            tmp.outputStream().use { input.copyTo(it) }
        }
        check(tmp.length() == 52_110_194L) { "SnowLuma tarball size mismatch" }
        tmp.renameTo(snowLumaTar)
    }
}
tasks.named("preBuild") { dependsOn(fetchSnowLuma) }

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.09.03"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    testImplementation("junit:junit:4.13.2")
}
