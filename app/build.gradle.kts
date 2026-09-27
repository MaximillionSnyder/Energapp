plugins {
    id("com.android.application")
    // AGP 9 trae Kotlin incorporado; el plugin del compilador Compose SI se
    // aplica aparte (asi lo hace morphdemo en este mismo equipo).
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.10"
}

/*
 * Para el release con R8, AGP 9 registra una tarea que conserva los grupos de
 * Compose y pide `org.jetbrains.kotlin:compose-group-mapping` en la version del
 * Kotlin embebido (2.2.10), que nunca se publico en Maven Central: la
 * resolucion falla y el release no compila. Se fuerza a 2.4.10, que si existe
 * y ademas coincide con el plugin de Compose que declara esta app.
 */
configurations.configureEach {
    if (name.startsWith("composeMappingProducer")) {
        resolutionStrategy.force("org.jetbrains.kotlin:compose-group-mapping:2.4.10")
    }
}

android {
    namespace = "dev.haklab.energia"
    compileSdk = 37

    defaultConfig {
        applicationId = "dev.haklab.energia"
        minSdk = 29
        // 34 = Android 14, la API del dispositivo de prueba.
        targetSdk = 34
        versionCode = 4
        versionName = "3.1"
    }

    /*
     * Firma del release: el keystore NO esta en el repositorio. Se inyecta en
     * tiempo de compilacion por variables de entorno (asi lo hace el flujo de
     * GitHub Actions con sus secretos). Si no estan, se compila sin firmar, lo
     * que permite que cualquiera verifique el proyecto con solo clonarlo.
     */
    val rutaKeystore: String? = System.getenv("ENERGIA_KEYSTORE")
    signingConfigs {
        if (rutaKeystore != null) {
            create("release") {
                storeFile = file(rutaKeystore)
                storePassword = System.getenv("ENERGIA_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("ENERGIA_KEY_ALIAS")
                keyPassword = System.getenv("ENERGIA_KEY_PASSWORD")
                enableV1Signing = false
                enableV2Signing = true
                enableV3Signing = true
            }
        }
    }

    buildTypes {
        release {
            // R8 acorta y ofusca; los recursos se podan con la misma decision.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            signingConfig = signingConfigs.findByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.19.0")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.11.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.11.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    // Compose 1.12.1 (BOM 2026.09.00) + Material3 1.4.0
    implementation("androidx.compose.ui:ui:1.12.1")
    implementation("androidx.compose.ui:ui-graphics:1.12.1")
    implementation("androidx.compose.ui:ui-tooling-preview:1.12.1")
    implementation("androidx.compose.foundation:foundation:1.12.1")
    implementation("androidx.compose.animation:animation:1.12.1")
    implementation("androidx.compose.material3:material3:1.4.0")
    debugImplementation("androidx.compose.ui:ui-tooling:1.12.1")
}
