plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "io.github.aarul5.mangaloud"
    compileSdk = 36

    defaultConfig {
        applicationId = "io.github.aarul5.mangaloud"
        minSdk = 26
        targetSdk = 36
        // CI passes its run number, so each APK is newer than the last.
        versionCode = System.getenv("BUILD_NUMBER")?.toIntOrNull() ?: 1
        // A release tag (v0.2.0) sets VERSION_NAME; other builds keep the version below.
        versionName = System.getenv("VERSION_NAME")?.takeIf { it.isNotBlank() } ?: "0.1.0"
    }

    signingConfigs {
        getByName("debug") {
            // CI signs every APK with the one saved key (DEBUG_KEYSTORE_PATH), so a new APK
            // installs over the last one. Without it, the usual ~/.android/debug.keystore is used.
            System.getenv("DEBUG_KEYSTORE_PATH")?.takeIf { it.isNotBlank() }?.let {
                storeFile = file(it)
                storePassword = "android"
                keyAlias = "androiddebugkey"
                keyPassword = "android"
            }
        }
        // Published releases are signed with the project's own release key, given by the release
        // workflow from GitHub secrets. It is never stored in the repository.
        create("release") {
            System.getenv("RELEASE_KEYSTORE_PATH")?.takeIf { it.isNotBlank() }?.let {
                storeFile = file(it)
                storePassword = System.getenv("RELEASE_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("RELEASE_KEY_ALIAS")
                keyPassword = System.getenv("RELEASE_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            // Removes unused code and resources: most of the icon library goes.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Without the release key (a test build), sign with the debug key so it installs over
            // the debug builds.
            val releaseKey = signingConfigs.getByName("release")
            signingConfig = if (releaseKey.storeFile != null) releaseKey else signingConfigs.getByName("debug")
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
    }

    sourceSets {
        // The reading guide lives with the relay (relay/skills); API mode sends the bundled copy.
        getByName("main").assets.srcDir("../../relay/skills")
    }

    testOptions {
        // android.util.Log is a stub in JVM tests; let it return quietly.
        unitTests.isReturnDefaultValues = true
    }

    packaging {
        resources {
            excludes += setOf(
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE*",
                "META-INF/NOTICE*",
                "META-INF/INDEX.LIST",
                "META-INF/*.kotlin_module",
                "META-INF/versions/9/OSGI-INF/MANIFEST.MF",
            )
        }
    }
}

dependencies {
    implementation("com.anthropic:anthropic-java:3.2.0")

    // The screens: Jetpack Compose with Material 3.
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.9.3")
    testImplementation("junit:junit:4.13.2")
}
