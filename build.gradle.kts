import com.vanniktech.maven.publish.AndroidSingleVariantLibrary
import com.vanniktech.maven.publish.SonatypeHost

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
    id("com.vanniktech.maven.publish") version "0.30.0"
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

    buildFeatures {
        // Prebuilt Jetpack Compose components (SignIn / UserButton).
        compose = true
    }

    composeOptions {
        // Compose compiler matched to Kotlin 1.9.24.
        kotlinCompilerExtensionVersion = libs.versions.composeCompiler.get()
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
}

dependencies {
    api(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.android)
    api(libs.kotlinx.serialization.json)
    api(libs.okhttp)
    implementation(libs.androidx.security.crypto)

    // Native passkeys / WebAuthn via the Jetpack Credential Manager. The
    // play-services-auth provider backs passkeys with Google Password Manager on
    // devices that ship it, and the Credential Manager Google-ID provider backs
    // the native "Sign in with Google" / One-Tap id_token flow.
    api(libs.androidx.credentials)
    implementation(libs.androidx.credentials.play.services.auth)
    api(libs.googleid)

    // Prebuilt Jetpack Compose UI + observable session holder. `api` for the
    // runtime (the public composables + StateFlow are part of the surface); the UI
    // libraries are implementation details of those composables. The non-UI core
    // (AtlasClient, flows, account surface) has no compile dependency on Compose.
    val composeBom = platform(libs.androidx.compose.bom)
    api(composeBom)
    api(libs.androidx.compose.runtime)
    // `ui` is `api`: `Modifier` appears in the public composables' signatures.
    api(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)

    testImplementation(libs.junit)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.kotlinx.coroutines.test)

    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.androidx.test.runner)
}

// Maven coordinate: net.atlasauth:atlas-android (the Android-library SDK — carries the
// native passkey ceremony via the Jetpack Credential Manager). The dependency-light
// pure-Kotlin/JVM SDK publishes separately as net.atlasauth:atlas-kotlin (sdks/android).
group = "net.atlasauth"
version = "0.7.0"

mavenPublishing {
    publishToMavenCentral(SonatypeHost.CENTRAL_PORTAL, automaticRelease = true)
    signAllPublications()
    // Android library: publish the release variant with sources + javadoc jars
    // (both required by Maven Central).
    configure(
        AndroidSingleVariantLibrary(
            variant = "release",
            sourcesJar = true,
            publishJavadocJar = true,
        ),
    )
    coordinates("net.atlasauth", "atlas-android", "0.7.0")
    pom {
        name.set("Atlas Android SDK")
        description.set(
            "Official Android-library SDK for the Atlas authentication platform — the " +
                "Atlas Frontend API client plus the native passkey/WebAuthn ceremony via the " +
                "Jetpack Credential Manager.",
        )
        url.set("https://atlasauth.net")
        licenses {
            license {
                name.set("MIT License")
                url.set("https://opensource.org/licenses/MIT")
                distribution.set("repo")
            }
        }
        developers {
            developer {
                id.set("atlas")
                name.set("Atlas")
                email.set("support@atlasauth.net")
                organization.set("Atlas")
                organizationUrl.set("https://atlasauth.net")
            }
        }
        scm {
            connection.set("scm:git:https://github.com/Atlas-Authorization/atlas-android.git")
            developerConnection.set("scm:git:ssh://git@github.com/Atlas-Authorization/atlas-android.git")
            url.set("https://github.com/Atlas-Authorization/atlas-android")
        }
    }
}
