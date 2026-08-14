use std::sync::Arc;
use tokio::sync::Mutex;
use log::{info, error, debug};
use std::io::{Read, Write};

#[cfg(unix)]
use std::os::unix::io::{FromRawFd};

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

    pub fn start(&self, fd: i32, callback: Box<dyn DnsCallback>, _protector: Box<dyn SocketProtector>) {
        info!("Starting Fityah VPN Core v5 (DNS Intercept Mode) with fd: {}", fd);
        let stop_signal = self.stop_signal.clone();

        #[cfg(unix)]
        std::thread::spawn(move || {
            let rt = tokio::runtime::Builder::new_current_thread()
                .enable_all()
                .build()
                .unwrap();

            rt.block_on(async move {
                if let Err(e) = run_dns_bridge_loop(fd, callback, stop_signal).await {
                    error!("VPN Bridge Loop error: {:?}", e);
                }
            });
        });
    }

    pub fn stop(&self) {
        let stop_signal = self.stop_signal.clone();
        let rt = tokio::runtime::Builder::new_current_thread().build().unwrap();
        rt.block_on(async {
            let mut stop = stop_signal.lock().await;
            *stop = true;
        });
    }
}

#[cfg(unix)]
async fn run_dns_bridge_loop(
    fd: i32,
    callback: Box<dyn DnsCallback>,
    stop_signal: Arc<Mutex<bool>>
) -> anyhow::Result<()> {
    // Android 15 compatibility: Duplicate the FD so we own it independently of ParcelFileDescriptor
    let duped_fd = unsafe { libc::dup(fd) };
    if duped_fd < 0 {
        return Err(anyhow::anyhow!("Failed to duplicate TUN fd"));
    }

    // Set to non-blocking for use with std::fs::File (which we'll use in a simple way)
    unsafe {
        let flags = libc::fcntl(duped_fd, libc::F_GETFL);
        libc::fcntl(duped_fd, libc::F_SETFL, flags | libc::O_NONBLOCK);
    }

    let mut tun_file = unsafe { std::fs::File::from_raw_fd(duped_fd) };
    let mut buf = [0u8; 4096];

    info!("VPN Core Bridge active: Intercepting DNS at 10.1.10.1");

    loop {
        if *stop_signal.lock().await { break; }

        match tun_file.read(&mut buf) {
            Ok(n) if n > 0 => {
                let packet_data = buf[..n].to_vec();
                debug!("Received packet, size: {}", n);

                // Offload resolution to Kotlin
                // Note: Kotlin builds the full IP response packet
                if let Some(response_packet) = callback.on_dns_packet(packet_data) {
                    if !response_packet.is_empty() {
                        debug!("Sending response back to TUN, size: {}", response_packet.len());
                        let _ = tun_file.write_all(&response_packet);
                    }
                }
            }
            Err(ref e) if e.kind() == std::io::ErrorKind::WouldBlock => {
                tokio::task::yield_now().await;
                continue;
            }
            Ok(_) => {} // Empty read
            Err(e) => {
                error!("TUN read error: {:?}", e);
                break;
            }
        }
    }

    unsafe { libc::close(duped_fd); }
    Ok(())
}
