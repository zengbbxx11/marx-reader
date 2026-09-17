import java.security.MessageDigest

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}


// Compute the content fingerprint at build time, keeping startup independent of library size.
val libraryAssets = fileTree("src/main/assets/library") { include("**/*.json") }
val generatedLibraryAssets = layout.buildDirectory.dir("generated/libraryFingerprint/assets")
val generateLibraryFingerprint by tasks.registering {
    inputs.files(libraryAssets).withPathSensitivity(PathSensitivity.RELATIVE)
    outputs.dir(generatedLibraryAssets)
    doLast {
        val digest = MessageDigest.getInstance("SHA-256")
        val assetRoot = file("src/main/assets/library")
        libraryAssets.files.sortedBy { it.relativeTo(assetRoot).invariantSeparatorsPath }.forEach { asset ->
            digest.update(asset.relativeTo(assetRoot).invariantSeparatorsPath.toByteArray(Charsets.UTF_8))
            digest.update(0.toByte())
            asset.inputStream().use { input ->
                val buffer = ByteArray(8192)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    digest.update(buffer, 0, count)
                }
            }
            digest.update(0.toByte())
        }
        val output = generatedLibraryAssets.get().file("library/content.sha256").asFile
        output.parentFile.mkdirs()
        output.writeText(digest.digest().joinToString("") { "%02x".format(it) })
    }
}
tasks.named("preBuild").configure { dependsOn(generateLibraryFingerprint) }

android {
    sourceSets.getByName("main").assets.srcDir(generatedLibraryAssets)
    namespace = "org.marxreader.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "org.marxreader.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 15
        versionName = "0.4.7-preview.20260917"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables.useSupportLibrary = true
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions.jvmTarget = "17"
    buildFeatures.compose = true
    packaging.resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2025.02.00")
    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")

    testImplementation("junit:junit:4.13.2")
    // Real org.json for unit tests: android.jar only ships throwing stubs, which made parseCatalog untestable.
    testImplementation("org.json:json:20240303")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
}
