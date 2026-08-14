use std::sync::Arc;
use tokio::sync::Mutex;
use log::{info, error};
use std::io::{Read, Write};

#[cfg(unix)]
use std::os::unix::io::{FromRawFd, AsRawFd, OwnedFd};

uniffi::setup_scaffolding!();

#[uniffi::export]
pub fn rust_init_logger() {
    android_logger::init_once(
        android_logger::Config::default()
            .with_max_level(log::LevelFilter::Info) // Reduced log level
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
        info!("Starting Fityah VPN Core v6 (Async DNS Bridge) with fd: {}", fd);
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
    use tokio::io::unix::AsyncFd;
    use tokio::io::Interest;

    // Android 15 compatibility: Duplicate the FD
    let duped_fd = unsafe { libc::dup(fd) };
    if duped_fd < 0 {
        return Err(anyhow::anyhow!("Failed to duplicate TUN fd"));
    }

    // Set to non-blocking for use with AsyncFd
    unsafe {
        let flags = libc::fcntl(duped_fd, libc::F_GETFL);
        libc::fcntl(duped_fd, libc::F_SETFL, flags | libc::O_NONBLOCK);
    }

    let owned_fd = unsafe { OwnedFd::from_raw_fd(duped_fd) };
    let async_fd = AsyncFd::with_interest(owned_fd, Interest::READABLE)?;

    let mut buf = [0u8; 4096];
    info!("VPN Core Bridge active: Intercepting DNS (Async Mode)");

    loop {
        if *stop_signal.lock().await { break; }

        let mut guard = async_fd.readable().await?;

        // Read raw data from the underlying FD
        let raw_fd = async_fd.as_raw_fd();
        let n = unsafe {
            libc::read(raw_fd, buf.as_mut_ptr() as *mut libc::c_void, buf.len())
        };

        if n > 0 {
            let packet_data = buf[..n as usize].to_vec();
            if let Some(response_packet) = callback.on_dns_packet(packet_data) {
                if !response_packet.is_empty() {
                    // Write back to the TUN
                    unsafe {
                        libc::write(raw_fd, response_packet.as_ptr() as *const libc::c_void, response_packet.len());
                    }
                }
            }
        } else if n < 0 {
            let err = std::io::Error::last_os_error();
            if err.kind() == std::io::ErrorKind::WouldBlock {
                guard.clear_ready();
            } else {
                error!("TUN read error: {:?}", err);
                break;
            }
        } else {
            // EOF
            break;
        }
    }

    Ok(())
}
