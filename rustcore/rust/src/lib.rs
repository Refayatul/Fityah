use std::sync::Arc;
use tokio::sync::Mutex;
use log::{info, error, debug, warn};
use std::io::{Read, Write};
use std::collections::HashMap;
use std::net::{SocketAddr, IpAddr};

#[cfg(unix)]
use std::os::unix::io::{FromRawFd, AsRawFd};

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
            let rt = tokio::runtime::Builder::new_multi_thread()
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
            let mut stop = stop_signal.lock().await;
            *stop = true;
        });
    }
}

// --- smoltcp Hardware Abstraction ---

#[cfg(unix)]
struct TunDevice {
    file: std::fs::File,
}

#[cfg(unix)]
impl smoltcp::phy::Device for TunDevice {
    type RxToken<'a> = RxToken where Self: 'a;
    type TxToken<'a> = TxToken<'a> where Self: 'a;

    fn receive(&mut self, _timestamp: smoltcp::time::Instant) -> Option<(Self::RxToken<'_>, Self::TxToken<'_>)> {
        None
    }

    fn transmit(&mut self, _timestamp: smoltcp::time::Instant) -> Option<Self::TxToken<'_>> {
        Some(TxToken { file: &mut self.file })
    }

    fn capabilities(&self) -> smoltcp::phy::DeviceCapabilities {
        let mut caps = smoltcp::phy::DeviceCapabilities::default();
        caps.medium = smoltcp::phy::Medium::Ip;
        caps.max_transmission_unit = 1500;
        caps
    }
}

#[cfg(unix)]
struct RxToken { buffer: Vec<u8> }
#[cfg(unix)]
impl smoltcp::phy::RxToken for RxToken {
    fn consume<R, F>(mut self, f: F) -> R where F: FnOnce(&mut [u8]) -> R { f(&mut self.buffer) }
}
#[cfg(unix)]
struct TxToken<'a> { file: &'a mut std::fs::File }
#[cfg(unix)]
impl<'a> smoltcp::phy::TxToken for TxToken<'a> {
    fn consume<R, F>(self, len: usize, f: F) -> R where F: FnOnce(&mut [u8]) -> R {
        let mut buffer = vec![0u8; len];
        let result = f(&mut buffer);
        let _ = self.file.write_all(&buffer);
        result
    }
}

// --- Multi-Connection Relay Manager ---

#[cfg(unix)]
struct TcpRelay {
    stream: tokio::net::TcpStream,
    client_port: u16,
    last_activity: std::time::Instant,
}

// --- Main Networking Loop ---

