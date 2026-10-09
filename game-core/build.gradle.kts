plugins {
    alias(libs.plugins.kotlin.multiplatform)
}

// Pure Kotlin, zero dependency: simulation, fixed step, collisions.
kotlin {
    jvmToolchain(21)
    jvm()
    iosArm64()
    iosSimulatorArm64()

    sourceSets {
        commonTest.dependencies {
            implementation(libs.kotlin.test)
        }
    }
}
