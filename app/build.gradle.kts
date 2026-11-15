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
        versionCode = 4
        versionName = "1.1.2"
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
