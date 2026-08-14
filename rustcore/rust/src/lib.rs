use std::sync::Arc;
use tokio::sync::Mutex;
use log::{info, error, debug, warn};
use std::io::{Read, Write};
use std::collections::{HashMap, HashSet};
use std::net::{SocketAddr, IpAddr};

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
    rx_queue: Vec<Vec<u8>>,
    reverse_nat: Arc<std::sync::Mutex<HashMap<u16, SocketAddr>>>,
}

#[cfg(unix)]
impl smoltcp::phy::Device for TunDevice {
    type RxToken<'a> = RxToken where Self: 'a;
    type TxToken<'a> = TxToken<'a> where Self: 'a;

    fn receive(&mut self, _timestamp: smoltcp::time::Instant) -> Option<(Self::RxToken<'_>, Self::TxToken<'_>)> {
        if self.rx_queue.is_empty() {
            return None;
        }
        let buffer = self.rx_queue.remove(0);
        Some((RxToken { buffer }, TxToken { file: &mut self.file, reverse_nat: self.reverse_nat.clone() }))
    }

    fn transmit(&mut self, _timestamp: smoltcp::time::Instant) -> Option<Self::TxToken<'_>> {
        Some(TxToken { file: &mut self.file, reverse_nat: self.reverse_nat.clone() })
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
struct TxToken<'a> {
    file: &'a mut std::fs::File,
    reverse_nat: Arc<std::sync::Mutex<HashMap<u16, SocketAddr>>>,
}
#[cfg(unix)]
impl<'a> smoltcp::phy::TxToken for TxToken<'a> {
    fn consume<R, F>(self, len: usize, f: F) -> R where F: FnOnce(&mut [u8]) -> R {
        let mut buffer = vec![0u8; len];
        let result = f(&mut buffer);

        use smoltcp::wire::{Ipv4Packet, Ipv6Packet, TcpPacket, UdpPacket, IpProtocol};

        let mut rewritten = false;
        let version = buffer[0] >> 4;

        if version == 4 {
            let mut dst_port = 0;
            let mut src_port = 0;

            if let Ok(ip_pkt) = Ipv4Packet::new_checked(&buffer) {
                if ip_pkt.dst_addr() == smoltcp::wire::Ipv4Address::new(10, 1, 10, 2) {
                    let payload = ip_pkt.payload();
                    match ip_pkt.next_header() {
                        IpProtocol::Tcp => {
                            if let Ok(tcp) = TcpPacket::new_checked(payload) {
                                dst_port = tcp.dst_port();
                                src_port = tcp.src_port();
                            }
                        }
                        IpProtocol::Udp => {
                            if let Ok(udp) = UdpPacket::new_checked(payload) {
                                dst_port = udp.dst_port();
                                src_port = udp.src_port();
                            }
                        }
                        _ => {}
                    };
                }
            }

            if dst_port != 0 {
                if let Some(orig) = self.reverse_nat.lock().unwrap().get(&dst_port) {
                    if let IpAddr::V4(orig_ip) = orig.ip() {
                        if orig.port() == src_port {
                            let mut ip_pkt = Ipv4Packet::new_unchecked(&mut buffer);
                            ip_pkt.set_src_addr(smoltcp::wire::Ipv4Address::from_bytes(&orig_ip.octets()));
                            rewritten = true;
                        }
                    }
                }
            }

            if rewritten {
                let mut ip_pkt = Ipv4Packet::new_unchecked(&mut buffer);
                let s_ip = ip_pkt.src_addr();
                let d_ip = ip_pkt.dst_addr();
                ip_pkt.fill_checksum();
                match ip_pkt.next_header() {
                    IpProtocol::Tcp => {
                        let mut tcp_pkt = TcpPacket::new_unchecked(ip_pkt.payload_mut());
                        tcp_pkt.fill_checksum(&s_ip.into(), &d_ip.into());
                    }
                    IpProtocol::Udp => {
                        let mut udp_pkt = UdpPacket::new_unchecked(ip_pkt.payload_mut());
                        udp_pkt.fill_checksum(&s_ip.into(), &d_ip.into());
                    }
                    _ => {}
                }
            }
        } else if version == 6 {
            let mut dst_port = 0;
            if let Ok(ip_pkt) = Ipv6Packet::new_checked(&buffer) {
                if ip_pkt.dst_addr() == smoltcp::wire::Ipv6Address::new(0xfd00, 0, 0, 0, 0, 0, 0, 2) {
                    let payload = ip_pkt.payload();
                    match ip_pkt.next_header() {
                        IpProtocol::Tcp => {
                            if let Ok(tcp) = TcpPacket::new_checked(payload) {
                                dst_port = tcp.dst_port();
                            }
                        }
                        IpProtocol::Udp => {
                            if let Ok(udp) = UdpPacket::new_checked(payload) {
                                dst_port = udp.dst_port();
                            }
                        }
                        _ => {}
                    };
                }
            }

            if dst_port != 0 {
                if let Some(orig) = self.reverse_nat.lock().unwrap().get(&dst_port) {
                    if let IpAddr::V6(orig_ip) = orig.ip() {
                        let mut ip_pkt = Ipv6Packet::new_unchecked(&mut buffer);
                        ip_pkt.set_src_addr(smoltcp::wire::Ipv6Address::from_bytes(&orig_ip.octets()));
                        rewritten = true;
                    }
                }
            }

            if rewritten {
                let mut ip_pkt = Ipv6Packet::new_unchecked(&mut buffer);
                let s_ip = ip_pkt.src_addr();
                let d_ip = ip_pkt.dst_addr();
                match ip_pkt.next_header() {
                    IpProtocol::Tcp => {
                        let mut tcp_pkt = TcpPacket::new_unchecked(ip_pkt.payload_mut());
                        tcp_pkt.fill_checksum(&s_ip.into(), &d_ip.into());
                    }
                    IpProtocol::Udp => {
                        let mut udp_pkt = UdpPacket::new_unchecked(ip_pkt.payload_mut());
                        udp_pkt.fill_checksum(&s_ip.into(), &d_ip.into());
                    }
                    _ => {}
                }
            }
        }

        let _ = self.file.write_all(&buffer);
        result
    }
}

