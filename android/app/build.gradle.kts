plugins {
    id("com.android.application")
    id("kotlin-android")
    // The Flutter Gradle Plugin must be applied after the Android and Kotlin Gradle plugins.
    id("dev.flutter.flutter-gradle-plugin")
}

android {
    namespace = "com.example.resurrect"
    compileSdk = flutter.compileSdkVersion
    // Pin to the locally installed NDK (cmdline-tools is missing, so Gradle can't
    // auto-download a different version).
    ndkVersion = "28.2.13676358"

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = JavaVersion.VERSION_17.toString()
    }

    defaultConfig {
        // TODO: Specify your own unique Application ID (https://developer.android.com/studio/build/application-id.html).
        applicationId = "com.example.resurrect"
        // You can update the following values to match your application needs.
        // For more information, see: https://flutter.dev/to/review-gradle-config.
        // Wireless Debugging pairing (the 6-digit code flow) requires Android 11 (API 30).
        minSdk = 30
        targetSdk = flutter.targetSdkVersion
        versionCode = flutter.versionCode
        versionName = flutter.versionName
    }

    buildTypes {
        release {
            // Signed with the debug key so `flutter build apk` works out of the box.
            // R8 stays off to keep the ADB/crypto reflection paths intact.
            signingConfig = signingConfigs.getByName("debug")
            isMinifyEnabled = false
            isShrinkResources = false
        }
    }

    packaging {
        resources {
            excludes += setOf(
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE",
                "META-INF/LICENSE.txt",
                "META-INF/license.txt",
                "META-INF/NOTICE",
                "META-INF/NOTICE.txt",
                "META-INF/notice.txt",
                "META-INF/*.kotlin_module",
                "META-INF/versions/9/OSGI-INF/MANIFEST.MF",
            )
        }
    }
}

flutter {
    source = "../.."
}

dependencies {
    // On-device ADB client + Android 11 wireless-debugging pairing (SPAKE2 + TLS1.3),
    // talking directly to the local adbd over loopback — no PC adb server needed.
    implementation("com.github.MuntashirAkon:libadb-android:3.1.1")
    // Conscrypt provides the TLS 1.3 stack adbd's TLS handshake needs.
    implementation("org.conscrypt:conscrypt-android:2.5.3")
    // Certificate generation is done with java.security only (see SelfSignedCert.kt) —
    // no BouncyCastle, to avoid the EdECObjectIdentifiers conflict on some devices.
}
