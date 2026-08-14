plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.rust.android.gradle)
}

android {
    namespace = "com.refayatul.fityah.rustcore"
    compileSdk = 35
    ndkVersion = "30.0.15729638"

    defaultConfig {
        minSdk = 26
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")
        
        ndk {
            // Restricted to arm64-v8a for rapid testing.
            abiFilters.add("arm64-v8a")
        }
    }

    // --- THE JAVA 21 FIX IS HERE ---
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
    
    kotlinOptions {
        jvmTarget = "21"
    }
    // -------------------------------

    sourceSets {
        getByName("main") {
            // Include generated UniFFI bindings
            java.srcDir("${project.buildDir}/generated/source/uniffi/main/java")
            jniLibs.srcDirs("${project.buildDir}/rustJniLibs/android")
        }
    }
    
    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
}

cargo {
    module = "rust"
    libname = "fityah_rust"
    targets = listOf("arm64")
    cargoCommand = "C:/Users/kai/.cargo/bin/cargo"
}

tasks.register<Exec>("buildRustHostLibrary") {
    workingDir = file("rust")
    val cargoPath = "C:/Users/kai/.cargo/bin/cargo"
    commandLine = listOf(cargoPath, "build")
}

tasks.register<Exec>("generateUniFFIBindings") {
    dependsOn("buildRustHostLibrary")
    workingDir = file("rust")
    val cargoPath = "C:/Users/kai/.cargo/bin/cargo"
    val dllFile = file("rust/target/debug/fityah_rust.dll")
    
    commandLine = listOf(
        cargoPath, "run", "--bin", "uniffi-bindgen", "generate",
        "--library", dllFile.absolutePath,
        "--language", "kotlin",
        "--out-dir", "${project.buildDir}/generated/source/uniffi/main/java"
    )
}

project.tasks.configureEach {
    if (name.contains("compile") && name.contains("Kotlin")) {
        dependsOn("generateUniFFIBindings")
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.jna)
}
