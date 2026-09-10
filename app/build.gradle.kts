import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.google.services) apply false
}

/**
 * Firebase, only where somebody has configured it.
 *
 * `google-services.json` names a particular Firebase project and is not in the
 * repository, and the plugin fails the build outright when it is missing — so
 * applied unconditionally it would mean nobody could compile a fresh clone.
 *
 * Absent, the app builds and runs as it always does. It never obtains a push
 * token, `PushRepository` already treats that as a failure it can carry on
 * from, and the notification poll beside it is what delivers instead.
 */
val firebaseConfigured = file("google-services.json").exists()
if (firebaseConfigured) {
    apply(plugin = libs.plugins.google.services.get().pluginId)
}

/**
 * Release signing comes from `keystore.properties`, which is tracked here
 * together with the keystore it unlocks: this repository is private, and the
 * pair otherwise existed on one machine only. Without the file the release
 * variant still builds — unsigned — which is enough to verify R8 and resource
 * shrinking.
 */
val keystoreProperties = Properties().apply {
    val file = rootProject.file("keystore.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}

/**
 * The site's `invite_code`, from an untracked `signup.properties` (see
 * signup.properties.sample). Absent, the app omits the field, which is correct
 * for a site that does not require one.
 */
val signupProperties = Properties().apply {
    val file = rootProject.file("signup.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}

val inviteCode: String = signupProperties.getProperty("inviteCode").orEmpty()

android {
    namespace = "com.nodeloc.app"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.nodeloc.app"
        minSdk = 26
        targetSdk = 37
        versionCode = 17
        versionName = "1.1.15"

        buildConfigField("String", "INVITE_CODE", "\"$inviteCode\"")
    }

    /**
     * Where the build is going to be installed from, which is the only thing
     * the two differ about: `github` carries the self-updater and the install
     * permission it needs, `play` carries neither, because Play forbids an app
     * it distributes from updating itself. Same applicationId — they are the
     * same app, and a user moving between them keeps their data.
     */
    flavorDimensions += "distribution"
    productFlavors {
        create("github") {
            dimension = "distribution"
            isDefault = true
        }
        create("play") {
            dimension = "distribution"
            /**
             * Play reserves a version code the moment a bundle is uploaded and
             * never gives it back — a release discarded before it reaches
             * anyone still burns the number. Sharing one counter with the
             * sideloaded build therefore lets a mistake in the console force a
             * version bump on GitHub, where the code means something. The
             * offset gives the store its own sequence, still monotonic because
             * it tracks the same base.
             */
            versionCode = defaultConfig.versionCode!! + 1000
        }
    }

    bundle {
        language {
            /**
             * Play normally ships only the languages the phone is set to and
             * fetches the rest on demand. This app picks its language from the
             * account rather than from the phone, so a Vietnamese member on an
             * English handset would ask for strings the install never received
             * and get English back. Keeping every language in the base build
             * costs a few hundred kilobytes and removes the failure mode.
             */
            enableSplit = false
        }
    }

    signingConfigs {
        if (keystoreProperties.containsKey("storeFile")) {
            create("release") {
                storeFile = rootProject.file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")

                /**
                 * AGP drops the old JAR signature once minSdk is 24 or above,
                 * and by Android's own rules it is right to: v2 has been
                 * sufficient since Nougat. The rules a sideloaded APK actually
                 * meets are the installer's, though, and some of the ones this
                 * audience runs are not stock — Huawei and HarmonyOS builds in
                 * particular. Carrying v1 as well costs a few kilobytes and a
                 * second of build time, and takes one variable off the table
                 * when somebody reports that a package will not install.
                 *
                 * Note this is not a diagnosis: 1.1.3 installed for the people
                 * now reporting a failure and it had no v1 signature either, so
                 * whatever changed for them was not this.
                 *
                 * To check it took, ask apksigner about an SDK below 24 —
                 * `apksigner verify --verbose --min-sdk-version 21`. Without
                 * that flag it reports v1 as false on a correctly v1-signed
                 * APK, because at this minSdk it never needs to look.
                 */
                enableV1Signing = true
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release")
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
        aidl = false
        shaders = false
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        resources.excludes += setOf("/META-INF/{AL2.0,LGPL2.1}")
    }

    testOptions {
        unitTests {
            // The client logs failures through android.util.Log on debug
            // builds, which throws in a JVM test rather than doing nothing.
            isReturnDefaultValues = true
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

/**
 * A release built without `signup.properties` omits `invite_code` entirely, and
 * on a site with `require_invite_code` set that means nobody can register from
 * the app — while the build says nothing at all. 1.1.0 shipped exactly that way
 * from a checkout that happened not to have the file, and the first anyone knew
 * was a user stuck on the last step of signup.
 *
 * Debug builds still work anywhere, which is what keeps a fresh clone usable.
 * A release refuses.
 */
if (inviteCode.isBlank()) {
    tasks.matching { it.name.matches(Regex("(assemble|bundle).*Release")) }.configureEach {
        doFirst {
            throw GradleException(
                "signup.properties is missing or its inviteCode is blank. A release built this way " +
                    "cannot register accounts on a site that requires an invite code — see " +
                    "signup.properties.sample.",
            )
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.process)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.androidx.navigation.compose)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)

    implementation(libs.okhttp)

    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)
    implementation(libs.coil.gif)
    implementation(libs.coil.svg)

    implementation(libs.media3.exoplayer)
    implementation(libs.media3.ui)
    implementation(libs.media3.common)
    // ExoPlayer's own HTTP stack carries no cookies, and an upload on this site
    // can need the session — see VideoPlayer.
    implementation(libs.media3.datasource.okhttp)
    implementation(libs.media3.transformer)
    implementation(libs.media3.effect)

    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.work.runtime.ktx)

    // The BoM keeps these two on versions that agree with each other.
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.messaging)
    /**
     * Analytics is here for one number: how many people open the app.
     *
     * Messaging does not report that and never did — the active-user charts in
     * the Firebase console, and Cloud Messaging's own open and conversion
     * rates, are all Google Analytics for Firebase. With the SDK present the
     * counts arrive on their own; nothing in this app calls it.
     *
     * It collects per-install usage data, so the Play listing's data-safety
     * declaration has to describe it.
     */
    implementation(libs.firebase.analytics)

    implementation(libs.androidx.browser)
    implementation(libs.androidx.exifinterface)
    implementation(libs.icons.lucide)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.turbine)
}
