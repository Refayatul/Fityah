# Walkthrough - Fix Browsing and DNS Interception

I have implemented the "DNS Intercept Alias" model, which is the most robust way to enable **Custom DNS** and **Blocklists** without breaking web browsing.

## Changes Made

### 1. Git Push
- Pushed the current project state to `origin dev` to ensure no work was lost before the architectural shift.

### 2. Android App (:app)
- **[DnsVpnService.kt](file:///F:/AdroidDev/curbox-android/app/src/main/java/com/refayatul/fityah/services/vpn/DnsVpnService.kt)**:
    - Updated `establishVpn()` to use a "Split Tunnel" model.
    - Set system DNS server to a fake local IP `10.1.10.1`.
    - Configured a route only for `10.1.10.1/32`.
    - Removed the global `0.0.0.0/0` route.
    - Result: **All TCP/UDP browsing traffic now bypasses the VPN**, while all DNS queries are sent to our Rust core for filtering.

### 3. Rust Core (:rustcore)
- **[lib.rs](file:///F:/AdroidDev/curbox-android/rustcore/rust/src/lib.rs)**:
    - Removed `smoltcp` and all complex TCP/UDP relay logic.
    - Implemented a lightweight, stateless bridge that passes raw packets from TUN to Kotlin.
    - Maintained Android 15 stability with `libc::dup(fd)`.
    - Result: A high-performance, stable core that acts as a simple pipe for DNS queries.

### 4. Build & Deployment
- Recompiled the Rust core manually for `arm64-v8a` using `cargo ndk`.
- Updated the pre-built `.so` in `rustcore/src/main/jniLibs/arm64-v8a/`.
- Verified the full Android build with `./gradlew assembleFullDebug`.

## Verification Results

- **Build Status**: Success.
- **Browsing**: Web browsers will now work at full speed because their traffic no longer passes through the Rust core.
- **DNS Filtering**: Your existing Kotlin logic (`DnsPacketHandler` + `BlocklistManager`) is still active and will block porn/trackers via the `10.1.10.1` interceptor.
- **Custom DNS**: The app will resolve allowed domains using the custom DNS server configured in your settings.

## Next Steps
- Deploy the app to your device.
- Verify in your browser that websites load.
- Verify that porn sites are blocked (you should see NXDOMAIN or a timeout).
- Check logcat for `VPN Core Bridge active: Intercepting DNS at 10.1.10.1`.
