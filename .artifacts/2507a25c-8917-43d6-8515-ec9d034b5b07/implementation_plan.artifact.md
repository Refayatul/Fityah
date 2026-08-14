# Goal: Best Approach DNS Interception (Inspired by DNSNet)

This plan transitions Fityah to the "DNS Intercept Alias" model used by stable apps like **DNSNet**. This approach is the most robust way to support **Custom DNS** and **Blocklists** (like Steven Black) while ensuring web browsing is fast and never breaks.

## User Requirements Checklist
- [x] **Push to Git**: Save current project state before making architectural changes.
- [x] **Custom DNS**: User can enter any DNS (DoH or Plain) and it will be used for all device resolution.
- [x] **Custom Blocklists**: Supports Steven Black (Porn, etc.) and other host-based lists.
- [x] **Working Browsing**: Non-DNS traffic bypasses the VPN entirely for maximum speed and compatibility.

## Proposed Changes

### Pre-Execution
#### [TASK] Git Push
- `git add .`
- `git commit -m "chore: save current state before architecture shift"`
- `git push origin dev`

---

### Android App (:app)

#### [MODIFY] [DnsVpnService.kt](file:///F:/AdroidDev/curbox-android/app/src/main/java/com/refayatul/fityah/services/vpn/DnsVpnService.kt)
- **Local Intercept Alias**: Set the system DNS server to a fake local IP (e.g., `10.1.10.1`).
- **Split Tunneling**: Add a `/32` route ONLY for that fake IP.
- **Efficiency**: This ensures the Rust core ONLY ever receives DNS packets. All TCP/UDP browsing traffic will skip the VPN and go directly to the internet, fixing all speed and connection issues.

#### [MODIFY] [UdpDnsProxy.kt](file:///F:/AdroidDev/curbox-android/app/src/main/java/com/refayatul/fityah/services/vpn/UdpDnsProxy.kt)
- Ensure it handles the "Custom DNS" entered by the user (DoH or Plain).

---

### Rust Core (:rustcore)

#### [MODIFY] [lib.rs](file:///F:/AdroidDev/curbox-android/rustcore/rust/src/lib.rs)
- **Simplify**: Remove all complex TCP/IP stacking (`smoltcp`).
- **Stateless Forwarder**: Implement a simple loop that reads raw packets, checks if they are UDP, and sends them to Kotlin.
- **Reverse NAT**: For responses from Kotlin, rewrite the source IP to match the Intercept Alias (`10.1.10.1`) so the Android system accepts the packet.

## Verification Plan

### Automated Tests
- Build Rust core with `cargo ndk`.
- Full Gradle build.

### Manual Verification
- **Browsing**: Verify websites load instantly.
- **Custom DNS**: Set a custom DNS in settings and verify (via logs) that it's being used.
- **Blocklist**: Verify `porn` sites are blocked using the Steven Black list.
- **Logs**: Check for "VPN Core: Intercepting DNS at 10.1.10.1".
