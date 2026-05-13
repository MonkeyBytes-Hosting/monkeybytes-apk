plugins {
    id("com.android.application")
    id("com.google.gms.google-services")
    id("com.google.firebase.crashlytics")
    id("com.google.firebase.firebase-perf")
}

android {
    namespace = "com.monkeybytes.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.monkeybytes.app"
        minSdk = 23
        targetSdk = 36
        versionCode = 15
        versionName = "0.15.1-beta"
    }

    signingConfigs {
        create("release") {
            if (project.hasProperty("MONKEYBYTES_UPLOAD_STORE_FILE")) {
                storeFile = file(project.property("MONKEYBYTES_UPLOAD_STORE_FILE") as String)
                storePassword = project.property("MONKEYBYTES_UPLOAD_STORE_PASSWORD") as String
                keyAlias = project.property("MONKEYBYTES_UPLOAD_KEY_ALIAS") as String
                keyPassword = project.property("MONKEYBYTES_UPLOAD_KEY_PASSWORD") as String
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        create("beta") {
            initWith(getByName("release"))
            matchingFallbacks += listOf("release")
            signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures { viewBinding = true }
}

dependencies {
    implementation("androidx.core:core-ktx:1.18.0")
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("com.google.android.material:material:1.13.0")
    implementation("androidx.constraintlayout:constraintlayout:2.2.1")
    implementation("androidx.biometric:biometric:1.1.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    implementation(platform("com.google.firebase:firebase-bom:34.12.0"))
    implementation("com.google.firebase:firebase-messaging")
    implementation("com.google.firebase:firebase-analytics")
    implementation("com.google.firebase:firebase-perf")
    implementation("com.google.firebase:firebase-crashlytics")
}
