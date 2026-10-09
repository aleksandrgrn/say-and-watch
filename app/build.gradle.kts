plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Load local.properties so findProperty can access custom keys (e.g. tmdb.api.key)
import java.util.Properties
val localProps = Properties()
val localPropsFile = rootProject.file("local.properties")
if (localPropsFile.exists()) {
    localProps.load(localPropsFile.inputStream())
}
localProps.forEach { k: Any?, v: Any? ->
    project.extra.set(k.toString(), v)
}

android {
    namespace = "com.voicesearch"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.voicesearch"
        minSdk = 28
        targetSdk = 33
        versionCode = 3
        versionName = "1.2.0"
    }

    signingConfigs {
        // Путь и пароль — в local.properties (в .gitignore), сам keystore вне репозитория.
        // Свойств нет — конфиг не создаётся, и AGP молча кладёт app-release-unsigned.apk:
        // сборка при этом остаётся зелёной, ловит подмену ./verify-release-signing.sh.
        val keystorePath = project.findProperty("td80.keystore.path") as String?
        if (keystorePath != null && file(keystorePath).exists()) {
            create("release") {
                storeFile = file(keystorePath)
                storePassword = project.property("td80.keystore.password") as String
                keyAlias = project.property("td80.key.alias") as String
                keyPassword = storePassword
            }
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
            buildConfigField("String", "TMDB_API_KEY",
                "\"${project.findProperty("tmdb.api.key") ?: "PLACEHOLDER"}\"")
        }
        release {
            isMinifyEnabled = true
            signingConfig = signingConfigs.findByName("release")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // Ключ в release не вшивается никогда: пользователь вводит свой в диалоге.
            buildConfigField("String", "TMDB_API_KEY", "\"PLACEHOLDER\"")
        }
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    testOptions {
        unitTests {
            isReturnDefaultValues = true
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.leanback:leanback:1.0.0")
    implementation("com.google.android.material:material:1.11.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.7.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.moshi:moshi-kotlin:1.15.0")
    implementation("io.coil-kt:coil:2.5.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.7.3")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    testImplementation("com.squareup.moshi:moshi-kotlin:1.15.0")
    testImplementation("io.mockk:mockk:1.13.9")
}
