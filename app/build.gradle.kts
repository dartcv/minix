import com.android.build.api.dsl.SigningConfig
import org.gradle.api.Project
import java.io.File
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

private data class ExternalSigningInput(
    val storeFile: File,
    val storePassword: String,
    val keyAlias: String,
    val keyPassword: String,
    val storeType: String,
)

private fun Project.readExternalSigningInput(scope: String): ExternalSigningInput? {
    val environmentScope = scope.uppercase()
    val localProperties = file("signing.properties")
        .takeIf(File::isFile)
        ?.let { propertiesFile ->
            Properties().apply {
                propertiesFile.inputStream().use { load(it) }
            }
        }

    fun readValue(propertySuffix: String, environmentSuffix: String): String? =
        providers.gradleProperty("minix.$scope.$propertySuffix").orNull
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: providers.environmentVariable("MINIX_${environmentScope}_$environmentSuffix").orNull
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
            ?: localProperties?.getProperty("minix.$scope.$propertySuffix")
                ?.trim()
                ?.takeIf { it.isNotEmpty() }

    val storeFilePath = readValue("storeFile", "STORE_FILE")
    val storePassword = readValue("storePassword", "STORE_PASSWORD")
    val keyAlias = readValue("keyAlias", "KEY_ALIAS")
    val keyPassword = readValue("keyPassword", "KEY_PASSWORD")
    val storeType = readValue("storeType", "STORE_TYPE") ?: "PKCS12"
    val requiredValues = listOf(storeFilePath, storePassword, keyAlias, keyPassword)

    if (requiredValues.all { it == null }) return null
    require(requiredValues.all { it != null }) {
        "Incomplete $scope signing configuration. Set all minix.$scope.* values " +
            "or all MINIX_${environmentScope}_* environment variables."
    }

    val storeFile = file(requireNotNull(storeFilePath))
    require(storeFile.isFile) {
        "$scope signing storeFile does not exist: ${storeFile.absolutePath}"
    }
    return ExternalSigningInput(
        storeFile = storeFile,
        storePassword = requireNotNull(storePassword),
        keyAlias = requireNotNull(keyAlias),
        keyPassword = requireNotNull(keyPassword),
        storeType = storeType,
    )
}

private fun SigningConfig.applyExternalSigning(input: ExternalSigningInput) {
    storeFile = input.storeFile
    storePassword = input.storePassword
    keyAlias = input.keyAlias
    keyPassword = input.keyPassword
    storeType = input.storeType
}

private val externalDebugSigning = project.readExternalSigningInput("debug")
private val externalReleaseSigning = project.readExternalSigningInput("release")
private val releaseSigningRequired = providers.gradleProperty("minix.release.signingRequired").orNull
    ?.trim()
    ?.equals("true", ignoreCase = true)
    ?: providers.environmentVariable("MINIX_RELEASE_SIGNING_REQUIRED").orNull
        ?.trim()
        ?.equals("true", ignoreCase = true)
        ?: false

if (releaseSigningRequired && externalReleaseSigning == null) {
    error(
        "Release signing is required but no external release signing configuration was found. " +
            "Set minix.release.* Gradle properties or MINIX_RELEASE_* environment variables.",
    )
}

android {
    namespace = "me.dartcv.minix"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "me.dartcv.minix"
        minSdk = 28
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        ndk {
            abiFilters += "arm64-v8a"
        }
    }

    signingConfigs {
        if (externalDebugSigning != null) {
            create("externalDebug") {
                applyExternalSigning(requireNotNull(externalDebugSigning))
            }
        }
        if (externalReleaseSigning != null) {
            create("externalRelease") {
                applyExternalSigning(requireNotNull(externalReleaseSigning))
            }
        }
    }

    ndkVersion = "28.0.13004108"

    buildFeatures {
        compose = true
        buildConfig = true
        aidl = true
    }

    buildTypes {
        debug {
            if (externalDebugSigning != null) {
                signingConfig = signingConfigs.getByName("externalDebug")
            }
        }
        release {
            isMinifyEnabled = false
            if (externalReleaseSigning != null) {
                signingConfig = signingConfigs.getByName("externalRelease")
            }
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.activity:activity-compose:1.8.2")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.7")
    implementation("androidx.datastore:datastore-preferences:1.1.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation(platform("androidx.compose:compose-bom:2024.04.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    debugImplementation("androidx.compose.ui:ui-tooling")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.7.0")
    androidTestImplementation(platform("androidx.compose:compose-bom:2024.04.01"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
