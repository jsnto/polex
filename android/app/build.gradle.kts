import java.util.Properties

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
        versionCode = 4
        versionName = "1.5"
    }

    // Stable release signing key. The store + its passwords live OUTSIDE
    // this repo (one permanent key per app: a fresh key per build breaks
    // in-app updates — INSTALL_FAILED_UPDATE_INCOMPATIBLE). The Docker
    // build mounts the signing dir at /release and exports the two env
    // vars below; a local release build exports them at the host paths
    // (scripts/build-apk.sh does this for the canonical location). If
    // they are unset or the files are missing, the release variant is
    // left with an empty signing config and packaging fails loudly
    // instead of minting a fresh key (the debug variant is unaffected).
    // Pointer to the key, not the key itself: see "Release signing" in
    // android/AGENTS.md.
    signingConfigs {
        create("release") {
            val storeFile = System.getenv("POLEX_RELEASE_KEYSTORE")?.let { file(it) }
            val propsFile = System.getenv("POLEX_RELEASE_KEYSTORE_PROPS")?.let { file(it) }
            if (storeFile != null && propsFile != null && storeFile.exists() && propsFile.exists()) {
                val props = Properties()
                propsFile.inputStream().use { props.load(it) }
                this.storeFile = storeFile
                storePassword = props.getProperty("storePassword")
                    ?: error("keystore.properties is missing storePassword")
                keyAlias = props.getProperty("keyAlias")
                    ?: error("keystore.properties is missing keyAlias")
                keyPassword = props.getProperty("keyPassword")
                    ?: error("keystore.properties is missing keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Signed with the stable polex release key (see signingConfigs
            // above) — never the debug keystore: the per-build regenerated
            // debug key made every published release un-updatable on
            // devices running the previous one.
            signingConfig = signingConfigs.getByName("release")
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

    testOptions {
        // Compose UI tests run on the JVM via Robolectric (no device needed);
        // they need the app's merged resources on the unit-test classpath.
        unitTests.isIncludeAndroidResources = true
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

    // Compose UI tests on the JVM (Robolectric): drives the real screens with
    // real clicks; see EditServerRemoveTest.
    testImplementation(platform("androidx.compose:compose-bom:2024.09.02"))
    testImplementation("androidx.compose.ui:ui-test-junit4")
    testImplementation("androidx.test.ext:junit:1.2.1")
    testImplementation("org.robolectric:robolectric:4.13")
}
