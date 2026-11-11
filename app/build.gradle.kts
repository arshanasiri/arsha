plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.example.callrecorder"
    compileSdk = 34

    defaultConfig {
        applicationId = "ir.nasirzadeh.callrecorder"
        minSdk = 26
        targetSdk = 34
        versionCode = 4
        versionName = "2.2"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // امضای انتشار: کلید از متغیرهای محیطی (یا gradle.properties) خوانده می‌شود و هرگز داخل مخزن نیست.
    fun prop(name: String): String? = System.getenv(name) ?: (findProperty(name) as String?)
    val ksFile = prop("KEYSTORE_FILE")
    val hasKey = ksFile != null && File(ksFile).exists() &&
        prop("KEYSTORE_PASSWORD") != null && prop("KEY_ALIAS") != null
    signingConfigs {
        if (hasKey) {
            create("release") {
                storeFile = File(ksFile!!)
                storePassword = prop("KEYSTORE_PASSWORD")
                keyAlias = prop("KEY_ALIAS")
                keyPassword = prop("KEY_PASSWORD") ?: prop("KEYSTORE_PASSWORD")
                enableV1Signing = true
                enableV2Signing = true
                enableV3Signing = true
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // بدون کلید انتشار، APK «بی‌امضا» ساخته می‌شود؛ هرگز با کلید debug امضا نمی‌شود.
            if (hasKey) signingConfig = signingConfigs.getByName("release")
        }
    }

    buildFeatures { buildConfig = true }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test:core:1.6.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test:rules:1.6.1")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
}
