plugins {
  id("com.android.application")
  id("org.jetbrains.kotlin.android")
}

val buildNumber = providers.gradleProperty("appVersionCode").orNull?.toIntOrNull() ?: 1
val buildName = providers.gradleProperty("appVersionName").orNull ?: "0.1"

android {
  namespace = "dev.pagedwallpaper.rust"
  compileSdk = 35

  defaultConfig {
    applicationId = "dev.pagedwallpaper.rust"
    minSdk = 24
    targetSdk = 35
    versionCode = buildNumber
    versionName = buildName
  }

  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
  }

  kotlinOptions {
    jvmTarget = "11"
  }

  sourceSets["main"].jniLibs.srcDir("src/main/jniLibs")
}

dependencies {
  implementation("androidx.activity:activity-ktx:1.10.1")
}
