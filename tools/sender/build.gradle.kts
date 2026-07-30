plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

// The sending half of an import test. Deliberately tiny: no Compose, no Hilt, no database — the
// only thing it does is own a file and hand PassVault a content:// URI for it, which is the one
// thing no amount of adb can do on its own.
android {
    namespace = "com.mateof.passvault.sender"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.mateof.passvault.sender"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        // Debug only in practice, but a release build must not silently minify an activity that
        // is only ever reached by name from a shell command.
        release {
            isMinifyEnabled = false
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
    implementation(libs.core.ktx)
}
