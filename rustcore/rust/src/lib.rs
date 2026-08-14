use std::sync::Arc;
use tokio::sync::Mutex;
use log::{info, error, debug, warn};
use std::io::{Read, Write};
use std::collections::{HashMap};
use std::net::{SocketAddr, IpAddr};
use tokio::net::{TcpStream, UdpSocket};

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
        info!("Starting VPN core v4 with fd: {}", fd);
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

        // --- REVERSE NAT Logic (Rust -> Phone) ---
        use smoltcp::wire::{Ipv4Packet, TcpPacket, IpProtocol};
        if buffer[0] >> 4 == 4 {
            if let Ok(ip_pkt) = Ipv4Packet::new_checked(&buffer) {
                if ip_pkt.src_addr() == smoltcp::wire::Ipv4Address::new(10, 1, 10, 1) &&
                   ip_pkt.next_header() == IpProtocol::Tcp {
                    let payload = ip_pkt.payload();
                    if let Ok(tcp_pkt) = TcpPacket::new_checked(payload) {
                        let dst_port = tcp_pkt.dst_port();
                        if let Some(orig_dst) = self.reverse_nat.lock().unwrap().get(&dst_port) {
                            if let IpAddr::V4(orig_ip) = orig_dst.ip() {
                                let mut ip_pkt_mut = Ipv4Packet::new_unchecked(&mut buffer);
                                ip_pkt_mut.set_src_addr(smoltcp::wire::Ipv4Address::from_bytes(&orig_ip.octets()));
                                ip_pkt_mut.fill_checksum();

                                let s_ip = ip_pkt_mut.src_addr();
                                let d_ip = ip_pkt_mut.dst_addr();
                                let mut tcp_pkt_mut = TcpPacket::new_unchecked(ip_pkt_mut.payload_mut());
                                tcp_pkt_mut.set_src_port(orig_dst.port());
                                tcp_pkt_mut.fill_checksum(&s_ip.into(), &d_ip.into());
                            }
                        }
                    }
                }
            }
        }

        let _ = self.file.write_all(&buffer);
        result
    }
}

// --- Relay State ---

struct TcpRelay {
    to_remote: tokio::sync::mpsc::Sender<Vec<u8>>,
    from_remote: tokio::sync::mpsc::Receiver<Vec<u8>>,
    client_port: u16,
}

