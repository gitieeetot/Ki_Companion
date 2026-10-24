plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.ki.companion"
    compileSdk = 35

    val releaseSigningValues = listOf(
        System.getenv("KI_RELEASE_STORE_FILE"),
        System.getenv("KI_RELEASE_STORE_PASSWORD"),
        System.getenv("KI_RELEASE_KEY_ALIAS"),
        System.getenv("KI_RELEASE_KEY_PASSWORD"),
    )
    val hasReleaseSigning = releaseSigningValues.all { !it.isNullOrBlank() }
    require(releaseSigningValues.all { it.isNullOrBlank() } || hasReleaseSigning) {
        "Set all KI_RELEASE_STORE_FILE, KI_RELEASE_STORE_PASSWORD, KI_RELEASE_KEY_ALIAS, and KI_RELEASE_KEY_PASSWORD values to sign a release APK."
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    defaultConfig {
        applicationId = "com.ki.companion"
        minSdk = 26
        targetSdk = 35
        versionCode = providers.gradleProperty("appVersionCode").orNull?.toIntOrNull() ?: 1
        versionName = providers.gradleProperty("appVersionName").orNull ?: "0.1.0"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = file(requireNotNull(System.getenv("KI_RELEASE_STORE_FILE")))
                storePassword = requireNotNull(System.getenv("KI_RELEASE_STORE_PASSWORD"))
                keyAlias = requireNotNull(System.getenv("KI_RELEASE_KEY_ALIAS"))
                keyPassword = requireNotNull(System.getenv("KI_RELEASE_KEY_PASSWORD"))
            }
        }
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2025.02.00"))
    implementation("androidx.activity:activity-compose:1.10.0")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("androidx.core:core-ktx:1.15.0")
    debugImplementation("androidx.compose.ui:ui-tooling")
}
