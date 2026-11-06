import com.android.build.api.artifact.SingleArtifact

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

/**
 * I-01 tamper check: SHA-256 of the RELEASE signing certificate(s), checked at runtime by
 * security/integrity/IntegrityChecker. Comma-separated for key rotation / several signers.
 *
 * Set in the project's gradle.properties (privateinbox.releaseCertSha256, since v1.0.3) or the
 * PRIVATEINBOX_RELEASE_CERT_SHA256 environment variable. Get it with:
 *     keytool -list -v -keystore release/privateinbox-release.jks -alias privateinbox   (SHA256 line)
 *     apksigner verify --print-certs app-release.apk                                    (SHA-256 digest)
 * Left empty, release builds skip the pin (and the build prints a warning). Debug builds never pin.
 */
val releaseCertSha256: String = (
    (project.findProperty("privateinbox.releaseCertSha256") as String?)
        ?: System.getenv("PRIVATEINBOX_RELEASE_CERT_SHA256")
        ?: ""
    ).trim()
releaseCertSha256.split(',', ';').map { it.trim() }.filter { it.isNotEmpty() }.forEach { d ->
    val hex = d.replace(":", "")
    if (hex.length != 64 || !hex.all { it in '0'..'9' || it.lowercaseChar() in 'a'..'f' }) {
        throw GradleException("privateinbox.releaseCertSha256: '$d' is not a SHA-256 digest")
    }
}
if (releaseCertSha256.isEmpty()) {
    logger.warn("PrivateInbox: privateinbox.releaseCertSha256 is not set; release builds will skip the signing-certificate check (I-01).")
}

android {
    namespace = "app.privateinbox"
    compileSdk = 35

    defaultConfig {
        applicationId = "app.privateinbox"
        minSdk = 26
        targetSdk = 35
        versionCode = 6
        versionName = "1.0.3"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables { useSupportLibrary = true }
        // Empty = no pin (debug builds). Release overrides below.
        buildConfigField("String", "EXPECTED_SIGNING_CERT_SHA256", "\"\"")
    }

    buildTypes {
        release {
            buildConfigField("String", "EXPECTED_SIGNING_CERT_SHA256", "\"$releaseCertSha256\"")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
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
        buildConfig = true // EXPECTED_SIGNING_CERT_SHA256 for the I-01 tamper check
    }
    packaging {
        resources { excludes += "/META-INF/{AL2.0,LGPL2.1}" }
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

// Privacy guard: fail the build if any networking / telemetry artifact sneaks in
// (directly or transitively).
configurations.configureEach {
    resolutionStrategy.eachDependency {
        val banned = listOf(
            "com.google.firebase", "com.google.android.gms", "com.squareup.okhttp3",
            "com.squareup.retrofit2", "io.ktor", "com.android.volley",
            "io.sentry", "com.crashlytics", "com.google.android.play",
        )
        if (banned.any { requested.group.startsWith(it) }) {
            throw GradleException(
                "PrivateInbox privacy guard: banned dependency ${requested.group}:${requested.name}",
            )
        }
    }
}

// Privacy guard #2: fail the build if the MERGED manifest (after every library has
// contributed) requests a forbidden permission. Runs before every assemble/check.
abstract class VerifyManifestPermissionsTask : DefaultTask() {
    @get:InputFile
    abstract val mergedManifest: RegularFileProperty

    @get:OutputFile
    abstract val report: RegularFileProperty

    @TaskAction
    fun verify() {
        val forbidden = listOf(
            "android.permission.INTERNET",
            "android.permission.ACCESS_NETWORK_STATE",
            "android.permission.CHANGE_NETWORK_STATE",
            "android.permission.ACCESS_WIFI_STATE",
            "android.permission.CHANGE_WIFI_STATE",
            "android.permission.CALL_PHONE",
            "android.permission.READ_PHONE_NUMBERS",
            "android.permission.ACCESS_FINE_LOCATION",
            "android.permission.ACCESS_COARSE_LOCATION",
            "com.google.android.gms.permission.AD_ID",
        )
        val xml = mergedManifest.get().asFile.readText()
        val requested = Regex("""<uses-permission(?:-sdk-23)?[^>]*android:name="([^"]+)"""")
            .findAll(xml).map { it.groupValues[1] }.toSet()
        val violations = forbidden.filter { it in requested }
        if (violations.isNotEmpty()) {
            throw GradleException("PrivateInbox privacy guard: merged manifest requests $violations")
        }
        report.get().asFile.writeText(requested.sorted().joinToString("\n"))
    }
}

androidComponents {
    onVariants { variant ->
        val cap = variant.name.replaceFirstChar { it.uppercase() }
        val verify = tasks.register<VerifyManifestPermissionsTask>("verify${cap}ManifestPermissions") {
            mergedManifest.set(variant.artifacts.get(SingleArtifact.MERGED_MANIFEST))
            report.set(layout.buildDirectory.file("reports/permissions/${variant.name}.txt"))
        }
        tasks.matching { it.name == "assemble$cap" || it.name == "test${cap}UnitTest" }
            .configureEach { dependsOn(verify) }
        tasks.matching { it.name == "check" }.configureEach { dependsOn(verify) }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)

    implementation(libs.androidx.navigation.compose)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    // Encrypted Room (SQLCipher). Native AAR from Maven Central; no network code at runtime.
    implementation(libs.net.zetetic.sqlcipher)
    implementation(libs.androidx.sqlite.ktx)

    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.biometric)
    implementation(libs.androidx.fragment.ktx)
    implementation(libs.androidx.work.runtime.ktx)

    // F-05: linear-time regex engine (pure Java, no dependencies, no network) for user-written rules.
    implementation(libs.google.re2j)

    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
    // JVM-only: replays Room migrations against real SQLite in unit tests. Never packaged.
    testImplementation(libs.sqlite.jdbc)

    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
