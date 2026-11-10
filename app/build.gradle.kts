plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

// Overridable from the command line / CI:
//   -PcatalogRepo=owner/name  -PappVersionName=1.2.3  -PappVersionCode=42
val catalogRepo = (findProperty("catalogRepo") as String?) ?: "kavirkobra19/KavirStore"
val appVersionName = (findProperty("appVersionName") as String?) ?: "1.0.0"
val appVersionCode = ((findProperty("appVersionCode") as String?) ?: "1").toInt()

// Signing material is read from the environment ONLY (CI secrets). Nothing is stored in the repo.
val keystorePath: String? = System.getenv("KAVIR_KEYSTORE")

android {
    namespace = "com.kavir.store"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.kavir.store"
        minSdk = 26
        targetSdk = 35
        versionCode = appVersionCode
        versionName = appVersionName
        buildConfigField("String", "CATALOG_REPO", "\"$catalogRepo\"")
        buildConfigField(
            "String",
            "CATALOG_URL",
            "\"https://raw.githubusercontent.com/$catalogRepo/main/catalog/apps.json\""
        )
    }

    signingConfigs {
        if (keystorePath != null) {
            create("release") {
                storeFile = file(keystorePath)
                storePassword = System.getenv("KAVIR_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("KAVIR_KEY_ALIAS")
                keyPassword = System.getenv("KAVIR_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            if (keystorePath != null) {
                signingConfig = signingConfigs.getByName("release")
            }
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
        buildConfig = true
    }
    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
    packaging {
        resources.excludes += setOf("/META-INF/{AL2.0,LGPL2.1}")
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.10.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")

    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("io.coil-kt:coil-compose:2.7.0")
}
