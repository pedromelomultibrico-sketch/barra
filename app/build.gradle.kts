plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "ao.pedromelo.barra"
    compileSdk = 34

    defaultConfig {
        applicationId = "ao.pedromelo.barra"
        minSdk = 26
        targetSdk = 34
        // número da compilação do GitHub: sobe sempre, para a atualização instalar por cima
        versionCode = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1
        versionName = "1.$versionCode"
    }

    // Assinatura fixa: todas as versões com a mesma chave
    signingConfigs {
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
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
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")
}