// --- Non-Blocking Relay Engine ---

struct AsyncTcpRelay {
    to_remote: tokio::sync::mpsc::Sender<Vec<u8>>,
    from_remote: tokio::sync::mpsc::Receiver<Vec<u8>>,
    client_port: u16,
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
    use smoltcp::wire::{IpAddress, IpCidr, Ipv4Address, Ipv6Address, Ipv4Packet, Ipv6Packet, TcpPacket, HardwareAddress};
    use smoltcp::socket::{tcp, AnySocket};
    use tokio::net::{UdpSocket};
    use tokio::io::{AsyncReadExt, AsyncWriteExt};
    use etherparse::{SlicedPacket, TransportSlice, NetSlice};
    use std::os::unix::io::{FromRawFd};

    let duped_fd = unsafe { libc::dup(fd) };
    if duped_fd < 0 {
        return Err(anyhow::anyhow!("Failed to duplicate TUN fd"));
    }

    let tun_std = unsafe { std::fs::File::from_raw_fd(duped_fd) };

    #[cfg(unix)]
    unsafe {
        let flags = libc::fcntl(duped_fd, libc::F_GETFL);
        libc::fcntl(duped_fd, libc::F_SETFL, flags | libc::O_NONBLOCK);
    }

    let reverse_nat = Arc::new(std::sync::Mutex::new(HashMap::new()));
    let mut device = TunDevice {
        file: tun_std,
        rx_queue: Vec::new(),
        reverse_nat: reverse_nat.clone()
    };

    let config = Config::new(HardwareAddress::Ip);
    let mut iface = Interface::new(config, &mut device, Instant::now());
    iface.update_ip_addrs(|addrs| {
        addrs.push(IpCidr::new(IpAddress::v4(10, 1, 10, 1), 24)).unwrap();
        addrs.push(IpCidr::new(IpAddress::v6(0xfd00, 0, 0, 0, 0, 0, 0, 1), 64)).unwrap();
    });