#[cfg(unix)]
async fn run_vpn_loop(
    fd: i32,
    callback: Box<dyn DnsCallback>,
    protector: Box<dyn SocketProtector>,
    stop_signal: Arc<Mutex<bool>>
) -> anyhow::Result<()> {
    use smoltcp::iface::{Config, Interface, SocketSet, SocketHandle};
    use smoltcp::time::Instant;
    use smoltcp::wire::{IpAddress, IpCidr, Ipv4Address, Ipv4Packet, HardwareAddress};
    use smoltcp::socket::tcp;
    use tokio::net::{UdpSocket, TcpStream};
    use tokio::io::{AsyncReadExt, AsyncWriteExt};
    use etherparse::{SlicedPacket, TransportSlice};
    use std::os::unix::io::{AsRawFd, FromRawFd, IntoRawFd};

    let tun_std = unsafe { std::fs::File::from_raw_fd(fd) };
    let mut device = TunDevice { file: tun_std };

    let config = Config::new(HardwareAddress::Ip);
    let mut iface = Interface::new(config, &mut device, Instant::now());
    iface.update_ip_addrs(|addrs| {
        addrs.push(IpCidr::new(IpAddress::v4(10, 0, 0, 2), 32)).unwrap();
    });

    let mut sockets = SocketSet::new(vec![]);

    // Relay States
    let mut udp_relays: HashMap<u16, Arc<UdpSocket>> = HashMap::new();
    let (tx_tun, mut rx_tun) = tokio::sync::mpsc::channel::<Vec<u8>>(1024);

    let mut tcp_relays: HashMap<SocketHandle, TcpRelay> = HashMap::new();
    let mut original_destinations: HashMap<u16, SocketAddr> = HashMap::new();

    const MAX_TCP_SESSIONS: usize = 256;
    const SESSION_TIMEOUT: std::time::Duration = std::time::Duration::from_secs(60);

    info!("Fityah VPN: Hybrid Core active (Multi-session TCP support).");

    loop {
        if *stop_signal.lock().await { break; }
        let now = Instant::now();

        // 1. Process responses -> TUN
        while let Ok(packet) = rx_tun.try_recv() {
            let _ = device.file.write_all(&packet);
        }

        // 2. Read outbound from TUN
        let mut buf = [0u8; 2048];
        if let Ok(n) = device.file.read(&mut buf) {
            let mut packet_data = buf[..n].to_vec();
            if let Ok(sliced) = SlicedPacket::from_ip(&packet_data) {
                match sliced.transport {
                    Some(TransportSlice::Udp(udp)) => {
                        let dst_port = udp.destination_port();
                        let src_port = udp.source_port();
                        let dst_ip = match sliced.ip.unwrap() {
                            etherparse::IpHeader::Version4(h, _) => IpAddr::V4(h.destination.into()),
                            etherparse::IpHeader::Version6(h, _) => IpAddr::V6(h.destination.into()),
                        };
                        let dst_addr = SocketAddr::new(dst_ip, dst_port);

                        if dst_port == 53 || dst_port == 853 || dst_port == 443 {
                             if let Some(resp) = callback.on_dns_packet(packet_data.clone()) {
                                 if !resp.is_empty() {
                                     let _ = device.file.write_all(&resp);
                                     continue;
                                 }
                             }
                        }

                        let relay = if let Some(r) = udp_relays.get(&src_port) {
                            r.clone()
                        } else {
                            match create_protected_udp_socket(&protector) {
                                Ok(s) => {
                                    let s_arc = Arc::new(s);
                                    udp_relays.insert(src_port, s_arc.clone());
                                    let tx_clone = tx_tun.clone();
                                    let s_clone = s_arc.clone();
                                    tokio::spawn(async move {
                                        let mut resp_buf = [0u8; 2048];
                                        while let Ok((len, addr)) = s_clone.recv_from(&mut resp_buf).await {
                                            if let Some(p) = build_udp_response_packet(addr, "10.0.0.2".parse().unwrap(), src_port, &resp_buf[..len]) {
                                                let _ = tx_clone.send(p).await;
                                            }
                                        }
                                    });
                                    s_arc
                                }
                                Err(_) => continue,
                            }
                        };
                        let _ = relay.send_to(udp.payload(), dst_addr).await;
                    }
                    Some(TransportSlice::Tcp(tcp_hdr)) => {
                        let src_port = tcp_hdr.source_port();
                        let mut ip_pkt = Ipv4Packet::new_unchecked(&mut packet_data);
                        let dst_ip = IpAddr::V4(ip_pkt.dst_addr().into());
                        let dst_addr = SocketAddr::new(dst_ip, tcp_hdr.destination_port());

                        if tcp_hdr.syn() {
                            if original_destinations.len() < MAX_TCP_SESSIONS {
                                original_destinations.insert(src_port, dst_addr);
                            } else {
                                warn!("TCP Capacity reached, dropping SYN.");
                                continue;
                            }
                        }

                        ip_pkt.set_dst_addr(Ipv4Address::new(10, 0, 0, 2));
                        let _ = iface.receive(now, RxToken { buffer: packet_data }, &mut sockets);
                    }
                    _ => {}
                }
            }
        }

        iface.poll(now, &mut device, &mut sockets);

        let mut handles_to_remove = Vec::new();
        for (handle, socket) in sockets.iter_mut() {
            if let Some(socket) = tcp::Socket::downcast_mut(socket) {
                if socket.is_active() && !tcp_relays.contains_key(&handle) {
                    if let Some(remote_endpoint) = socket.remote_endpoint() {
                        let client_port = remote_endpoint.port;
                        if let Some(dst_addr) = original_destinations.get(&client_port) {
                            match create_protected_tcp_stream(*dst_addr, &protector).await {
                                Ok(stream) => {
                                    tcp_relays.insert(handle, TcpRelay {
                                        stream,
                                        client_port,
                                        last_activity: std::time::Instant::now()
                                    });
                                }
                                Err(_) => { socket.abort(); }
                            }
                        }
                    }
                }

                if let Some(relay) = tcp_relays.get_mut(&handle) {
                    let mut activity = false;
                    if socket.can_recv() {
                        let mut data = vec![0u8; 8192];
                        if let Ok(n) = socket.recv_slice(&mut data) {
                            if n > 0 {
                                let _ = relay.stream.write_all(&data[..n]).await;
                                activity = true;
                            }
                        }
                    }
                    if socket.can_send() {
                        let mut data = [0u8; 8192];
                        match relay.stream.try_read(&mut data) {
                            Ok(n) if n > 0 => {
                                let _ = socket.send_slice(&data[..n]);
                                activity = true;
                            }
                            Err(ref e) if e.kind() == std::io::ErrorKind::WouldBlock => {}
                            _ => { socket.close(); }
                        }
                    }
                    if activity { relay.last_activity = std::time::Instant::now(); }
                    if relay.last_activity.elapsed() > SESSION_TIMEOUT {
                        socket.abort();
                        handles_to_remove.push(handle);
                    }
                }
                if socket.state() == tcp::State::Closed || socket.state() == tcp::State::TimeWait {
                    handles_to_remove.push(handle);
                }
            }
        }

        for handle in handles_to_remove {
            if let Some(relay) = tcp_relays.remove(&handle) {
                original_destinations.remove(&relay.client_port);
            }
            sockets.remove(handle);
        }

        if sockets.len() < MAX_TCP_SESSIONS {
            let tcp_rx_buffer = tcp::SocketBuffer::new(vec![0; 65535]);
            let tcp_tx_buffer = tcp::SocketBuffer::new(vec![0; 65535]);
            let mut s = tcp::Socket::new(tcp_rx_buffer, tcp_tx_buffer);
            let _ = s.listen(0);
            sockets.add(s);
        }

        tokio::task::yield_now().await;
    }
    Ok(())
}

