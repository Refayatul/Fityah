plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
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
    
    kotlinOptions {
        jvmTarget = "21"
    }

    sourceSets {
        getByName("main") {
            // Include generated UniFFI bindings
            java.srcDir("${project.buildDir}/generated/source/uniffi/main/java")
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
    api("net.java.dev.jna:jna:${libs.versions.jna.get()}@aar")
}
