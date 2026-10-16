plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "app.solstone.platform.screen"
    compileSdk = 35

    defaultConfig {
        minSdk = 26
    }

    testOptions { unitTests.isReturnDefaultValues = true }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:sources"))
    implementation(project(":core:segment"))
    implementation(project(":core:spool"))
    implementation(project(":platform:fgs"))
    implementation(project(":platform:audio"))
    implementation(project(":platform:power"))
    implementation(project(":platform:persistence-room"))
    testImplementation(kotlin("test"))
    testImplementation(project(":testing"))
    testImplementation(project(":core:observer"))
}
