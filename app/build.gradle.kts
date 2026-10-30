plugins {
    id("com.android.application")
}

android {
    namespace = "com.ivd.virtualdisplay"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.ivd.virtualdisplay"
        minSdk = 29
        targetSdk = 35
        versionCode = 3
        versionName = "1.2"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    // Gerçek harita (OpenStreetMap) — sanal ekranda doğrudan oluşturulur.
    implementation("org.osmdroid:osmdroid-android:6.1.20")
}
