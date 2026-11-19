plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.kotlin.compose)
  alias(libs.plugins.google.services)
}

android {
  namespace = "com.omni.admin"
  compileSdk { version = release(36) { minorApiLevel = 1 } }

  defaultConfig {
    applicationId = "com.omni.adminpanel"
    minSdk = 24
    targetSdk = 36
    buildConfigField("String", "OMNI_ADMIN_API_URL", "\"https://omni-admin-api.jmredmond262.workers.dev\"")
    // GitHub Actions run numbers increase monotonically, allowing in-place Android updates.
    versionCode = (System.getenv("OMNI_ADMIN_VERSION_CODE") ?: System.getenv("GITHUB_RUN_NUMBER") ?: "4").toInt()
    versionName = "1.4.9"
  }

  signingConfigs {
    create("debugConfig") {
      // Share the persistent debug signing identity used by the main OMNI app.
      storeFile = file("${rootDir}/debug.keystore")
      storePassword = "android"
      keyAlias = "androiddebugkey"
      keyPassword = "android"
    }
  }

  buildTypes {
    create("demo") {
      initWith(getByName("debug"))
      applicationIdSuffix = ".demo"
      versionNameSuffix = "-demo"
      val demoApiUrl = System.getenv("OMNI_DEMO_WORKER_BASE_URL") ?: ""
      buildConfigField("String", "OMNI_ADMIN_API_URL", "\"${demoApiUrl}\"")
    }
    debug {
      signingConfig = signingConfigs.getByName("debugConfig")
    }
  }

  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
  }
  buildFeatures { compose = true; buildConfig = true }
}


dependencies {
  implementation(platform(libs.androidx.compose.bom))
  implementation(platform(libs.firebase.bom))
  implementation(libs.androidx.activity.compose)
  implementation(libs.androidx.core.ktx)
  implementation(libs.androidx.compose.material3)
  implementation(libs.androidx.compose.material.icons.core)
  implementation(libs.androidx.compose.material.icons.extended)
  implementation(libs.androidx.compose.ui)
  implementation(libs.androidx.compose.ui.graphics)
  implementation(libs.androidx.compose.ui.tooling.preview)
  implementation(libs.firebase.auth)
  implementation(libs.kotlinx.coroutines.play.services)
}
