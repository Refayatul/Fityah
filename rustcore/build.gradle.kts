plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.refayatul.fityah.rustcore"
    compileSdk = 37
    ndkVersion = "30.0.15729638"

    defaultConfig {
        minSdk = 26
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")
        
        ndk {
            abiFilters.add("arm64-v8a")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

sourceSets {
        getByName("main") {
            // Include generated UniFFI bindings
            java.directories.add(layout.buildDirectory.dir("generated/source/uniffi/main/java").get().asFile.absolutePath)
            // Native libraries are in src/main/jniLibs
        }
    }
    
    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
}

// Manual UniFFI Binding Generation
tasks.register<Exec>("generateUniFFIBindings") {
    workingDir = file("rust")
    val cargoPath = "C:/Users/kai/.cargo/bin/cargo"
    // Use the release .so to generate bindings as it contains the latest metadata
    val soFile = file("rust/target/aarch64-linux-android/release/libfityah_rust.so")
    
    commandLine = listOf(
        cargoPath, "run", "--bin", "uniffi-bindgen", "generate",
        "--library", soFile.absolutePath,
        "--language", "kotlin",
        "--out-dir", layout.buildDirectory.dir("generated/source/uniffi/main/java").get().asFile.absolutePath
    )
}

project.tasks.configureEach {
    if (name.contains("compile") && name.contains("Kotlin")) {
        dependsOn("generateUniFFIBindings")
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    api(libs.jna)
}
