use std::sync::Arc;
use std::sync::atomic::{AtomicBool, Ordering};
use log::info;

#[cfg(unix)]
use std::os::unix::io::{FromRawFd, AsRawFd, OwnedFd};

uniffi::setup_scaffolding!();

#[uniffi::export]
pub fn rust_init_logger() {
    android_logger::init_once(
        android_logger::Config::default()
            .with_max_level(log::LevelFilter::Info)
            .with_tag("FityahRust"),
    );
}

#[uniffi::export]
pub fn ping_rust() -> String {
    "Pong from Rust via UniFFI!".to_string()
}

#[uniffi::export(callback_interface)]
pub trait DnsCallback: Send + Sync {
    fn on_dns_packet(&self, packet: Vec<u8>, src_ip: String, src_port: u16) -> Option<Vec<u8>>;
}

#[uniffi::export(callback_interface)]
pub trait SocketProtector: Send + Sync {
    fn protect_socket(&self, fd: i32) -> bool;
}

#[derive(uniffi::Object)]
pub struct VpnController {
    stop_signal: Arc<AtomicBool>,
}

#[uniffi::export]
impl VpnController {
    #[uniffi::constructor]
    pub fn new() -> Arc<Self> {
        Arc::new(VpnController {
            stop_signal: Arc::new(AtomicBool::new(false)),
        })
    }

    #[allow(unused_variables)]
    pub fn start(&self, fd: i32, callback: Box<dyn DnsCallback>, _protector: Box<dyn SocketProtector>) {
        info!("Starting Fityah VPN Core v6.1 (Attribution Mode) with fd: {}", fd);
        self.stop_signal.store(false, Ordering::SeqCst);

        #[cfg(unix)]
        {
            let stop_signal = self.stop_signal.clone();
            std::thread::spawn(move || {
                use log::error;
                let rt = match tokio::runtime::Builder::new_current_thread()
                    .enable_all()
                    .build()
                {
                    Ok(rt) => rt,
                    Err(e) => {
                        error!("Failed to create tokio runtime: {:?}", e);
                        return;
                    }
                };
                rt.block_on(async move {
                    if let Err(e) = run_dns_bridge_loop(fd, callback, stop_signal).await {
                        error!("VPN Bridge Loop error: {:?}", e);
                    }
                });
            });
        }
    }

    pub fn stop(&self) {
        info!("Stopping Fityah VPN Core");
        self.stop_signal.store(true, Ordering::SeqCst);
        // The loop uses tokio::select! with a 1-second timer, so it will
        // observe the stop signal within at most 1 second even if no
        // new packets arrive.
    }
}

