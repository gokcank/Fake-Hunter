plugins {
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.jetbrainsKotlinAndroid)
    alias(libs.plugins.jetbrainsKotlinCompose)
}

android {
    namespace = "com.gokcank.fakehunter"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.gokcank.fakehunter"
        minSdk = 21
        targetSdk = 35
        versionCode = 2
        versionName = "1.1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    flavorDimensions += "ui"
    productFlavors {
        create("modern") {
            dimension = "ui"
            applicationIdSuffix = ".modern"
            versionNameSuffix = "-modern"
        }
        create("classic") {
            dimension = "ui"
            applicationIdSuffix = ".classic"
            versionNameSuffix = "-classic"
        }
    }

    buildFeatures {
        compose = true
        viewBinding = true
        buildConfig = true
    }

    bundle {
        language {
            enableSplit = false
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

val modernDebugImplementation by configurations.creating

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.zxing.core)

    // Jetpack Compose — packaged ONLY into the Modern flavor
    "modernImplementation"(libs.androidx.activity.compose)
    "modernImplementation"(platform(libs.androidx.compose.bom))
    "modernImplementation"(libs.androidx.ui)
    "modernImplementation"(libs.androidx.ui.graphics)
    "modernImplementation"(libs.androidx.ui.tooling.preview)
    "modernImplementation"(libs.androidx.material3)
    modernDebugImplementation(libs.androidx.ui.tooling)

    // Satisfy Kotlin Compose compiler plugin classpath check on Classic without packaging any Compose code
    "classicCompileOnly"(platform(libs.androidx.compose.bom))
    "classicCompileOnly"(libs.androidx.compose.runtime)

    testImplementation(libs.junit)
}
