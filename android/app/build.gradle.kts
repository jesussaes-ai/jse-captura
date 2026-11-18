plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "mx.jse.captura"
    compileSdk = 35

    defaultConfig {
        applicationId = "mx.jse.captura"
        minSdk = 29          // Android 10: MediaProjection en servicio de primer plano y captura de audio interno
        targetSdk = 35
        versionCode = 5
        versionName = "0.2.0"
    }

    signingConfigs {
        // Firma propia si existen las variables (CI o tu PC); si no, la de depuración.
        create("jse") {
            val ks = System.getenv("JSE_KEYSTORE")
            if (ks != null && file(ks).exists()) {
                storeFile = file(ks)
                storePassword = System.getenv("JSE_KEYSTORE_PASS")
                keyAlias = System.getenv("JSE_KEY_ALIAS") ?: "jse"
                keyPassword = System.getenv("JSE_KEY_PASS") ?: System.getenv("JSE_KEYSTORE_PASS")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = if (System.getenv("JSE_KEYSTORE") != null) signingConfigs.getByName("jse") else signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { buildConfig = true }
    // "Inspector": Android Lint corre en CI y detiene la entrega si encuentra errores.
    lint { abortOnError = true; checkReleaseBuilds = false; htmlReport = true; textReport = true; textOutput = file("stdout"); explainIssues = false }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.work:work-runtime-ktx:2.9.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
}