#[cfg(unix)]
async fn run_dns_bridge_loop(
    fd: i32,
    callback: Box<dyn DnsCallback>,
    stop_signal: Arc<AtomicBool>,
) -> anyhow::Result<()> {
    use std::time::Duration;
    use std::net::{Ipv4Addr, Ipv6Addr};
    use tokio::io::unix::AsyncFd;
    use tokio::io::Interest;
    use etherparse::{SlicedPacket, TransportSlice, NetSlice};
    use log::{debug, error, warn};

    let duped_fd = unsafe { libc::dup(fd) };
    if duped_fd < 0 {
        return Err(anyhow::anyhow!("Failed to duplicate TUN fd"));
    }

    unsafe {
        let flags = libc::fcntl(duped_fd, libc::F_GETFL);
        libc::fcntl(duped_fd, libc::F_SETFL, flags | libc::O_NONBLOCK);
    }

    let owned_fd = unsafe { OwnedFd::from_raw_fd(duped_fd) };
    let async_fd = AsyncFd::with_interest(owned_fd, Interest::READABLE)?;
    info!("VPN Core Bridge active: Intercepting DNS at 10.1.10.1");

    let mut buf = [0u8; 4096];
    let mut packets_seen: u64 = 0;
    let mut dns_forwarded: u64 = 0;

    loop {
        if stop_signal.load(Ordering::SeqCst) {
            debug!(
                "Stop signal received after {} packets ({} forwarded)",
                packets_seen, dns_forwarded
            );
            break;
        }

        tokio::select! {
            result = async_fd.readable() => {
                let mut guard = match result {
                    Ok(g) => g,
                    Err(e) => {
                        error!("AsyncFd readable error: {:?}", e);
                        break;
                    }
                };

                let raw_fd = async_fd.as_raw_fd();
                let n = unsafe {
                    libc::read(raw_fd, buf.as_mut_ptr() as *mut libc::c_void, buf.len())
                };

                if n > 0 {
                    packets_seen += 1;
                    let packet_data = buf[..n as usize].to_vec();

                    let mut src_ip = String::new();
                    let mut src_port = 0u16;
                    let mut is_dns = false;

                    if let Ok(sliced) = SlicedPacket::from_ip(&packet_data) {
                        if let Some(NetSlice::Ipv4(h)) = sliced.net {
                            src_ip = Ipv4Addr::from(h.header().source()).to_string();
                        } else if let Some(NetSlice::Ipv6(h)) = sliced.net {
                            src_ip = Ipv6Addr::from(h.header().source()).to_string();
                        }

                        if let Some(TransportSlice::Udp(u)) = sliced.transport {
                            src_port = u.source_port();
                            is_dns = u.destination_port() == 53;
                        }
                    }

                    // Only forward DNS (UDP port 53) packets to Kotlin. Non-DNS
                    // traffic should not enter the TUN under the intercept alias
                    // model, but if it does, drop it silently rather than paying
                    // the JNI/Kotlin coroutine cost for a no-op response.
                    if !is_dns {
                        continue;
                    }

                    dns_forwarded += 1;
                    if let Some(response_packet) =
                        callback.on_dns_packet(packet_data, src_ip, src_port)
                    {
                        if !response_packet.is_empty() {
                            let written = unsafe {
                                libc::write(
                                    raw_fd,
                                    response_packet.as_ptr() as *const libc::c_void,
                                    response_packet.len(),
                                )
                            };
                            if written < 0 {
                                let err = std::io::Error::last_os_error();
                                warn!("Failed to write response packet: {}", err);
                            } else if written != response_packet.len() as isize {
                                warn!(
                                    "Short write: requested {} bytes, wrote {}",
                                    response_packet.len(),
                                    written
                                );
                            }
                        }
                    }
                } else if n < 0 {
                    let err = std::io::Error::last_os_error();
                    if err.kind() == std::io::ErrorKind::WouldBlock {
                        guard.clear_ready();
                    } else if err.raw_os_error() == Some(libc::EINTR) {
                        // Interrupted by signal — retry the loop.
                        continue;
                    } else {
                        error!("VPN Bridge Loop read error: {}", err);
                        break;
                    }
                } else {
                    // n == 0: EOF — the TUN fd was closed.
                    debug!("TUN fd EOF (closed)");
                    break;
                }
            }

            // Wake up every second to check the stop signal even when idle.
            // This guarantees stop() takes effect within ~1 second regardless
            // of traffic patterns.
            _ = tokio::time::sleep(Duration::from_secs(1)) => {}
        }
    }

    info!(
        "VPN Core Bridge stopped — {} packets seen, {} DNS queries forwarded",
        packets_seen, dns_forwarded
    );
    Ok(())
}

#[cfg(all(unix, test))]
mod tests {
    use super::*;
    use std::net::Ipv4Addr;

