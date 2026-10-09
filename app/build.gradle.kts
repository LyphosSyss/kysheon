plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kmp.library)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.kotlin.compose)
}

// Shared Compose Multiplatform UI. Entry points live in :androidApp and iosApp/.
kotlin {
    jvmToolchain(21)
    android {
        namespace = "epheis.lyphossyss.kysheon.app"
        compileSdk = libs.versions.androidCompileSdk.get().toInt()
        minSdk = libs.versions.androidMinSdk.get().toInt()
        androidResources {
            enable = true
        }
    }
    listOf(iosArm64(), iosSimulatorArm64()).forEach { target ->
        target.binaries.framework {
            baseName = "KysheonApp"
            isStatic = true
        }
    }

    sourceSets {
        commonMain.dependencies {
            api(libs.compose.runtime)
            implementation(libs.compose.foundation)
            implementation(libs.compose.ui)
            implementation(libs.compose.components.resources)
            implementation(libs.jetbrains.lifecycle.viewmodel.compose)
            implementation(projects.match)
        }
        androidMain.dependencies {
            implementation(libs.androidx.activity.compose)
            implementation(libs.kotlinx.coroutines.android)
        }
    }
}

compose.resources {
    packageOfResClass = "epheis.lyphossyss.kysheon.app.resources"
    publicResClass = false
}
