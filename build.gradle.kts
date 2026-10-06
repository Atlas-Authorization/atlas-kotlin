// Atlas — the official Android SDK for the Atlas auth platform.
//
// Deliberately a plain Kotlin/JVM library, not an Android application module: the
// auth core is pure Kotlin (HttpURLConnection + coroutines + a hand-rolled JSON
// reader), so it needs no Android SDK to build or unit-test. The one Android-only
// piece — EncryptedSharedPreferences — is reached through the `KeyValueStore`
// seam (see TokenStore.kt), so this module stays toolchain-light while remaining
// a drop-in for an Android app. Add the `com.android.library` plugin + an
// androidx.security dependency when wiring it into a full Android build.
import com.vanniktech.maven.publish.SonatypeHost

plugins {
    kotlin("jvm") version "2.2.20"
    `java-library`
    id("com.vanniktech.maven.publish") version "0.30.0"
}

// Maven coordinate: net.atlasauth:atlas-kotlin (the dependency-light, pure-Kotlin/JVM
// SDK). Distinct from the Android-library SDK net.atlasauth:atlas-android (sdks/kotlin),
// which carries the native passkey ceremony.
group = "net.atlasauth"
version = "0.4.0"

repositories {
    mavenCentral()
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")

    testImplementation(kotlin("test"))
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
}

kotlin {
    // No jvmToolchain pin: this box has only JDK 25, so pinning 17 would force a
    // network toolchain provision. Target 17 bytecode from whatever JDK runs Gradle.
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("passed", "skipped", "failed")
    }
}

mavenPublishing {
    publishToMavenCentral(SonatypeHost.CENTRAL_PORTAL, automaticRelease = true)
    signAllPublications()
    coordinates("net.atlasauth", "atlas-kotlin", "0.4.0")
    pom {
        name.set("Atlas Kotlin SDK")
        description.set(
            "Dependency-light, pure-Kotlin/JVM client SDK for the Atlas authentication " +
                "platform — speaks the Atlas Frontend API (FAPI) with HttpURLConnection + coroutines.",
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
            connection.set("scm:git:https://github.com/Atlas-Authorization/atlas-kotlin.git")
            developerConnection.set("scm:git:ssh://git@github.com/Atlas-Authorization/atlas-kotlin.git")
            url.set("https://github.com/Atlas-Authorization/atlas-kotlin")
        }
    }
}
