plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Cada build de GitHub Actions incrementa el versionCode para que la APK se pueda
// instalar encima de la anterior sin perder la configuración.
val buildNumber = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()

android {
    namespace = "com.sukis.assistant"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.sukis.assistant"
        minSdk = 26
        targetSdk = 35
        versionCode = buildNumber
        versionName = "1.0.$buildNumber"
        ndk {
            // La Galaxy Tab S6 Lite es arm64; armeabi-v7a por si acaso. Reduce el tamaño de la APK.
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
    }

    signingConfigs {
        // Llave fija incluida en el repo: así todas las APKs comparten firma y se
        // actualizan encima. Es una app personal; no la uses para publicar en Play Store.
        create("sukis") {
            storeFile = file("sukis.keystore")
            storePassword = "sukis-android"
            keyAlias = "sukis"
            keyPassword = "sukis-android"
        }
    }

    buildTypes {
        getByName("debug") {
            signingConfig = signingConfigs.getByName("sukis")
        }
        getByName("release") {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("sukis")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    packaging {
        resources.excludes += listOf("META-INF/*.kotlin_module", "META-INF/AL2.0", "META-INF/LGPL2.1")
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    // Reconocimiento de voz offline para la palabra de activación "Oye Sukis".
    implementation("com.alphacephei:vosk-android:0.3.47@aar")
    implementation("net.java.dev.jna:jna:5.13.0@aar")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