#[cfg(unix)]
async fn create_protected_tcp_stream(addr: SocketAddr, protector: &Box<dyn SocketProtector>) -> anyhow::Result<TcpStream> {
    use socket2::{Socket, Domain, Type, Protocol};
    use std::os::unix::io::{AsRawFd, IntoRawFd};
    let socket = Socket::new(Domain::IPV4, Type::STREAM, Some(Protocol::TCP))?;
    if !protector.protect_socket(socket.as_raw_fd()) {
        return Err(anyhow::anyhow!("Protect failed"));
    }
    socket.set_nonblocking(true)?;
    let std_socket: std::net::TcpStream = unsafe { std::net::TcpStream::from_raw_fd(socket.into_raw_fd()) };
    let stream = TcpStream::from_std(std_socket)?;
    let _ = tokio::time::timeout(std::time::Duration::from_secs(5), stream.connect(addr)).await??;
    Ok(stream)
}

#[cfg(unix)]
fn create_protected_udp_socket(protector: &Box<dyn SocketProtector>) -> anyhow::Result<UdpSocket> {
    use socket2::{Socket, Domain, Type, Protocol};
    use std::os::unix::io::{AsRawFd, IntoRawFd};
    let socket = Socket::new(Domain::IPV4, Type::DGRAM, Some(Protocol::UDP))?;
    if !protector.protect_socket(socket.as_raw_fd()) {
        return Err(anyhow::anyhow!("Protect failed"));
    }
    let std_socket: std::net::UdpSocket = unsafe { std::net::UdpSocket::from_raw_fd(socket.into_raw_fd()) };
    Ok(UdpSocket::from_std(std_socket)?)
}

#[cfg(unix)]
fn build_udp_response_packet(src_addr: std::net::SocketAddr, dst_ip: std::net::IpAddr, dst_port: u16, payload: &[u8]) -> Option<Vec<u8>> {
    use etherparse::PacketBuilder;
    let src_ip = match src_addr.ip() { IpAddr::V4(ip) => ip, _ => return None };
    let dst_ip = match dst_ip { IpAddr::V4(ip) => ip, _ => return None };
    let builder = PacketBuilder::ipv4(src_ip.octets(), dst_ip.octets(), 64).udp(src_addr.port(), dst_port);
    let mut result = Vec::with_capacity(builder.size(payload.len()));
    builder.write(&mut result, payload).ok()?;
    Some(result)
}
