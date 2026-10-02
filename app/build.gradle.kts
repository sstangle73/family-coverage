import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// The version lives in gradle.properties (fc.versionCode, fc.versionName), bumped by each release: F-Droid's update
// checker and Google Play both read a plain number, not one computed from git.
val fcVersionCode = (findProperty("fc.versionCode") as String).toInt()
val fcVersionName = findProperty("fc.versionName") as String

// Release signing comes from CI variables (FC_KEYSTORE_*) or a local, gitignored keystore.properties. There is
// deliberately no fallback to the debug key: a release signed with it could never be updated by the real one.
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
fun signingValue(env: String, prop: String): String? = System.getenv(env) ?: keystoreProps.getProperty(prop)

android {
    namespace = "com.storiedev.familycoverage"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.storiedev.familycoverage"
        minSdk = 31
        // 36, not 37: apps targeting Android 17 need ACCESS_LOCAL_NETWORK to reach a server on the home network.
        targetSdk = 36
        versionCode = fcVersionCode
        versionName = fcVersionName
        // Help and the report page (GitHub Pages, from docs/); the privacy policy is StorieDev's own page.
        buildConfigField("String", "SITE_URL", "\"https://sstangle73.github.io/family-coverage/\"")
        buildConfigField("String", "PRIVACY_URL", "\"https://storiedev.com/privacy\"")
    }

    flavorDimensions += "store"
    productFlavors {
        // F-Droid and GitHub releases: automatic test texts, and the donation link in About.
        create("full") {
            dimension = "store"
            buildConfigField("boolean", "AUTO_TEXTS", "true")
            buildConfigField("String", "SOURCE_URL", "\"https://github.com/sstangle73/family-coverage\"")
            buildConfigField("String", "COFFEE_URL", "\"https://buymeacoffee.com/stevenstorie\"")
        }
        // Google Play: no SMS permissions (Play allows them only to default texting apps and nine listed uses), so a
        // test text is one tap into the phone's Messages app. No donation link anywhere (Play's payments policy).
        create("play") {
            dimension = "store"
            buildConfigField("boolean", "AUTO_TEXTS", "false")
            buildConfigField("String", "SOURCE_URL", "\"\"")
            buildConfigField("String", "COFFEE_URL", "\"\"")
        }
    }

    buildFeatures {
        buildConfig = true
    }

    signingConfigs {
        create("release") {
            val storePath = signingValue("FC_KEYSTORE_FILE", "storeFile")
            if (storePath != null) {
                storeFile = file(storePath)
                storePassword = signingValue("FC_KEYSTORE_PASSWORD", "storePassword")
                keyAlias = signingValue("FC_KEY_ALIAS", "keyAlias")
                keyPassword = signingValue("FC_KEY_PASSWORD", "keyPassword") ?: storePassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            val release = signingConfigs.getByName("release")
            // Unsigned without a key: F-Droid signs what it builds itself, and the release script refuses to publish
            // an unsigned APK.
            if (release.storeFile != null) signingConfig = release
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    lint {
        checkReleaseBuilds = false
        // English only for now, built in code; the version checks only add noise to CI.
        disable += setOf("SetTextI18n", "NewerVersionAvailable", "AndroidGradlePluginVersion")
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    // F-Droid: no Google-encrypted dependency metadata blob in the APK.
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    // QR codes for household setup: shown on one phone, scanned by the next. Pure Java, Apache-2.0, no Google services.
    implementation("com.google.zxing:core:3.5.3")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
