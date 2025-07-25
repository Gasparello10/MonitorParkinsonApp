plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.gabriel.wear"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.gabriel.wear"
        minSdk = 30
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
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
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }
    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation(project(":shared"))
    implementation(libs.kotlinx.coroutines.play.services)
    implementation(libs.play.services.wearable)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.core.splashscreen)

    // MUDANÇA CRÍTICA: Declarando as dependências do Wear Compose explicitamente
    // para remover qualquer ambiguidade do arquivo libs.versions.toml.
    val wearComposeVersion = "1.4.1" // Versão moderna e estável
    implementation("androidx.wear.compose:compose-material:$wearComposeVersion")
    implementation("androidx.wear.compose:compose-foundation:$wearComposeVersion")
    implementation("androidx.wear.compose:compose-ui-tooling:$wearComposeVersion")

    // Dependências padrão do Compose que são necessárias
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.tooling.preview)
    debugImplementation(libs.androidx.ui.tooling)

    androidTestImplementation(libs.androidx.ui.test.junit4)
}
