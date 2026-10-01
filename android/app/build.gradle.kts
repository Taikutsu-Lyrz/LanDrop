plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "dev.landrop.mobile"
    compileSdk = 36

    defaultConfig {
        applicationId = "dev.landrop.mobile"
        minSdk = 26
        targetSdk = 36
        // Play updates require a new build code; local builds retain the current code by default.
        versionCode = providers.gradleProperty("landropVersionCode").getOrElse("100").toInt()
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // Clean release builds can supply freshly compiled JNI libraries for every ABI.
    providers.gradleProperty("landropJniLibsDir").orNull?.let { nativeDir ->
        sourceSets.getByName("main").jniLibs.setSrcDirs(listOf(nativeDir))
    }

    signingConfigs {
        create("release") {
            val propsFile = rootProject.file("keystore.properties")
            val props: Map<String, String> = if (propsFile.exists()) {
                propsFile.readLines()
                    .map { it.trim() }
                    .filter { it.isNotEmpty() && !it.startsWith("#") && it.contains("=") }
                    .associate { it.substringBefore("=").trim() to it.substringAfter("=").trim() }
            } else {
                emptyMap()
            }
            fun prop(name: String, env: String): String =
                props[name] ?: System.getenv(env)
                ?: error("Signing '$name' not found: set it in android/keystore.properties or env $env")
            storeFile = file(prop("storeFile", "LANDROP_STORE_FILE"))
            storePassword = prop("storePassword", "LANDROP_STORE_PASSWORD")
            keyAlias = prop("keyAlias", "LANDROP_KEY_ALIAS")
            keyPassword = prop("keyPassword", "LANDROP_KEY_PASSWORD")
        }
    }

    buildTypes {
        getByName("debug") {
            isDebuggable = true
        }
        getByName("release") {
            signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    buildFeatures {
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation("androidx.activity:activity-compose:1.11.0")
    implementation("androidx.compose.ui:ui:1.11.4")
    implementation("androidx.compose.ui:ui-tooling-preview:1.11.4")
    implementation("androidx.compose.material:material-icons-extended:1.7.8")
    implementation("androidx.compose.material3:material3:1.5.0-alpha18")
    implementation("androidx.navigation:navigation-compose:2.9.5")
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")

    debugImplementation("androidx.compose.ui:ui-tooling:1.11.4")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20180813")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test:rules:1.6.1")

    constraints {
        implementation("androidx.compose.animation:animation:1.11.4")
        implementation("androidx.compose.animation:animation-core:1.11.4")
        implementation("androidx.compose.foundation:foundation:1.11.4")
        implementation("androidx.compose.foundation:foundation-layout:1.11.4")
        implementation("androidx.compose.material:material-ripple:1.11.4")
        implementation("androidx.compose.runtime:runtime:1.11.4")
        implementation("androidx.compose.ui:ui-geometry:1.11.4")
        implementation("androidx.compose.ui:ui-graphics:1.11.4")
        implementation("androidx.compose.ui:ui-text:1.11.4")
        implementation("androidx.compose.ui:ui-unit:1.11.4")
        implementation("androidx.compose.ui:ui-util:1.11.4")
    }
}
