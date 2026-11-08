plugins { id("com.android.application"); id("org.jetbrains.kotlin.android"); id("org.jetbrains.kotlin.plugin.compose") }
android {
 namespace = "dev.atomos.bridge"
 compileSdk = 35
 defaultConfig {
  applicationId = "dev.atomos.bridge"
  minSdk = 29
  targetSdk = 35
  versionCode = 16
  versionName = "1.0.6"
 }
 signingConfigs { create("release") {
  storeFile = file("release-key.jks")
  storePassword = System.getenv("ANDROID_SIGNING_STORE_PASSWORD")
  keyAlias = System.getenv("ANDROID_SIGNING_KEY_ALIAS")
  keyPassword = System.getenv("ANDROID_SIGNING_KEY_PASSWORD")
 } }
 splits { abi { isEnable = true; reset(); include("arm64-v8a", "armeabi-v7a", "x86_64"); isUniversalApk = true } }
 buildTypes {
  debug { buildConfigField("boolean", "BRIDGE_DEBUG", "false") }
  release { buildConfigField("boolean", "BRIDGE_DEBUG", "false"); signingConfig = signingConfigs.getByName("release"); isMinifyEnabled = false }
 }
 buildFeatures { compose = true; buildConfig = true }
 compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
 kotlinOptions { jvmTarget = "17" }
}
dependencies {
 implementation(platform("androidx.compose:compose-bom:2024.12.01"))
 implementation("androidx.activity:activity-compose:1.10.0")
 implementation("androidx.compose.ui:ui")
 implementation("androidx.compose.ui:ui-tooling-preview")
 implementation("androidx.compose.material3:material3")
 implementation("androidx.compose.material:material-icons-extended")
 implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
 implementation("androidx.core:core-ktx:1.15.0")
 implementation("androidx.core:core-splashscreen:1.0.1")
 implementation("androidx.documentfile:documentfile:1.0.1")
 implementation("androidx.media3:media3-exoplayer:1.5.1")
 implementation("androidx.media3:media3-ui:1.5.1")
 implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
 debugImplementation("androidx.compose.ui:ui-tooling")
}
