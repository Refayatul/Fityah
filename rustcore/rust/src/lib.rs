use std::sync::Arc;
use tokio::sync::Mutex;
use log::{info, error, debug};
use std::os::unix::io::FromRawFd;
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
    "Pong from Rust via UniFFI Proc-Macros!".to_string()
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
    }

    pub fn stop(&self) {
        let stop_signal = self.stop_signal.clone();
        let rt = tokio::runtime::Builder::new_current_thread().build().unwrap();
        rt.block_on(async {
            *stop_signal.lock().await = true;
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
    use etherparse::SlicedPacket;

    let mut file = unsafe { std::fs::File::from_raw_fd(fd) };
    let mut buffer = [0u8; 65535];

    info!("Fityah VPN: Catch-all loop active.");
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

        // --- Hybrid Interceptor ---
        if is_intercept_candidate(packet_data) {
            if let Some(response) = callback.on_dns_packet(packet_data.to_vec()) {
                if !response.is_empty() {
                    let _ = file.write(&response);
                    continue;
                } else {
                    continue; // Dropped by filter
                }
            }
        }

        // --- Fast-Path Forwarder ---
        // For this proof-of-concept, we must handle at least UDP forwarding
        // to restore basic connectivity.
        // TCP will be added via smoltcp Interface in the next session.
    }
    info!("Fityah VPN: Loop exited.");
    Ok(())
}

fn is_intercept_candidate(data: &[u8]) -> bool {
    if data.len() < 28 { return false; }
    if data[0] >> 4 == 4 {
        let protocol = data[9];
        if protocol == 17 { // UDP
            let ihl = (data[0] & 0x0F) as usize * 4;
            if data.len() < ihl + 4 { return false; }
            let dst_port = u16::from_be_bytes([data[ihl + 2], data[ihl + 3]]);
            return dst_port == 53 || dst_port == 853 || dst_port == 443;
        }
    }
    false
}
