plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.polex.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.polex.app"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.1"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Sign release with the debug keystore so the APK installs.
            // (For distribution beyond your own devices, switch to a
            // dedicated release keystore via a signingConfigs block.)
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
        buildConfig = true // BuildConfig.VERSION_NAME (app version shown in settings)
    }
    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.14"
    }
}

// Name APKs after the project, not the module: polex-debug.apk / polex-release.apk
// outputFileName only exists on AGP's internal impl type, so a cast is needed
// (the standard AGP 8 workaround; the public VariantOutput API lacks it).
androidComponents {
    onVariants { variant ->
        variant.outputs.forEach { output ->
            (output as com.android.build.api.variant.impl.VariantOutputImpl)
                .outputFileName.set("polex-${variant.name}.apk")
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // Compose
    implementation(platform("androidx.compose:compose-bom:2024.09.02"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")

    // Networking
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // Home screen widget (Glance)
    implementation("androidx.glance:glance-appwidget:1.1.0")

    // Background refresh for the widget
    implementation("androidx.work:work-runtime-ktx:2.9.1")

    // Unit tests (JVM, no device/emulator needed)
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
    // Real org.json for the JVM unit tests (the platform copy is a stub there).
    testImplementation("org.json:json:20240303")
}
