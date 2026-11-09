plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.phonewheel.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.phonewheel.app"
        minSdk = 26
        targetSdk = 34
        versionCode = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()
        versionName = "1.0." + (System.getenv("GITHUB_RUN_NUMBER") ?: "0")
    }

    // Release signing: GitHub Actions passes the project's key through secrets (see docs/BUILDING.md).
    // Without it (forks, local builds) the APK is signed with the debug key so it still installs.
    val ksPath = System.getenv("PW_KEYSTORE")
    signingConfigs {
        create("release") {
            if (ksPath != null && file(ksPath).exists()) {
                storeFile = file(ksPath)
                val storePw = System.getenv("PW_KEYSTORE_PASSWORD")?.trim()
                storePassword = storePw
                keyAlias = System.getenv("PW_KEY_ALIAS")?.trim()
                // PKCS12 keystores use the same password for the key, so the store password is used
                // (avoids failures from a mistyped PW_KEY_PASSWORD secret).
                keyPassword = storePw
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = if (ksPath != null && file(ksPath).exists()) signingConfigs.getByName("release")
                            else signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }
}
// No third-party libraries: plain Android framework only.
