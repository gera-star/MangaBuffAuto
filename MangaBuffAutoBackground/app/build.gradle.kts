plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// The video is stored at the repository root in .idea. Copy it into generated
// Android raw resources so it is bundled in the APK and plays fully offline.
val startupSplashSource = rootProject.projectDir.parentFile.resolve(
    ".idea/video-26f8f8a9-cb79-477d-8d32-c284da329fb5.mp4"
)
val generatedSplashResDir = layout.buildDirectory.dir("generated/splashVideoResources/main").get().asFile

val generateSplashVideoResource = tasks.register<Copy>("generateSplashVideoResource") {
    from(startupSplashSource)
    into(generatedSplashResDir.resolve("raw"))
    rename("video-26f8f8a9-cb79-477d-8d32-c284da329fb5.mp4", "mangabuff_splash.mp4")
}

android {
    namespace = "com.example.myapplication"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.example.myapplication.background"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "1.0-background"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            optimization {
                enable = false
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
        aidl = true
    }

    sourceSets.getByName("main").res.srcDir(generatedSplashResDir)
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation("androidx.compose.material:material-icons-extended")
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)

    // Networking, HTML Parsing & JSON Serialization
    implementation("com.squareup.okhttp3:okhttp:5.3.0")
    implementation("org.jsoup:jsoup:1.23.2")
    implementation("com.google.code.gson:gson:2.14.0")
    implementation("androidx.webkit:webkit:1.17.1")
    implementation("dev.rikka.shizuku:api:13.6.0")
    implementation("dev.rikka.shizuku:provider:13.6.0")

    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
// Ensure the generated raw video resource exists before Android resource processing.
tasks.named("preBuild").configure {
    dependsOn(generateSplashVideoResource)
}
