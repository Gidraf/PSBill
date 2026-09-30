plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// ── Compile-time modules (tick/untick in ../modules.properties) ─────────────
extra["featureModules.app"] = "ajiriwa"
extra["featureModules.catalogue"] = listOf(
    "dashboard", "orders", "sms", "wifi", "printing", "arcade",
    "customers", "catalogue", "properties", "marketing", "stock",
)
// These still live inside MainActivity: excluded builds hide + skip them at runtime.
extra["featureModules.builtIn"] = listOf("wifi", "arcade")
extra["featureModules.package"] = "com.example.psbill.core"
extra["featureModules.featurePkg"] = "com.example.psbill.modules"
extra["featureModules.baseClass"] = "FeatureModule"
apply(from = rootProject.file("gradle/feature-modules.gradle.kts"))

@Suppress("UNCHECKED_CAST")
val enabledModules = extra["featureModules.enabled"] as List<String>
val moduleManifest = extra["featureModules.manifest"] as File?
val moduleGenSrc = extra["featureModules.genSrc"] as File

// ── Release version + signing (set by tools/server-build; local builds stay 1 / unsigned) ──
val releaseVersionCode = (findProperty("appVersionCode") as String?)?.toIntOrNull() ?: 1
val releaseVersionName = (findProperty("appVersionName") as String?) ?: "1.0"
val releaseKeystore = System.getenv("ANDROID_KEYSTORE_PATH")?.let { file(it) }?.takeIf { it.exists() }

android {
    namespace = "com.example.psbill"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.example.psbill.mobile"
        minSdk = 21
        targetSdk = 35
        versionCode = releaseVersionCode
        versionName = releaseVersionName

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (releaseKeystore != null) create("release") {
            storeFile = releaseKeystore
            storePassword = System.getenv("ANDROID_KEYSTORE_PASSWORD")
            keyAlias = System.getenv("ANDROID_KEY_ALIAS")
            keyPassword = System.getenv("ANDROID_KEY_PASSWORD") ?: System.getenv("ANDROID_KEYSTORE_PASSWORD")
        }
    }

    buildTypes {
        release {
            signingConfigs.findByName("release")?.let { signingConfig = it }
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
    }

    sourceSets {
        getByName("main") {
            kotlin.directories.add(moduleGenSrc.path)
            enabledModules.forEach { module ->
                kotlin.directories.add("src/module/$module/java")
                if (file("src/module/$module/res").exists()) res.directories.add("src/module/$module/res")
            }
        }
        // Module manifests (permissions, services, receivers) are merged as a
        // build-type overlay so the AGP manifest merger handles them normally.
        if (moduleManifest != null) {
            listOf("debug", "release").forEach { getByName(it).manifest.srcFile(moduleManifest) }
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.activity)
    implementation(libs.androidx.constraintlayout)
    
    // Compose
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)
    implementation("androidx.compose.material3:material3:1.2.1")
    implementation("androidx.print:print:1.1.0-beta01")
    
    // Networking & Utilities
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    implementation("com.google.zxing:core:3.5.3")
    
    // CameraX and ML Kit Barcode Scanning
    implementation("androidx.camera:camera-camera2:1.3.1")
    implementation("androidx.camera:camera-lifecycle:1.3.1")
    implementation("androidx.camera:camera-view:1.3.1")
    implementation("com.google.mlkit:barcode-scanning:17.2.0")
    implementation("com.google.android.gms:play-services-location:21.3.0")
    
    // Properties module: photos + OpenStreetMap map/route
    if ("properties" in enabledModules) {
        implementation("io.coil-kt:coil-compose:2.7.0")
        implementation("org.osmdroid:osmdroid-android:6.1.20")
        implementation("androidx.compose.material:material-icons-extended")
    }

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    debugImplementation(libs.androidx.compose.ui.tooling)
}
