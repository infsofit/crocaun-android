plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

val crocaunVersion = providers.gradleProperty("crocaunVersion").orElse("1.11").get()

fun versionCodeFor(version: String): Int {
    val match = Regex(
        """^([1-9]\d*)\.(0|[1-9]\d*)(?:\.(0|[1-9]\d*))?(?:-android\.([1-9]\d?))?$""",
    ).matchEntire(version)
    require(match != null) {
        "crocaunVersion must be 1.11, 1.11.1, 1.11-android.1, or 1.11.1-android.1"
    }

    val major = match.groupValues[1].toLong()
    val minor = match.groupValues[2].toLong()
    val patch = match.groupValues[3].ifEmpty { "0" }.toLong()
    val androidSerial = match.groupValues[4].ifEmpty { "0" }.toLong()
    require(minor <= 999 && patch <= 999 && androidSerial <= 99) {
        "Version components are outside the supported release range: $version"
    }

    val windowsVersionCode = major * 1_000_000L + minor * 1_000L + patch
    val versionCode = windowsVersionCode * 100L + androidSerial
    require(versionCode in 1..2_100_000_000L) {
        "Version is outside the supported Android versionCode range: $version"
    }
    return versionCode.toInt()
}

android {
    namespace = "com.infsofit.crocaun"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.infsofit.crocaun"
        minSdk = 30
        targetSdk = 36
        versionName = crocaunVersion
        versionCode = versionCodeFor(crocaunVersion)
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2026.04.01"))
    implementation("androidx.activity:activity-compose:1.11.0")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    val crocTransferAar = file("libs/crocaun-transfer.aar")
    require(crocTransferAar.exists()) { "Run scripts/build-transfer-aar.sh before building the Android app" }
    implementation(files(crocTransferAar))
    implementation("androidx.compose.material:material-icons-extended")
    implementation("com.google.code.gson:gson:2.13.2")

    debugImplementation("androidx.compose.ui:ui-tooling")
    testImplementation("junit:junit:4.13.2")
}
