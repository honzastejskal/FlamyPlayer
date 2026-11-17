import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// The version comes from gradle.properties, or from the release tag on CI (-PappVersion=1.2.3).
// "1.2.3" becomes version code 10203, so every release installs over the previous one.
val appVersion: String = providers.gradleProperty("appVersion").get()
val appVersionCode: Int = appVersion.split('.').map { it.toInt() }
    .let { (major, minor, patch) -> major * 10_000 + minor * 100 + patch }

// Release signing is configured outside the repository: in a keystore.properties file next to
// settings.gradle.kts, or in environment variables of the same names on CI.
val keystoreProperties = Properties().apply {
    val file = rootProject.file("keystore.properties")
    if (file.isFile) file.inputStream().use { load(it) }
}

fun signingValue(name: String): String? =
    providers.environmentVariable(name).orNull?.takeIf { it.isNotEmpty() }
        ?: keystoreProperties.getProperty(name)

val releaseKeystore: String? = signingValue("FLAMY_KEYSTORE_FILE")

android {
    namespace = "io.github.flamy69.flamyplayer"
    compileSdk = 37

    defaultConfig {
        applicationId = "io.github.flamy69.flamyplayer"
        // FlamyPlayer TV is installed beside FlamyPlayer, not over it: own id and own signing key.
        applicationIdSuffix = ".tv"
        minSdk = 26
        targetSdk = 37
        versionCode = appVersionCode
        versionName = appVersion

        // The FFmpeg decoders are native code. Phones are ARM; leaving out the builds for
        // x86 emulators halves what they add to the download.
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
    }

    packaging {
        jniLibs {
            // Compressed inside the APK: the download is 10 MB smaller, and the phone unpacks
            // only the libraries for its own processor.
            useLegacyPackaging = true
        }
    }

    lint {
        // Leaving out the x86 builds of the decoders is deliberate, see abiFilters above.
        disable += "ChromeOsAbiSupport"
    }

    signingConfigs {
        if (releaseKeystore != null) {
            create("release") {
                storeFile = rootProject.file(releaseKeystore)
                storePassword = signingValue("FLAMY_KEYSTORE_PASSWORD")
                keyAlias = signingValue("FLAMY_KEY_ALIAS")
                keyPassword = signingValue("FLAMY_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Without a release keystore the build falls back to the debug key, so that
            // `assembleRelease` still yields an installable APK on a fresh checkout. Such a
            // build cannot be installed over one signed with the real key, and vice versa.
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }

    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.extended)

    implementation(libs.media3.exoplayer)
    implementation(libs.media3.session)
    implementation(libs.media3.ui)
    // FFmpeg decoders for the sound and picture formats phones have no decoder for (DTS, AC3...).
    implementation(libs.nextlib.media3ext)

    // SMB2/3 client for playing video from Windows shares and NAS boxes, plus the RPC calls
    // needed to list the shares of a server.
    implementation(libs.smbj)
    implementation(libs.smbj.rpc) {
        // Media3 already brings the Android flavor of Guava; the JVM flavor must not replace it.
        exclude(group = "com.google.guava", module = "guava")
    }

    testImplementation(libs.junit)
}
