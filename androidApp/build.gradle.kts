plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// Thin Android entry point (AGP 9 forbids com.android.application in a KMP module).
android {
    namespace = "epheis.lyphossyss.kysheon.android"
    compileSdk = libs.versions.androidCompileSdk.get().toInt()

    defaultConfig {
        applicationId = "epheis.lyphossyss.kysheon"
        minSdk = libs.versions.androidMinSdk.get().toInt()
        targetSdk = libs.versions.androidTargetSdk.get().toInt()
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
        }
    }
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(projects.app)
    implementation(libs.androidx.activity.compose)
}
