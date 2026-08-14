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

## 3. Android Studio Configuration
- Ensure **NDK (Side-by-side)** version `30.0.15729638` is installed via SDK Manager.
- Ensure **Java 21** is selected for the Gradle JDK.

## 4. Troubleshooting
- **"CargoExtension does not exist"**: Gradle Configuration Cache must be disabled. Check `gradle.properties`.
- **"Error parsing the IDL"**: Ensure the `buildRustHostLibrary` task has run and produced a `.dll` in `rustcore/rust/target/debug/`. The generator reads this binary, not raw text.
