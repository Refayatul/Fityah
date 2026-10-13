# Fityah Project Setup Guide

This project integrates a Rust-based networking core into an Android app using UniFFI.

## 1. Rust Toolchain Setup
- Install Rust via [rustup](https://rustup.rs/).
- Add the Android target:
  ```bash
  rustup target add aarch64-linux-android
  ```
- Install `cargo-ndk`:
  ```bash
  cargo install cargo-ndk
  ```

## 2. Windows Host Setup (Crucial for UniFFI)
Because we use UniFFI Proc-macros, the build process must compile the Rust code for your Windows host machine to generate the Kotlin bindings.

### Prerequisites:
- **dlltool.exe**: Required to link the host DLL.
  1. Install [MSYS2](https://www.msys2.org/).
  2. Open the MSYS2 MINGW64 terminal and run:
     ```bash
     pacman -S mingw-w64-x86_64-binutils
     ```
  3. Add `C:\msys64\mingw64\bin` to your **System PATH**.
- **GNU Toolchain**: Ensure you are using the `x86_64-pc-windows-gnu` host toolchain for Rust.

## 3. Building the Rust Core
Build the shared library for Android arm64:
```bash
cd rustcore/rust
cargo ndk -t arm64-v8a -P 26 build --release --lib
```

**Important:** Use `--lib` to build only the library target. Without it, `cargo ndk` also cross-compiles the `uniffi-bindgen` binary and its dependencies (e.g., `clap`, `goblin`) for Android, which fails because packages like `clap_builder >= 4.6.7` use Rust edition 2024, unsupported by the NDK toolchain. The `uniffi-bindgen` binary only runs on the host; it does not need to be built for Android.

After building, copy the `.so` and generate Kotlin bindings:
```bash
cp rustcore/rust/target/aarch64-linux-android/release/libfityah_rust.so \
   rustcore/src/main/jniLibs/arm64-v8a/

cargo run --bin uniffi-bindgen generate \
  --library rustcore/rust/target/aarch64-linux-android/release/libfityah_rust.so \
  --language kotlin \
  --out-dir rustcore/build/generated/source/uniffi/main/java
```

## 4. Android Studio Configuration
- Ensure **NDK (Side-by-side)** version `30.0.15729638` is installed via SDK Manager.
- Ensure **Java 21** is selected for the Gradle JDK.

## 4. Troubleshooting
- **"CargoExtension does not exist"**: Gradle Configuration Cache must be disabled. Check `gradle.properties`.
- **"Error parsing the IDL"**: Ensure the `buildRustHostLibrary` task has run and produced a `.dll` in `rustcore/rust/target/debug/`. The generator reads this binary, not raw text.
