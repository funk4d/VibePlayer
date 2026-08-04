plugins {
    id("com.android.application")
}

android {
    namespace = "com.vibeplayer.tv"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.vibeplayer.tv"
        minSdk = 28
        targetSdk = 28
        versionCode = 53
        versionName = "0.53.0"

        ndk {
            abiFilters += "armeabi-v7a"
        }

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        debug { }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    lint {
        // This APK is sideloaded onto one Android 9 TV, not published to Google Play.
        disable += "ExpiredTargetSdkVersion"
    }
}

dependencies {
    val media3Version = "1.10.1"

    implementation("androidx.media3:media3-exoplayer:$media3Version")
    implementation("androidx.media3:media3-exoplayer-hls:$media3Version")
    implementation("androidx.media3:media3-exoplayer-dash:$media3Version")
    implementation("androidx.media3:media3-datasource-okhttp:$media3Version")
    implementation("androidx.media3:media3-ui:$media3Version")
    implementation("com.squareup.okhttp3:okhttp-dnsoverhttps:4.12.0")
    implementation(files("libs/media3-decoder-av1-1.10.1-armeabi-v7a.aar"))

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20250517")
}
