import com.android.build.api.artifact.SingleArtifact

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

android {
    namespace = "app.privateinbox"
    compileSdk = 35

    defaultConfig {
        applicationId = "app.privateinbox"
        minSdk = 26
        targetSdk = 35
        versionCode = 5
        versionName = "1.0.2"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables { useSupportLibrary = true }
    }

    buildTypes {
        release {
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
        buildConfig = false
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
