plugins {
    alias(libs.plugins.kotlin.multiplatform)
}

// Pure Kotlin glue between the simulation (:game-core) and the session (:link). No UI, no BLE.
kotlin {
    jvmToolchain(21)
    jvm()
    iosArm64()
    iosSimulatorArm64()

    sourceSets {
        commonMain.dependencies {
            api(projects.gameCore)
            api(projects.link)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}
