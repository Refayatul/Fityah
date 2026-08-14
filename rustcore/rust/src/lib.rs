use std::sync::Arc;
use tokio::sync::Mutex;
use log::{info, error, debug};
use std::collections::HashMap;
use std::net::{SocketAddr, IpAddr};

#[cfg(unix)]
use std::os::unix::io::FromRawFd;
#[cfg(unix)]
use std::io::{Read, Write};

uniffi::setup_scaffolding!();

#[uniffi::export]
pub fn rust_init_logger() {
    android_logger::init_once(
        android_logger::Config::default()
            .with_max_level(log::LevelFilter::Debug)
            .with_tag("FityahRust"),
    );
}

#[uniffi::export]
pub fn ping_rust() -> String {
    "Pong from Rust via UniFFI!".to_string()
}

#[uniffi::export(callback_interface)]
pub trait DnsCallback: Send + Sync {
    fn on_dns_packet(&self, packet: Vec<u8>) -> Option<Vec<u8>>;
}

#[uniffi::export(callback_interface)]
pub trait SocketProtector: Send + Sync {
    fn protect_socket(&self, fd: i32) -> bool;
}

#[derive(uniffi::Object)]
pub struct VpnController {
    stop_signal: Arc<Mutex<bool>>,
}

#[uniffi::export]
impl VpnController {
    #[uniffi::constructor]
    pub fn new() -> Arc<Self> {
        Arc::new(VpnController {
            stop_signal: Arc::new(Mutex::new(false)),
        })
    }

    pub fn start(&self, fd: i32, callback: Box<dyn DnsCallback>, protector: Box<dyn SocketProtector>) {
        info!("Starting VPN core with fd: {}", fd);
        let stop_signal = self.stop_signal.clone();

        #[cfg(unix)]
        std::thread::spawn(move || {
            let rt = tokio::runtime::Builder::new_current_thread()
                .enable_all()
                .build()
                .unwrap();

            rt.block_on(async move {
                if let Err(e) = run_vpn_loop(fd, callback, protector, stop_signal).await {
                    error!("VPN Loop error: {:?}", e);
                }
            });
        });

        #[cfg(windows)]
        {
            error!("VPN core start ignored on Windows host.");
            let _ = (fd, callback, protector, stop_signal);
        }
    }

    pub fn stop(&self) {
        let stop_signal = self.stop_signal.clone();
        // Since we are in a sync context but need to update an async mutex
        let rt = tokio::runtime::Builder::new_current_thread().build().unwrap();
        rt.block_on(async {
            let mut stop = stop_signal.lock().await;
            *stop = true;
        });
    }
}

#[cfg(unix)]
async fn run_vpn_loop(
    fd: i32,
    callback: Box<dyn DnsCallback>,
    protector: Box<dyn SocketProtector>,
    stop_signal: Arc<Mutex<bool>>
) -> anyhow::Result<()> {
    use std::io::ErrorKind;
    use std::os::unix::io::AsRawFd;

    let mut file = unsafe { std::fs::File::from_raw_fd(fd) };
    let mut buffer = [0u8; 65535];

    // Task 4: Simplified UDP NAT state (Source Port -> Real Socket)
    // For a robust implementation, we'd use smoltcp sockets.
    let mut udp_relays: HashMap<u16, Arc<tokio::net::UdpSocket>> = HashMap::new();

    info!("Fityah VPN: Catch-all loop active (0.0.0.0/0).");
    loop {
        if *stop_signal.lock().await {
            break;
        }

        let n = match file.read(&mut buffer) {
            Ok(0) => break,
            Ok(n) => n,
            Err(ref e) if e.kind() == ErrorKind::Interrupted => continue,
            Err(e) => {
                error!("Read error: {}", e);
                break;
            }
        };

        let packet_data = &buffer[..n];

        match etherparse::SlicedPacket::from_ip(packet_data) {
            Ok(sliced) => {
                if let Some(transport) = sliced.transport {
                    match transport {
                        etherparse::TransportSlice::Udp(udp) => {
                            let dst_port = udp.destination_port();

                            // FILTER-PATH: Intercept DNS/DoT/DoH
                            if dst_port == 53 || dst_port == 853 || dst_port == 443 {
                                if let Some(response) = callback.on_dns_packet(packet_data.to_vec()) {
                                    if !response.is_empty() {
                                        let _ = file.write(&response);
                                        continue;
                                    }
                                }
                            }

                            // FAST-PATH: Minimal UDP Forwarding (NAT Placeholder)
                            // To keep connectivity working, we need to relay this to the real world.
                            // Integration of smoltcp will happen here next.
                        }
                        etherparse::TransportSlice::Tcp(tcp) => {
                            let dst_port = tcp.destination_port();
                            if dst_port == 853 || dst_port == 443 {
                                if let Some(response) = callback.on_dns_packet(packet_data.to_vec()) {
                                    if response.is_empty() { continue; } // Blocked IP
                                }
                            }
                        }
                        _ => {}
                    }
                }
            }
            Err(_) => {}
        }

        // Pass-through isn't simple in a VPN. If we don't NAT, we drop.
        // I am implementing smoltcp in the next turn to enable full connectivity.
    }
    info!("Fityah VPN: Loop exited.");
    Ok(())
}
