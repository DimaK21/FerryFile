import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
}

val keystoreProperties = Properties()
val keystorePropertiesFile = rootProject.file("keystore.properties")
if (keystorePropertiesFile.isFile) {
    keystorePropertiesFile.inputStream().use { keystoreProperties.load(it) }
}

fun releaseSigningProperty(name: String): String? =
    providers.gradleProperty(name).orNull?.takeIf { it.isNotBlank() }
        ?: providers.environmentVariable(name).orNull?.takeIf { it.isNotBlank() }
        ?: keystoreProperties.getProperty(name)?.takeIf { it.isNotBlank() }

val releaseStoreFilePath = releaseSigningProperty("RELEASE_STORE_FILE")
val releaseStorePassword = releaseSigningProperty("RELEASE_STORE_PASSWORD")
val releaseKeyAlias = releaseSigningProperty("RELEASE_KEY_ALIAS")
val releaseKeyPassword = releaseSigningProperty("RELEASE_KEY_PASSWORD")
val releaseKeystoreFile = releaseStoreFilePath?.let { rootProject.file(it) }
val releaseSigningProperties = listOf(
    "RELEASE_STORE_FILE" to releaseStoreFilePath,
    "RELEASE_STORE_PASSWORD" to releaseStorePassword,
    "RELEASE_KEY_ALIAS" to releaseKeyAlias,
    "RELEASE_KEY_PASSWORD" to releaseKeyPassword,
)

fun requiredVersionInt(name: String): Int {
    val value = providers.gradleProperty(name).orNull
        ?: error("Missing required Gradle property: $name")
    return value.toIntOrNull()
        ?: error("Gradle property $name must be an integer, got: $value")
}

val appVersionMajor = requiredVersionInt("APP_VERSION_MAJOR")
val appVersionMinor = requiredVersionInt("APP_VERSION_MINOR")
val appVersionPatch = requiredVersionInt("APP_VERSION_PATCH")
val appVersionCode = requiredVersionInt("APP_VERSION_CODE")
val appVersionSuffix = providers.gradleProperty("APP_VERSION_SUFFIX").orNull.orEmpty()

require(appVersionMajor >= 0 && appVersionMinor >= 0 && appVersionPatch >= 0) {
    "APP_VERSION_MAJOR, APP_VERSION_MINOR and APP_VERSION_PATCH must not be negative"
}
require(appVersionCode in 1..2_100_000_000) {
    "APP_VERSION_CODE must be between 1 and 2,100,000,000"
}
require(
    appVersionSuffix.isEmpty() ||
        appVersionSuffix.matches(Regex("[0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*"))
) {
    "APP_VERSION_SUFFIX must contain only SemVer prerelease identifiers"
}

val appVersionName = buildString {
    append("$appVersionMajor.$appVersionMinor.$appVersionPatch")
    if (appVersionSuffix.isNotEmpty()) append("-$appVersionSuffix")
}

android {
    namespace = "ru.kryu.ferryfile"
    compileSdk = 36
    defaultConfig {
        applicationId = "ru.kryu.ferryfile"
        minSdk = 30
        targetSdk = 36
        versionCode = appVersionCode
        versionName = appVersionName
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    signingConfigs {
        create("release") {
            releaseKeystoreFile?.let { storeFile = it }
            storePassword = releaseStorePassword
            keyAlias = releaseKeyAlias
            keyPassword = releaseKeyPassword
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("release")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlin { jvmToolchain(11) }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "/META-INF/INDEX.LIST"
        }
    }
}

val validateReleaseSigning = tasks.register("validateReleaseSigning") {
    doLast {
        val missingProperties = releaseSigningProperties
            .filter { (_, value) -> value == null }
            .map { (name, _) -> name }
        check(missingProperties.isEmpty()) {
            "Release signing is not configured. Set ${missingProperties.joinToString()} " +
                "in keystore.properties, Gradle properties, or environment variables."
        }

        val keystoreFile = requireNotNull(releaseKeystoreFile)
        check(keystoreFile.isFile) {
            "Release keystore does not exist: ${keystoreFile.absolutePath}"
        }
    }
}

tasks.configureEach {
    if (
        name == "assembleRelease" ||
        name == "bundleRelease" ||
        name == "validateSigningRelease" ||
        name.startsWith("packageRelease") ||
        name.startsWith("signRelease")
    ) {
        dependsOn(validateReleaseSigning)
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)

    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.cio)
    implementation(libs.ktor.server.auth)
    implementation(libs.ktor.server.sessions)
    implementation(libs.ktor.server.sse)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.serialization.kotlinx.json)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.hilt.navigation.compose)
    implementation(libs.navigation.compose)
    implementation(libs.security.crypto)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.androidx.documentfile)

    testImplementation(libs.junit)
    testImplementation(libs.ktor.server.test.host)
    testImplementation(libs.mockito.kotlin)
    testImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
