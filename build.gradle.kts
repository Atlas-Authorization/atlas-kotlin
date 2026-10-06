plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
    `maven-publish`
}

android {
    namespace = "net.atlasauth.atlas"
    compileSdk = 34

    defaultConfig {
        minSdk = 24
        consumerProguardFiles("consumer-rules.pro")
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

    kotlinOptions {
        jvmTarget = "1.8"
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            isReturnDefaultValues = true
        }
    }

    publishing {
        singleVariant("release") {
            withSourcesJar()
        }
    }
}

dependencies {
    api(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.android)
    api(libs.kotlinx.serialization.json)
    api(libs.okhttp)
    implementation(libs.androidx.security.crypto)

    // Native passkeys / WebAuthn via the Jetpack Credential Manager. The
    // play-services-auth provider backs passkeys with Google Password Manager on
    // devices that ship it.
    api(libs.androidx.credentials)
    implementation(libs.androidx.credentials.play.services.auth)

    testImplementation(libs.junit)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.kotlinx.coroutines.test)

    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.androidx.test.runner)
}

// Maven coordinate: net.atlasauth:atlas-android
group = "net.atlasauth"
version = "0.3.0"

publishing {
    publications {
        register<MavenPublication>("release") {
            groupId = "net.atlasauth"
            artifactId = "atlas-android"
            version = "0.3.0"
            afterEvaluate {
                from(components["release"])
            }
        }
    }
}
