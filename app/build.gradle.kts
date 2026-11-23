plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

val crocaunVersion = providers.gradleProperty("crocaunVersion").orElse("1.11").get()

fun versionCodeFor(version: String): Int {
    val parts = version.split('.').map { part -> part.toIntOrNull() }
    require(parts.size in 2..3 && parts.all { it != null }) {
        "crocaunVersion must have two or three numeric components, e.g. 1.11 or 1.11.1"
    }

    val numbers = parts.filterNotNull().toMutableList()
    while (numbers.size < 3) numbers += 0
    val (major, minor, patch) = numbers
    require(major in 1..2000 && minor in 0..999 && patch in 0..999) {
        "Version components are outside the supported Android versionCode range: $version"
    }
    return major * 1_000_000 + minor * 1_000 + patch
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
    implementation("androidx.compose.material:material-icons-extended")
    implementation("com.google.code.gson:gson:2.13.2")

    debugImplementation("androidx.compose.ui:ui-tooling")
    testImplementation("junit:junit:4.13.2")
}
