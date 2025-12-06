plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
    id("maven-publish")
}

version = "1.0.1"
group = "com.example.datasync"

android {
    // This matches the namespace you provided in your snippet
    namespace = "com.example.datasync"
    compileSdk = 36

    defaultConfig {
        // Libraries don't have applicationId
        minSdk = 26
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
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    // 1. Core Android dependencies
    implementation(libs.androidx.core.ktx)

    // 2. Coroutines (Essential for your OfflineFirstLoader flows)
    // Note: We need the core library for pure Kotlin logic
    implementation(libs.kotlinx.coroutines.core)

    // 3. Serialization (For handling SerializationException in safeCall)
    implementation(libs.kotlinx.serialization.json)

    // 4. Ktor Core (Optional but recommended for robust safeCall)
    // If you want to catch generic IO exceptions, you don't strictly need this.
    // But if you want to support specific Ktor exceptions in the core, keep it.
    implementation(libs.ktor.client.core)

    // 5. Testing
    testImplementation(libs.junit)
    // Essential for testing Coroutines/Flows in your unit tests
    testImplementation(libs.kotlinx.coroutines.test)
}

publishing {
    publications {
        create<MavenPublication>("release") {
            groupId = project.group.toString()
            artifactId = "data-sync"
            version = project.version.toString()

            afterEvaluate {
                from(components["release"])
            }
        }
    }
}