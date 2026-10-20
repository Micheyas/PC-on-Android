plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.mikiyas.pconandroid"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.mikiyas.pconandroid"
        minSdk = 26
        // Deliberately 28, the same choice Winlator / Termux / MiceWine
        // make: apps targeting API 29+ run in a SELinux domain that
        // forbids PROT_EXEC on app-data files (W^X execmod), and Wine
        // dies loading its first DLL — "map_image_into_view failed to
        // set protection ... noexec filesystem?" (seen on-device
        // 2026-10-20). Targeting 28 keeps the legacy domain where an
        // app may execute code from its own data directory, which a
        // Wine-class emulator fundamentally needs. Trade-off accepted:
        // no Play Store listing until a different runtime story exists;
        // distribution is sideload / direct APK.
        targetSdk = 28
        versionCode = 17
        versionName = "0.17"

        ndk {
            abiFilters += "arm64-v8a"
        }
    }

    packaging {
        jniLibs {
            // Engine binaries (proot & friends) must be extracted as real
            // files into nativeLibraryDir so the app can execute them.
            useLegacyPackaging = true
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
}
