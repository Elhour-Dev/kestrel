import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// Signing: local keystore.properties (git-ignored) or CI environment variables.
val signProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
fun signValue(key: String, env: String): String? =
    signProps.getProperty(key) ?: System.getenv(env)?.takeIf { it.isNotBlank() }

val appVersionCode = (project.findProperty("versionCode") as String?)?.toInt() ?: 1

kotlin {
    jvmToolchain(21)
}

android {
    namespace = "dev.kestrel.app"
    compileSdk = 37

    defaultConfig {
        applicationId = "dev.kestrel.app"
        minSdk = 26
        targetSdk = 36
        versionCode = appVersionCode
        versionName = "1.$appVersionCode"
        buildConfigField("String", "GITHUB_REPO", "\"Elhour-Dev/kestrel\"")
    }

    signingConfigs {
        val storePath = signValue("storeFile", "KESTREL_KEYSTORE")
        if (storePath != null) {
            create("kestrel") {
                storeFile = file(storePath)
                storePassword = signValue("storePassword", "KESTREL_KEYSTORE_PASSWORD")
                keyAlias = signValue("keyAlias", "KESTREL_KEY_ALIAS")
                keyPassword = signValue("keyPassword", "KESTREL_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("kestrel") ?: signingConfigs.getByName("debug")
        }
        debug {
            applicationIdSuffix = ".debug"
        }
    }

    compileOptions {
        isCoreLibraryDesugaringEnabled = true
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    packaging {
        resources.excludes += setOf("META-INF/*.md", "META-INF/LICENSE*", "META-INF/NOTICE*", "META-INF/DEPENDENCIES")
    }
}

tasks.withType<Test>().configureEach {
    testLogging {
        showStandardStreams = true
        events("passed", "failed", "skipped")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}

dependencies {
    coreLibraryDesugaring(libs.desugar)
    implementation(libs.newpipe.extractor)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.compose.icons)
    implementation(libs.compose.tooling.preview)
    implementation(libs.activity.compose)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.lifecycle.service)
    implementation(libs.lifecycle.process)
    implementation(libs.navigation.compose)
    implementation(libs.core.ktx)
    implementation(libs.webkit)
    implementation(libs.documentfile)

    implementation(libs.media3.exoplayer)
    implementation(libs.media3.hls)
    implementation(libs.media3.dash)
    implementation(libs.media3.ui)
    implementation(libs.media3.session)

    implementation(libs.coil.compose)
    implementation(libs.coil.okhttp)
    implementation(libs.okhttp)
    implementation(libs.serialization.json)
    implementation(libs.coroutines.android)

    testImplementation(libs.junit)
}
