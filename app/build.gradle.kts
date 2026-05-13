import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.play.publisher)
}

// Load signing credentials from gitignored `signing.properties` at the repo
// root. Absent → release build is unsigned (e.g. on CI without secrets).
val signingPropsFile = rootProject.file("signing.properties")
val signingProps: Properties? = if (signingPropsFile.exists()) {
    Properties().apply { signingPropsFile.inputStream().use { load(it) } }
} else null

// `-PappVersionName=0.0.6 -PappVersionCode=6` lets CI drive the version from
// the git tag instead of having to commit a bump before each release. Local
// builds without these props get the fallback values below.
val cliVersionName: String? = (project.findProperty("appVersionName") as String?)?.takeIf { it.isNotBlank() }
val cliVersionCode: Int?    = (project.findProperty("appVersionCode") as String?)?.toIntOrNull()

android {
    namespace = "ch.ywesee.movementlogger"
    compileSdk = 35
    buildToolsVersion = "34.0.0"

    defaultConfig {
        applicationId = "ch.ywesee.movementlogger"
        minSdk = 26
        targetSdk = 35
        versionCode = cliVersionCode ?: 5
        versionName = cliVersionName ?: "0.0.5"
    }

    if (signingProps != null) {
        signingConfigs {
            create("release") {
                storeFile = rootProject.file(signingProps.getProperty("STORE_FILE"))
                storePassword = signingProps.getProperty("STORE_PASSWORD")
                keyAlias = signingProps.getProperty("KEY_ALIAS")
                keyPassword = signingProps.getProperty("KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (signingProps != null) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions { jvmTarget = "17" }

    buildFeatures { compose = true }

    packaging {
        resources.excludes += setOf(
            "/META-INF/{AL2.0,LGPL2.1}",
        )
    }
}

// Gradle Play Publisher: pushes the signed AAB + listing + screenshots +
// release notes to the Play Console. Service-account JSON at the repo root
// (gitignored). When the file is missing the Play tasks fail at run-time,
// not config-time, so local `assembleDebug` builds aren't affected.
play {
    val key = rootProject.file("play-service-account.json")
    // GPP inserts itself into the regular `bundleRelease` pipeline (for
    // version-code auto-bump), which means it demands credentials even for
    // a plain local signed build. Disable the whole plugin when the
    // service-account JSON is absent — Play tasks (`publishReleaseApps`,
    // etc.) still exist on the task list but bail with a clear error; the
    // rest of the build is unaffected.
    enabled.set(key.exists())
    if (key.exists()) {
        serviceAccountCredentials.set(key)
    }
    // Default to the internal-testing track. Bump to "production" once you're
    // ready to ship to the public Play Store.
    track.set("internal")
    defaultToAppBundles.set(true)
    // AUTO: merge whatever changed locally with what's on Play, never blow
    // away a higher version that's already up.
    resolutionStrategy.set(com.github.triplet.gradle.androidpublisher.ResolutionStrategy.AUTO)
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.ui)
    implementation(libs.androidx.media3.common)
    implementation(libs.androidx.media3.transformer)
    implementation(libs.androidx.media3.effect)
    implementation(libs.kotlinx.coroutines.android)

    debugImplementation(libs.androidx.compose.ui.tooling)

    testImplementation(libs.junit)
}