    /// Build a minimal IPv4 UDP packet to the given destination port with a
    /// one-byte DNS payload, enough to exercise the parse + filter logic.
    fn build_ipv4_udp_packet(src_ip: Ipv4Addr, dst_port: u16) -> Vec<u8> {
        let ip_header_len: usize = 20;
        let udp_header_len: usize = 8;
        let dns_payload_len: usize = 1;
        let total_len = ip_header_len + udp_header_len + dns_payload_len;

        let mut packet = vec![0u8; total_len];

        // IPv4 header
        packet[0] = 0x45; // version 4, IHL 5 (20 bytes)
        packet[1] = 0x00;
        packet[2] = (total_len as u16 >> 8) as u8;
        packet[3] = (total_len as u16 & 0xFF) as u8;
        packet[4] = 0x00;
        packet[5] = 0x00;
        packet[6] = 0x00;
        packet[7] = 0x00;
        packet[8] = 64;  // TTL
        packet[9] = 17;  // protocol = UDP
        packet[10] = 0;
        packet[11] = 0;
        packet[12..16].copy_from_slice(&src_ip.octets());
        packet[16..20].copy_from_slice(&[10, 1, 10, 1]); // 10.1.10.1

        // UDP header
        packet[ip_header_len] = 0x12; // src port hi
        packet[ip_header_len + 1] = 0x34; // src port lo
        packet[ip_header_len + 2] = (dst_port >> 8) as u8;
        packet[ip_header_len + 3] = (dst_port & 0xFF) as u8;
        let udp_len = (udp_header_len + dns_payload_len) as u16;
        packet[ip_header_len + 4] = (udp_len >> 8) as u8;
        packet[ip_header_len + 5] = (udp_len & 0xFF) as u8;

        // DNS payload
        packet[ip_header_len + udp_header_len] = 0x00;

        packet
    }

    /// Extract DNS-relevant fields from a parsed packet, mirroring the
    /// inline logic in run_dns_bridge_loop for testability.
    fn extract_dns_fields(packet: &[u8]) -> (String, u16, bool) {
        use etherparse::{SlicedPacket, TransportSlice, NetSlice};
        let mut src_ip = String::new();
        let mut src_port = 0u16;
        let mut is_dns = false;

        if let Ok(sliced) = SlicedPacket::from_ip(packet) {
            if let Some(NetSlice::Ipv4(h)) = sliced.net {
                src_ip = Ipv4Addr::from(h.header().source()).to_string();
            } else if let Some(NetSlice::Ipv6(h)) = sliced.net {
                src_ip = Ipv6Addr::from(h.header().source()).to_string();
            }
            if let Some(TransportSlice::Udp(u)) = sliced.transport {
                src_port = u.source_port();
                is_dns = u.destination_port() == 53;
            }
        }
        (src_ip, src_port, is_dns)
    }

    #[test]
    fn test_dns_port_detection() {
        let packet = build_ipv4_udp_packet(Ipv4Addr::new(192, 168, 1, 100), 53);
        let (src_ip, src_port, is_dns) = extract_dns_fields(&packet);

        assert_eq!(src_ip, "192.168.1.100");
        assert_eq!(src_port, 0x1234);
        assert!(is_dns);
    }

    #[test]
    fn test_non_dns_port_filtered() {
        let packet = build_ipv4_udp_packet(Ipv4Addr::new(10, 0, 0, 5), 443);
        let (src_ip, _src_port, is_dns) = extract_dns_fields(&packet);

        assert_eq!(src_ip, "10.0.0.5");
        assert!(!is_dns);
    }

    #[test]
    fn test_too_short_packet_skipped() {
        let packet = vec![0x45, 0x00, 0x00, 0x01];
        assert!(SlicedPacket::from_ip(&packet).is_err());
    }

    #[test]
    fn test_tcp_packet_not_dns() {
        // Build a minimal IPv4 TCP packet (protocol 6) — should not be treated as DNS
        let mut packet = vec![0u8; 28];
        packet[0] = 0x45;
        packet[1] = 0x00;
        packet[2] = 0x00;
        packet[3] = 28;
        packet[8] = 64;
        packet[9] = 6; // protocol = TCP
        packet[12..16].copy_from_slice(&[192, 168, 1, 50]);
        packet[16..20].copy_from_slice(&[10, 1, 10, 1]);
        // TCP header (src_port, dst_port at offset 20+)
        packet[20] = 0x00;
        packet[21] = 53; // dst port = 53 (but it's TCP, not UDP)

        let (_, _, is_dns) = extract_dns_fields(&packet);
        assert!(!is_dns); // TCP, not UDP — should not be DNS
    }

    #[test]
    fn test_stop_signal_atomic() {
        let flag = Arc::new(AtomicBool::new(false));
        let flag_clone = flag.clone();

        assert!(!flag.load(Ordering::SeqCst));
        flag_clone.store(true, Ordering::SeqCst);
        assert!(flag.load(Ordering::SeqCst));
    }
}
