plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

android {
    namespace = "com.blockapp.android"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.blockapp.android"
        minSdk = 26
        targetSdk = 34
        versionCode = 3
        versionName = "1.2"
    }

    buildTypes {
        // The build to put on the phone (`./gradlew installRelease`). A debug build is what made
        // the app feel slow on a Pixel: debuggable apps run Compose without ahead-of-time
        // compilation or R8, and the frame times showed it. R8 and resource shrinking also
        // strip material-icons-extended down to the few icons actually drawn.
        //
        // Signed with the local debug key so it installs without a keystore. That is fine for a
        // personal sideloaded tool, but it means release and debug share a signature, so they can
        // be installed over each other without losing data.
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.getByName("debug")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.10.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    // Material3's Icon composable just renders an ImageVector, so the icon glyphs from the
    // Material 2 icon library are the standard source for them in a Material3 app — there is no
    // separate material3-icons-extended artifact. Needed for real vector icons (lock, shield,
    // key, etc.) in place of emoji-as-icon text.
    implementation("androidx.compose.material:material-icons-extended")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.6")
    implementation("androidx.activity:activity-compose:1.9.2")
    // Installs src/main/baseline-prof.txt (and the Compose libraries' own profiles) on a sideload.
    // Without it those profiles are only applied by the Play Store, so a sideloaded release
    // build would still JIT its first few launches, which is exactly when it felt slowest.
    implementation("androidx.profileinstaller:profileinstaller:1.3.1")

    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")
}
