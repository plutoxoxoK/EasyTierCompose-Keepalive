import java.io.StringReader
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    id("kotlin-parcelize")
}

// Semantic version from version.txt (configuration-cache safe: lazy provider).
val semanticVersion: Provider<String> = providers.fileContents(
    rootProject.layout.projectDirectory.file("version.txt")
).asText.map { it.trim() }.filter { it.isNotEmpty() }.orElse("0.0.0")

// Fixed fork version: archives build without Git and shallow clones cannot change identity.
val candidateVersionCode = 1001001

// Signing credentials for the release variant. CI passes env vars
// (KEYSTORE_PATH / KEYSTORE_PASSWORD / KEY_ALIAS / KEY_PASSWORD) decoded
// from GitHub secrets; local builds use a sibling keystore.properties file.
// Configuration-cache compatible: file content is read via providers.fileContents
// (tracked build input, evaluated lazily), env vars via providers.environmentVariable.
val keystoreProps: Provider<Properties> = providers.fileContents(
    rootProject.layout.projectDirectory.file("keystore.properties")
).asText.map { txt: String ->
    Properties().apply { load(StringReader(txt)) }
}.orElse(Properties())

val releaseStoreFile: Provider<String> = providers.environmentVariable("KEYSTORE_PATH")
    .orElse(keystoreProps.map { props: Properties -> props.getProperty("storeFile") ?: "" })
val releaseStorePassword: Provider<String> = providers.environmentVariable("KEYSTORE_PASSWORD")
    .orElse(keystoreProps.map { props: Properties -> props.getProperty("storePassword") ?: "" })
val releaseKeyAlias: Provider<String> = providers.environmentVariable("KEY_ALIAS")
    .orElse(keystoreProps.map { props: Properties -> props.getProperty("keyAlias") ?: "" })
val releaseKeyPassword: Provider<String> = providers.environmentVariable("KEY_PASSWORD")
    .orElse(keystoreProps.map { props: Properties -> props.getProperty("keyPassword") ?: "" })
val hasSigningCredentials: Provider<Boolean> = releaseStoreFile
    .map { path: String -> path.isNotBlank() }
    .orElse(false)

android {
    namespace = "cc.ptoe.easytier.compose"
    buildToolsVersion = "37.0.0"
    compileSdk {
        version = release(37) {
            minorApiLevel = 0
        }
    }

    defaultConfig {
        applicationId = "cc.ptoe.easytier.compose.keepalive"
        minSdk = 24
        targetSdk = 36
        // Placeholders; real values are wired per-variant via androidComponents
        // below so the git invocation stays configuration-cache compatible.
        versionCode = 1
        versionName = "0.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // No-TUN-only fork: do not download/build NDK or the unused privileged root JNI.
    signingConfigs {
        create("release") {
            // Release stays unsigned without explicit release credentials.
            if (hasSigningCredentials.get()) {
                storeFile = rootProject.file(releaseStoreFile.get())
                storePassword = releaseStorePassword.get()
                keyAlias = releaseKeyAlias.get()
                keyPassword = releaseKeyPassword.get()
            }
        }
    }
    buildTypes {
        debug {
            // Debug identity is always distinct from any future release signing identity.
        }
        release {
            // No silent fallback from release signing to a debug key.
            if (hasSigningCredentials.get()) {
                signingConfig = signingConfigs.getByName("release")
            }
            optimization {
                enable = false
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
        aidl = true
        buildConfig = true
    }
    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
    packaging {
        jniLibs {
            useLegacyPackaging = true
            keepDebugSymbols.add("**/*.so") // Preserve upstream bytes; never provision NDK just to strip.
        }
    }
    splits {
        abi {
            // Build one APK per ABI instead of a single fat APK containing all
            // three native libs (~45 MB). AGP forbids combining this with
            // ndk.abiFilters, so the ABI list is declared here explicitly.
            isEnable = true
            reset()
            include(*providers.gradleProperty("candidateAbis").orElse("arm64-v8a").get().split(',').toTypedArray())
            isUniversalApk = false
        }
    }
    bundle {
        abi {
            // Keep ABI splits inside the App Bundle so Google Play assembles
            // device-specific APKs on the server side.
            enableSplit = true
        }
    }
}

tasks.withType<org.gradle.api.tasks.testing.Test>().configureEach {
    systemProperty("robolectric.dependency.repo.url", "https://repo.maven.apache.org/maven2")
    // Optional transport settings come from the caller's local Gradle home, never this repo.
    listOf("http.proxyHost", "http.proxyPort", "https.proxyHost", "https.proxyPort").forEach { key ->
        providers.systemProperty(key).orNull?.let { value -> systemProperty(key, value) }
    }
    providers.gradleProperty("robolectricSdkDir").orNull?.let { directory ->
        systemProperty("robolectric.offline", "true")
        systemProperty("robolectric.dependency.dir", directory)
    }
}

// Stable version identity shared by Git checkouts and clean source archives.
androidComponents {
    onVariants { variant ->
        variant.outputs.forEach { output ->
            output.versionCode.set(candidateVersionCode)
            output.versionName.set(semanticVersion)
        }
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.tomlj)
    implementation(libs.libsu.core)
    implementation(libs.kotlin.parcelize.runtime)
    implementation(libs.libsu.service)
    testImplementation(libs.junit)
    testImplementation("org.robolectric:robolectric:4.16.1") {
        // Lifecycle tests use legacy shadows, not native graphics or a native emulator runtime.
        exclude(group = "org.robolectric", module = "nativeruntime-dist-compat")
    }
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