    let mut sockets = SocketSet::new(vec![]);

    let mut udp_relays: HashMap<u16, Arc<UdpSocket>> = HashMap::new();
    let (tx_tun, mut rx_tun) = tokio::sync::mpsc::channel::<Vec<u8>>(1024);

    let mut tcp_relays: HashMap<SocketHandle, AsyncTcpRelay> = HashMap::new();
    let mut original_destinations: HashMap<u16, SocketAddr> = HashMap::new();
    let mut tracked_ports: HashSet<u16> = HashSet::new();

    const MAX_TCP_SESSIONS: usize = 256;
    info!("Fityah VPN: Hybrid Core active (Dual-Stack support).");

    let protector_arc: Arc<dyn SocketProtector> = Arc::from(protector);

    loop {
        if *stop_signal.lock().await { break; }
        let now = Instant::now();

        // 1. Maintain Listener Pool
        let mut listener_counts: HashMap<u16, usize> = HashMap::new();
        for (_, socket) in sockets.iter_mut() {
            if let Some(socket) = tcp::Socket::downcast_mut(socket) {
                if socket.state() == tcp::State::Listen {
                    if let Some(ep) = socket.local_endpoint() {
                        *listener_counts.entry(ep.port).or_insert(0) += 1;
                    }
                }
            }
        }
        for &port in tracked_ports.iter() {
            let count = *listener_counts.get(&port).unwrap_or(&0);
            if count < 5 {
                let mut s = tcp::Socket::new(
                    tcp::SocketBuffer::new(vec![0; 16384]),
                    tcp::SocketBuffer::new(vec![0; 16384])
                );
                let _ = s.listen(port);
                sockets.add(s);
            }
        }

        // 2. Feed Outbound Packets
        while let Ok(packet) = rx_tun.try_recv() {
            let _ = device.file.write_all(&packet);
        }

        // 3. Consume Inbound Packets
        let mut buf = [0u8; 2048];
        match device.file.read(&mut buf) {
            Ok(n) if n > 0 => {
                let mut packet_data = buf[..n].to_vec();
                if let Ok(sliced) = SlicedPacket::from_ip(&packet_data) {
                    match sliced.transport {
                        Some(TransportSlice::Udp(udp)) => {
                            let dst_port = udp.destination_port();
                            let src_port = udp.source_port();
                            let dst_ip = match sliced.net.as_ref().unwrap() {
                                NetSlice::Ipv4(h) => IpAddr::V4(h.header().destination().into()),
                                NetSlice::Ipv6(h) => IpAddr::V6(h.header().destination().into()),
                            };
                            let dst_addr = SocketAddr::new(dst_ip, dst_port);

                            if dst_port == 53 || dst_port == 853 {
                                 match callback.on_dns_packet(packet_data.clone()) {
                                     Some(resp) if !resp.is_empty() => {
                                         let _ = device.file.write_all(&resp);
                                         continue;
                                     }
                                     _ => { if dst_port == 53 { continue; } }
                                 }
                            }

                            let relay = if let Some(r) = udp_relays.get(&src_port) {
                                r.clone()
                            } else {
                                match create_protected_udp_socket(dst_addr, &protector_arc) {
                                    Ok(s) => {
                                        let s_arc = Arc::new(s);
                                        udp_relays.insert(src_port, s_arc.clone());
                                        reverse_nat.lock().unwrap().insert(src_port, dst_addr);
                                        let tx_clone = tx_tun.clone();
                                        let s_clone = s_arc.clone();
                                        let rn_clone = reverse_nat.clone();
                                        tokio::spawn(async move {
                                            let mut resp_buf = [0u8; 2048];
                                            while let Ok((len, addr)) = s_clone.recv_from(&mut resp_buf).await {
                                                if let Some(p) = build_udp_response_packet(addr, src_port, &resp_buf[..len]) {
                                                    let _ = tx_clone.send(p).await;
                                                }
                                            }
                                            rn_clone.lock().unwrap().remove(&src_port);
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
                            let dst_port = tcp_hdr.destination_port();
                            if let Some(net) = sliced.net.as_ref() {
                                match net {
                                    NetSlice::Ipv4(h) => {
                                        let dst_ip = IpAddr::V4(h.header().destination().into());
                                        let dst_addr = SocketAddr::new(dst_ip, dst_port);
                                        if tcp_hdr.syn() {
                                            if original_destinations.len() < MAX_TCP_SESSIONS {
                                                original_destinations.insert(src_port, dst_addr);
                                                reverse_nat.lock().unwrap().insert(src_port, dst_addr);
                                                tracked_ports.insert(dst_port);
                                            } else { continue; }
                                        }
                                        let mut ip_pkt = Ipv4Packet::new_unchecked(&mut packet_data);
                                        let s_ip = ip_pkt.src_addr();
                                        let d_ip = Ipv4Address::new(10, 1, 10, 1);
                                        ip_pkt.set_dst_addr(d_ip);
                                        ip_pkt.fill_checksum();
                                        let mut tcp_pkt = TcpPacket::new_unchecked(ip_pkt.payload_mut());
                                        tcp_pkt.fill_checksum(&s_ip.into(), &d_ip.into());
                                    }
                                    NetSlice::Ipv6(h) => {
                                        let dst_ip = IpAddr::V6(h.header().destination().into());
                                        let dst_addr = SocketAddr::new(dst_ip, dst_port);
                                        if tcp_hdr.syn() {
                                            if original_destinations.len() < MAX_TCP_SESSIONS {
                                                original_destinations.insert(src_port, dst_addr);
                                                reverse_nat.lock().unwrap().insert(src_port, dst_addr);
                                                tracked_ports.insert(dst_port);
                                            } else { continue; }
                                        }
                                        let mut ip_pkt = Ipv6Packet::new_unchecked(&mut packet_data);
                                        let s_ip = ip_pkt.src_addr();
                                        let d_ip = Ipv6Address::new(0xfd00, 0, 0, 0, 0, 0, 0, 1);
                                        ip_pkt.set_dst_addr(d_ip);
                                        let mut tcp_pkt = TcpPacket::new_unchecked(ip_pkt.payload_mut());
                                        tcp_pkt.fill_checksum(&s_ip.into(), &d_ip.into());
                                    }
                                }
                                device.rx_queue.push(packet_data);
                            }
                        }
                        _ => {}
                    }
                }
            }
            _ => {}
        }

        // 4. Poll smoltcp
        iface.poll(now, &mut device, &mut sockets);

        // 5. Handle Socket Data & Transitions
        let mut handles_to_remove = Vec::new();
        for (handle, socket) in sockets.iter_mut() {
            if let Some(socket) = tcp::Socket::downcast_mut(socket) {
                // New Connection Established
                if socket.is_active() && !tcp_relays.contains_key(&handle) {
                    if let Some(remote) = socket.remote_endpoint() {
                        if let Some(orig_dst) = original_destinations.get(&remote.port) {
                            let (tx_smol, rx_smol) = tokio::sync::mpsc::channel::<Vec<u8>>(128);
                            let (tx_remote, rx_remote) = tokio::sync::mpsc::channel::<Vec<u8>>(128);

                            tcp_relays.insert(handle, AsyncTcpRelay {
                                to_remote: tx_remote,
                                from_remote: rx_smol,
                                client_port: remote.port,
                            });

                            let target = *orig_dst;
                            let protector_clone = protector_arc.clone();
                            let tx_tun_clone = tx_smol.clone();
                            let mut rx_remote_stream = rx_remote;

                            tokio::spawn(async move {
                                match create_protected_tcp_stream(target, &protector_clone).await {
                                    Ok(mut stream) => {
                                        let (mut reader, mut writer) = stream.split();
                                        let mut buf = [0u8; 8192];
                                        loop {
                                            tokio::select! {
                                                res = rx_remote_stream.recv() => {
                                                    if let Some(data) = res {
                                                        if writer.write_all(&data).await.is_err() { break; }
                                                    } else { break; }
                                                }
                                                res = reader.read(&mut buf) => {
                                                    match res {
                                                        Ok(0) | Err(_) => break,
                                                        Ok(n) => {
                                                            if tx_tun_clone.send(buf[..n].to_vec()).await.is_err() { break; }
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    }
                                    Err(e) => warn!("Failed to connect to {}: {}", target, e),
                                }
                            });
                        }
                    }
                }

                // Established Relay
                if let Some(relay) = tcp_relays.get_mut(&handle) {
                    if socket.can_recv() {
                        let mut data = vec![0u8; 8192];
                        if let Ok(n) = socket.recv_slice(&mut data) {
                            if n > 0 {
                                let _ = relay.to_remote.try_send(data[..n].to_vec());
                            }
                        }
                    }
                    if socket.can_send() {
                        if let Ok(data) = relay.from_remote.try_recv() {
                            let _ = socket.send_slice(&data);
                        }
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
                reverse_nat.lock().unwrap().remove(&relay.client_port);
            }
            sockets.remove(handle);
        }

        tokio::task::yield_now().await;
    }
    Ok(())
}

#[cfg(unix)]
async fn create_protected_tcp_stream(addr: SocketAddr, protector: &Arc<dyn SocketProtector>) -> anyhow::Result<tokio::net::TcpStream> {
    use socket2::{Socket, Domain, Type, Protocol};
    use std::os::unix::io::{AsRawFd, IntoRawFd};
    let domain = if addr.is_ipv4() { Domain::IPV4 } else { Domain::IPV6 };
    let socket = Socket::new(domain, Type::STREAM, Some(Protocol::TCP))?;
    if !protector.protect_socket(socket.as_raw_fd()) {
        return Err(anyhow::anyhow!("Protection failed"));
    }
    socket.set_nonblocking(true)?;
    match socket.connect(&addr.into()) {
        Ok(_) => {}
        Err(ref e) if e.kind() == std::io::ErrorKind::WouldBlock => {}
        Err(e) => return Err(e.into()),
    }
    let std_socket: std::net::TcpStream = unsafe { std::net::TcpStream::from_raw_fd(socket.into_raw_fd()) };
    Ok(tokio::net::TcpStream::from_std(std_socket)?)
}

#[cfg(unix)]
fn create_protected_udp_socket(addr: SocketAddr, protector: &Arc<dyn SocketProtector>) -> anyhow::Result<tokio::net::UdpSocket> {
    use socket2::{Socket, Domain, Type, Protocol};
    use std::os::unix::io::{AsRawFd, IntoRawFd};
    let domain = if addr.is_ipv4() { Domain::IPV4 } else { Domain::IPV6 };
    let socket = Socket::new(domain, Type::DGRAM, Some(Protocol::UDP))?;
    if !protector.protect_socket(socket.as_raw_fd()) {
        return Err(anyhow::anyhow!("Protection failed"));
    }
    let std_socket: std::net::UdpSocket = unsafe { std::net::UdpSocket::from_raw_fd(socket.into_raw_fd()) };
    Ok(tokio::net::UdpSocket::from_std(std_socket)?)
}

#[cfg(unix)]
fn build_udp_response_packet(src_addr: std::net::SocketAddr, dst_port: u16, payload: &[u8]) -> Option<Vec<u8>> {
    use etherparse::PacketBuilder;
    match src_addr.ip() {
        IpAddr::V4(src_ip) => {
            let builder = PacketBuilder::ipv4(src_ip.octets(), [10, 1, 10, 2], 64).udp(src_addr.port(), dst_port);
            let mut result = Vec::with_capacity(builder.size(payload.len()));
            builder.write(&mut result, payload).ok()?;
            Some(result)
        }
        IpAddr::V6(src_ip) => {
            let builder = PacketBuilder::ipv6(src_ip.octets(), [0xfd, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 2], 64).udp(src_addr.port(), dst_port);
            let mut result = Vec::with_capacity(builder.size(payload.len()));
            builder.write(&mut result, payload).ok()?;
            Some(result)
        }
    }
}