#[cfg(unix)]
fn build_udp_response_packet(src_addr: std::net::SocketAddr, dst_ip: [u8; 4], dst_port: u16, payload: &[u8]) -> Option<Vec<u8>> {
    use etherparse::PacketBuilder;
    match src_addr.ip() {
        IpAddr::V4(src_ip) => {
            let builder = PacketBuilder::ipv4(src_ip.octets(), dst_ip, 64).udp(src_addr.port(), dst_port);
            let mut result = Vec::with_capacity(builder.size(payload.len()));
            builder.write(&mut result, payload).ok()?;
            Some(result)
        }
        _ => None,
    }
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
    use smoltcp::wire::{IpAddress, IpCidr, Ipv4Address, Ipv4Packet, TcpPacket, HardwareAddress};
    use smoltcp::socket::{tcp, AnySocket};
    use tokio::io::{AsyncReadExt, AsyncWriteExt};
    use etherparse::{SlicedPacket, TransportSlice, NetSlice};

    let duped_fd = unsafe { libc::dup(fd) };
    if duped_fd < 0 {
        return Err(anyhow::anyhow!("Failed to duplicate TUN fd"));
    }
    let tun_file = unsafe { std::fs::File::from_raw_fd(duped_fd) };
    unsafe {
        let flags = libc::fcntl(duped_fd, libc::F_GETFL);
        libc::fcntl(duped_fd, libc::F_SETFL, flags | libc::O_NONBLOCK);
    }

    let reverse_nat = Arc::new(std::sync::Mutex::new(HashMap::new()));
    let mut device = TunDevice {
        file: tun_file,
        rx_queue: Vec::new(),
        reverse_nat: reverse_nat.clone(),
    };

    let config = Config::new(HardwareAddress::Ip);
    let mut iface = Interface::new(config, &mut device, Instant::now());
    iface.update_ip_addrs(|addrs| {
        addrs.push(IpCidr::new(IpAddress::v4(10, 1, 10, 1), 24)).unwrap();
    });

    let mut sockets = SocketSet::new(vec![]);
    let tcp_listener_port = 12345;

    let mut udp_relays: HashMap<u16, Arc<UdpSocket>> = HashMap::new();
    let mut tcp_relays: HashMap<SocketHandle, TcpRelay> = HashMap::new();
    let (tx_tun, mut rx_tun) = tokio::sync::mpsc::channel::<Vec<u8>>(1024);
    let protector_arc: Arc<dyn SocketProtector> = Arc::from(protector);

    info!("Fityah VPN Core v4: IPv4 Handshake Fixed.");

    loop {
        if *stop_signal.lock().await { break; }
        let now = Instant::now();

        // 1. Maintain Listener Pool
        let mut listening_count = 0;
        for (_, socket) in sockets.iter_mut() {
            if let Some(socket) = tcp::Socket::downcast_mut(socket) {
                if socket.state() == tcp::State::Listen { listening_count += 1; }
            }
        }
        while listening_count < 16 {
            let mut s = tcp::Socket::new(
                tcp::SocketBuffer::new(vec![0; 65536]),
                tcp::SocketBuffer::new(vec![0; 65536])
            );
            s.listen(tcp_listener_port).unwrap();
            sockets.add(s);
            listening_count += 1;
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
                            if let Some(NetSlice::Ipv4(h)) = sliced.net {
                                let dst_ip = IpAddr::V4(h.header().destination().into());
                                let dst_addr = SocketAddr::new(dst_ip, dst_port);

                                if dst_port == 53 || dst_port == 853 {
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
                                    match create_protected_udp_socket(dst_addr, &protector_arc) {
                                        Ok(s) => {
                                            let s_arc = Arc::new(s);
                                            udp_relays.insert(src_port, s_arc.clone());
                                            let tx_clone = tx_tun.clone();
                                            let s_clone = s_arc.clone();
                                            tokio::spawn(async move {
                                                let mut resp_buf = [0u8; 2048];
                                                while let Ok((len, addr)) = s_clone.recv_from(&mut resp_buf).await {
                                                    if let Some(p) = build_udp_response_packet(addr, [10, 1, 10, 2], src_port, &resp_buf[..len]) {
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
                        }
                        Some(TransportSlice::Tcp(tcp_hdr)) => {
                            let src_port = tcp_hdr.source_port();
                            let dst_port = tcp_hdr.destination_port();
                            if let Some(NetSlice::Ipv4(ipv4_hdr)) = sliced.net {
                                let dst_ip = IpAddr::V4(ipv4_hdr.header().destination().into());
                                let dst_addr = SocketAddr::new(dst_ip, dst_port);

                                if tcp_hdr.syn() {
                                    reverse_nat.lock().unwrap().insert(src_port, dst_addr);
                                }

                                let mut ip_pkt = Ipv4Packet::new_unchecked(&mut packet_data);
                                let s_ip = ip_pkt.src_addr();
                                let d_ip = Ipv4Address::new(10, 1, 10, 1);
                                ip_pkt.set_dst_addr(d_ip);
                                ip_pkt.fill_checksum();
                                {
                                    let mut tcp_pkt = TcpPacket::new_unchecked(ip_pkt.payload_mut());
                                    tcp_pkt.set_dst_port(tcp_listener_port);
                                    tcp_pkt.fill_checksum(&s_ip.into(), &d_ip.into());
                                }
                                device.rx_queue.push(packet_data);
                                continue;
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

        // 5. Handle Relays
        let mut handles_to_remove = Vec::new();
        for (handle, socket) in sockets.iter_mut() {
            if let Some(socket) = tcp::Socket::downcast_mut(socket) {
                match socket.state() {
                    tcp::State::Established => {
                        if !tcp_relays.contains_key(&handle) {
                            if let Some(remote) = socket.remote_endpoint() {
                                if let Some(target) = reverse_nat.lock().unwrap().get(&remote.port).cloned() {
                                    let (tx_smol, rx_smol) = tokio::sync::mpsc::channel::<Vec<u8>>(1024);
                                    let (tx_rem, rx_rem) = tokio::sync::mpsc::channel::<Vec<u8>>(1024);

                                    tcp_relays.insert(handle, TcpRelay {
                                        to_remote: tx_rem,
                                        from_remote: rx_smol,
                                        client_port: remote.port,
                                    });

                                    let protector_clone = protector_arc.clone();
                                    tokio::spawn(async move {
                                        if let Ok(mut stream) = create_protected_tcp_stream(target, &protector_clone).await {
                                            // ASYNC HANDSHAKE WAIT
                                            if let Ok(_) = stream.writable().await {
                                                if let Ok(None) = stream.take_error() {
                                                    let (mut reader, mut writer) = stream.split();
                                                    let mut buf = [0u8; 32768];
                                                    let mut rx_rem_inner = rx_rem;
                                                    loop {
                                                        tokio::select! {
                                                            res = rx_rem_inner.recv() => {
                                                                if let Some(data) = res {
                                                                    if writer.write_all(&data).await.is_err() { break; }
                                                                } else { break; }
                                                            }
                                                            res = reader.read(&mut buf) => {
                                                                match res {
                                                                    Ok(0) | Err(_) => break,
                                                                    Ok(n) => {
                                                                        if tx_smol.send(buf[..n].to_vec()).await.is_err() { break; }
                                                                    }
                                                                }
                                                            }
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    });
                                }
                            }
                        }

                        if let Some(relay) = tcp_relays.get_mut(&handle) {
                            while socket.can_recv() {
                                let mut data = vec![0u8; 16384];
                                match socket.recv_slice(&mut data) {
                                    Ok(n) if n > 0 => { let _ = relay.to_remote.try_send(data[..n].to_vec()); }
                                    _ => break,
                                }
                            }
                            while socket.can_send() {
                                match relay.from_remote.try_recv() {
                                    Ok(data) => { let _ = socket.send_slice(&data); }
                                    _ => break,
                                }
                            }
                        }
                    }
                    tcp::State::Closed | tcp::State::TimeWait | tcp::State::Closing | tcp::State::LastAck => {
                        handles_to_remove.push(handle);
                    }
                    _ => {}
                }
            }
        }

        for handle in handles_to_remove {
            if let Some(relay) = tcp_relays.remove(&handle) {
                reverse_nat.lock().unwrap().remove(&relay.client_port);
            }
            sockets.remove(handle);
        }

        tokio::task::yield_now().await;
    }
    unsafe { libc::close(duped_fd); }
    Ok(())
}

#[cfg(unix)]
async fn create_protected_tcp_stream(addr: SocketAddr, protector: &Arc<dyn SocketProtector>) -> anyhow::Result<TcpStream> {
    use socket2::{Socket, Domain, Type, Protocol};
    let domain = Domain::IPV4;
    let socket = Socket::new(domain, Type::STREAM, Some(Protocol::TCP))?;
    if !protector.protect_socket(socket.as_raw_fd()) {
        return Err(anyhow::anyhow!("Protection failed"));
    }
    socket.set_nonblocking(true)?;
    match socket.connect(&addr.into()) {
        Ok(_) => {}
        Err(ref e) if e.raw_os_error() == Some(115) => {} // EINPROGRESS
        Err(e) => return Err(e.into()),
    }
    let std_socket: std::net::TcpStream = unsafe { std::net::TcpStream::from_raw_fd(socket.as_raw_fd()) };
    Ok(TcpStream::from_std(std_socket)?)
}

#[cfg(unix)]
fn create_protected_udp_socket(addr: SocketAddr, protector: &Arc<dyn SocketProtector>) -> anyhow::Result<UdpSocket> {
    use socket2::{Socket, Domain, Type, Protocol};
    let domain = Domain::IPV4;
    let socket = Socket::new(domain, Type::DGRAM, Some(Protocol::UDP))?;
    if !protector.protect_socket(socket.as_raw_fd()) {
        return Err(anyhow::anyhow!("Protection failed"));
    }
    let std_socket: std::net::UdpSocket = unsafe { std::net::UdpSocket::from_raw_fd(socket.as_raw_fd()) };
    Ok(UdpSocket::from_std(std_socket)?)
}
