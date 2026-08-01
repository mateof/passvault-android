plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.mateof.passvault"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.mateof.passvault"
        // API 26 for the Android KeyStore behaviour the vault relies on, and for
        // java.time without desugaring surprises in the crypto paths.
        minSdk = 26
        targetSdk = 35
        // Raised by CI from the run number. Bumping it by hand is the usual
        // reason an update refuses to install.
        versionCode = (System.getenv("VERSION_CODE") ?: "1").toInt()
        versionName = "0.17.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        resourceConfigurations += listOf("gl", "es", "en")
    }

    signingConfigs {
        create("release") {
            val keystorePath = System.getenv("KEYSTORE_PATH")
            if (keystorePath != null) {
                storeFile = file(keystorePath)
                storePassword = System.getenv("KEYSTORE_PASSWORD") ?: ""
                keyAlias = System.getenv("KEY_ALIAS")?.takeIf { it.isNotBlank() } ?: "passvault"
                keyPassword = System.getenv("KEY_PASSWORD") ?: ""
            }
        }
    }

    buildTypes {
        release {
            // Unsigned locally, signed in CI. A developer building a release
            // build on their laptop should not need the production key.
            if (System.getenv("KEYSTORE_PATH") != null) {
                signingConfig = signingConfigs.getByName("release")
            }
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        debug {
            applicationIdSuffix = ".debug"
        }
    }

    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            isReturnDefaultValues = true
        }
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "/META-INF/INDEX.LIST"
            // Bouncy Castle and jspecify both ship an OSGi manifest under the same
            // multi-release path. Neither is used at runtime on Android, so the
            // duplicate is dropped rather than resolved.
            excludes += "/META-INF/versions/9/OSGI-INF/MANIFEST.MF"
        }
    }
}

dependencies {
    implementation(libs.core.ktx)
    implementation(libs.activity.compose)
    implementation(libs.splashscreen)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.material3)
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.foundation)
    implementation(libs.compose.icons.extended)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.lifecycle.runtime.ktx)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.navigation.compose)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.hilt.navigation.compose)

    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    implementation(libs.security.crypto)
    implementation(libs.biometric)
    implementation(libs.credentials)
    // The Play Services provider. A passkey can be stored by Google Password Manager or by a
    // hardware key; without this only the latter is offered, which on most phones means none.
    implementation(libs.credentials.play.services)
    implementation(libs.datastore.preferences)
    implementation(libs.work.runtime)

    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)

    implementation(libs.okhttp.core)
    implementation(libs.okhttp.logging)

    implementation(libs.zxing.core)
    // PdfBox-Android carries its own Bouncy Castle under the older artefact name
    // (bcprov-jdk15to18). Two copies of the same library collide at dex time, so the
    // transitive one is dropped and the newer artefact below is the only one. They are
    // build variants of the same source, and the classes PdfBox uses — ASN.1 and PKCS —
    // are stable across both.
    implementation(libs.pdfbox.android) {
        exclude(group = "org.bouncycastle")
    }
    implementation(libs.bouncycastle)

    coreLibraryDesugaring(libs.desugar.jdk.libs)

    testImplementation(libs.junit)
    testImplementation(libs.truth)
    testImplementation(libs.turbine)
    testImplementation(libs.coroutines.test)
    testImplementation(libs.robolectric)
    testImplementation(libs.room.testing)
    testImplementation(libs.androidx.test.core)
    // Compose under Robolectric. The bill of materials has to be repeated for the test
    // configuration or these two resolve with no version at all and the build fails.
    testImplementation(platform(libs.compose.bom))
    testImplementation(libs.compose.ui.test.junit4)
    // Supplies the empty activity the test rule hosts composables in. Debug rather than test,
    // because it is a manifest that has to be merged into the one under test.
    debugImplementation(libs.compose.ui.test.manifest)

    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.espresso.core)
    androidTestImplementation(libs.androidx.test.core)
}
