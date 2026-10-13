use std::sync::Arc;
use std::sync::atomic::{AtomicBool, AtomicU64, Ordering};
use std::sync::Mutex as StdMutex;
use std::collections::HashMap;
use std::time::{Duration, Instant};
use log::{info, debug, warn};

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

#[derive(uniffi::Enum)]
pub enum DnsServerType {
    Plain,
    DoH,
    DoH3,
    DoT,
}

#[derive(uniffi::Record)]
pub struct DnsServer {
    pub address: String,
    pub server_type: DnsServerType,
    pub is_enabled: bool,
}

fn create_server_name(host: &str) -> Option<rustls::pki_types::ServerName<'static>> {
    if let Ok(ip) = host.parse::<std::net::IpAddr>() {
        return Some(rustls::pki_types::ServerName::IpAddress(ip.into()));
    }
    let host_static: &'static str =
        Box::leak(host.to_string().into_boxed_str());
    rustls::pki_types::ServerName::try_from(host_static).ok()
}

fn find_header_end(data: &[u8]) -> Option<usize> {
    for i in 0..data.len().saturating_sub(3) {
        if data[i] == 0x0D
            && data[i + 1] == 0x0A
            && data[i + 2] == 0x0D
            && data[i + 3] == 0x0A
        {
            return Some(i + 4);
        }
    }
    None
}

fn parse_http_body(data: &[u8]) -> Option<Vec<u8>> {
    let header_end = find_header_end(data)?;
    let body = &data[header_end..];
    if body.is_empty() {
        return None;
    }
    Some(body.to_vec())
}

static REEL_DOMAINS: &[&str] = &[
    "tiktok.com",
    "tiktokv.com",
    "instagram.com",
    "fbcdn.net",
    "youtube.com",
    "youtu.be",
    "googlevideo.com",
    "snapchat.com",
    "facebook.com",
    "fbscdn.com",
];

fn parse_dns_query_name(query: &[u8]) -> Option<String> {
    if query.len() < 12 {
        return None;
    }
    let qdcount = u16::from_be_bytes([query[4], query[5]]);
    if qdcount == 0 {
        return None;
    }
    let mut pos = 12usize;
    let mut labels = Vec::new();
    while pos < query.len() {
        let len = query[pos] as usize;
        if len == 0 {
            break;
        }
        if pos + len >= query.len() {
            return None;
        }
        labels.push(std::str::from_utf8(&query[pos + 1..pos + 1 + len]).ok()?);
        pos += 1 + len;
    }
    if labels.is_empty() {
        return None;
    }
    Some(labels.join("."))
}

fn is_reel_domain(domain: &str) -> bool {
    let lower = domain.to_lowercase();
    REEL_DOMAINS.iter().any(|d| lower.ends_with(d) || lower == *d)
}

fn base64url_encode(data: &[u8]) -> String {
    const URL_SAFE: &[u8] = b"ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_";
    let mut result = String::with_capacity((data.len() + 2) / 3 * 4);
    for chunk in data.chunks(3) {
        let b0 = chunk[0] as u32;
        let b1 = if chunk.len() > 1 { chunk[1] as u32 } else { 0 };
        let b2 = if chunk.len() > 2 { chunk[2] as u32 } else { 0 };
        let triple = (b0 << 16) | (b1 << 8) | b2;
        result.push(URL_SAFE[((triple >> 18) & 0x3F) as usize] as char);
        result.push(URL_SAFE[((triple >> 12) & 0x3F) as usize] as char);
        if chunk.len() > 1 {
            result.push(URL_SAFE[((triple >> 6) & 0x3F) as usize] as char);
        }
        if chunk.len() > 2 {
            result.push(URL_SAFE[(triple & 0x3F) as usize] as char);
        }
    }
    result
}

struct DnsResolver {
    tls_config: Arc<rustls::ClientConfig>,
    cache: StdMutex<HashMap<Vec<u8>, (Vec<u8>, Instant)>>,
}

impl DnsResolver {
    fn new() -> Self {
        let mut root_store = rustls::RootCertStore::empty();
        root_store.extend(webpki_roots::TLS_SERVER_ROOTS.iter().cloned());
        let tls_config = Arc::new(
            rustls::ClientConfig::builder()
                .with_root_certificates(root_store)
                .with_no_client_auth()
        );

        DnsResolver {
            tls_config,
            cache: StdMutex::new(HashMap::new()),
        }
    }

    fn check_cache(&self, query: &[u8]) -> Option<Vec<u8>> {
        let cache = self.cache.lock().ok()?;
        let (response, expires_at) = cache.get(query)?;
        if *expires_at > Instant::now() {
            Some(response.clone())
        } else {
            None
        }
    }

    fn update_cache(&self, query: Vec<u8>, response: Vec<u8>) {
        let ttl = Duration::from_secs(300);
        let expires_at = Instant::now() + ttl;
        if let Ok(mut cache) = self.cache.lock() {
            cache.insert(query, (response, expires_at));
            if cache.len() > 500 {
                let oldest_key = cache
                    .iter()
                    .min_by_key(|(_, (_, expiry))| *expiry)
                    .map(|(k, _)| k.clone());
                if let Some(key) = oldest_key {
                    cache.remove(&key);
                }
            }
        }
    }
}

fn is_valid_dns_response(query: &[u8], response: &[u8]) -> bool {
    if query.len() < 2 || response.len() < 2 {
        return false;
    }
    let query_id = (query[0] as u16) << 8 | query[1] as u16;
    let response_id = (response[0] as u16) << 8 | response[1] as u16;
    if query_id != response_id {
        warn!(
            "DNS response ID mismatch: expected {:04x}, got {:04x}",
            query_id, response_id
        );
        return false;
    }
    let qr = (response[2] as u16 & 0x80) != 0;
    if !qr {
        warn!("DNS response missing QR bit (expected response, got query)");
        return false;
    }
    true
}

fn is_dns_truncated(response: &[u8]) -> bool {
    if response.len() < 3 {
        return false;
    }
    (response[2] as u8 & 0x02) != 0
}

impl DnsResolver {
    fn resolve(
        &self,
        query: &[u8],
        servers: &[DnsServer],
        protector: &dyn SocketProtector,
    ) -> Option<Vec<u8>> {
        if let Some(cached) = self.check_cache(query) {
            debug!("DNS cache hit for query ({} bytes)", query.len());
            return Some(cached);
        }

        debug!("DNS cache miss, resolving with {} servers", servers.len());

        for server in servers {
            if !server.is_enabled {
                continue;
            }

            let result = match server.server_type {
                DnsServerType::Plain => {
                    self.resolve_plain(query, &server.address, protector)
                }
                DnsServerType::DoH | DnsServerType::DoH3 => {
                    self.resolve_doh(query, &server.address, protector)
                }
                DnsServerType::DoT => {
                    self.resolve_dot(query, &server.address, protector)
                }
            };

            if let Some(resp) = result {
                if !is_valid_dns_response(query, &resp) {
                    warn!("Invalid DNS response from server, discarding");
                    continue;
                }
                if is_dns_truncated(&resp) {
                    warn!("DNS response truncated (TC flag set), consider TCP");
                }
                self.update_cache(query.to_vec(), resp.clone());
                return Some(resp);
            }
        }

        warn!("All DNS servers exhausted for query ({} bytes)", query.len());
        None
    }

    fn resolve_plain(
        &self,
        query: &[u8],
        address: &str,
        protector: &dyn SocketProtector,
    ) -> Option<Vec<u8>> {
        use std::net::UdpSocket;

        info!("DNS (Plain) -> {}", address);
        let server_addr: std::net::SocketAddr = format!("{}:53", address).parse().ok()?;

        let socket = UdpSocket::bind("0.0.0.0:0").ok()?;
        #[cfg(unix)]
        {
            let _ = protector.protect_socket(socket.as_raw_fd());
        }
        socket.set_read_timeout(Some(Duration::from_secs(5))).ok()?;
        socket.connect(server_addr).ok()?;

        socket.send(query).ok()?;
        let mut buf = vec![0u8; 4096];
        let n = socket.recv(&mut buf).ok()?;
        buf.truncate(n);
        Some(buf)
    }

    fn resolve_doh(
        &self,
        query: &[u8],
        url: &str,
        protector: &dyn SocketProtector,
    ) -> Option<Vec<u8>> {
        use std::io::{Read, Write};
        use std::net::ToSocketAddrs;
        use rustls::ClientConnection;
        use rustls::StreamOwned;

        info!("DNS (DoH) -> {}", url);

        let rest = url.strip_prefix("https://")?;
        let (host_and_port, path) = rest.split_once('/').unwrap_or((rest, ""));
        let (host, port_str) = host_and_port
            .split_once(':')
            .unwrap_or((host_and_port, "443"));
        let port: u16 = port_str.parse().unwrap_or(443);
        let addr = (host, port).to_socket_addrs().ok()?.next()?;

        // Try POST first (RFC 8484)
        if let Some(resp) = self.doh_post(query, url, path, host, addr, protector) {
            if is_valid_dns_response(query, &resp) {
                return Some(resp);
            }
        }

        // Fallback to GET
        self.doh_get(query, url, path, host, addr, protector)
    }

    fn doh_post(
        &self,
        query: &[u8],
        _url: &str,
        path: &str,
        host: &str,
        addr: std::net::SocketAddr,
        protector: &dyn SocketProtector,
    ) -> Option<Vec<u8>> {
        use std::io::Read;
        use std::io::Write;
        use rustls::ClientConnection;
        use rustls::StreamOwned;

        let tcp = std::net::TcpStream::connect_timeout(&addr, Duration::from_secs(5)).ok()?;
        #[cfg(unix)]
        {
            let _ = protector.protect_socket(tcp.as_raw_fd());
        }
        tcp.set_read_timeout(Some(Duration::from_secs(5))).ok()?;
        tcp.set_write_timeout(Some(Duration::from_secs(5))).ok()?;

        let server_name = create_server_name(host)?;
        let conn = ClientConnection::new(self.tls_config.clone(), server_name).ok()?;
        let mut tls = StreamOwned::new(conn, tcp);

        let http_request = format!(
            "POST /{} HTTP/1.1\r\n\
             Host: {}\r\n\
             Content-Type: application/dns-message\r\n\
             Accept: application/dns-message\r\n\
             Content-Length: {}\r\n\
             Connection: close\r\n\
             User-Agent: Fityah/1.0\r\n\
             \r\n",
            path,
            host,
            query.len()
        );

        tls.write_all(http_request.as_bytes()).ok()?;
        tls.write_all(query).ok()?;
        tls.flush().ok()?;

        let mut buf = Vec::new();
        tls.read_to_end(&mut buf).ok()?;

        parse_http_body(&buf)
    }

    fn doh_get(
        &self,
        query: &[u8],
        url: &str,
        path: &str,
        host: &str,
        addr: std::net::SocketAddr,
        protector: &dyn SocketProtector,
    ) -> Option<Vec<u8>> {
        use std::io::Read;
        use rustls::ClientConnection;
        use rustls::StreamOwned;

        debug!("DNS (DoH GET fallback) -> {}", url);

        let tcp = std::net::TcpStream::connect_timeout(&addr, Duration::from_secs(5)).ok()?;
        #[cfg(unix)]
        {
            let _ = protector.protect_socket(tcp.as_raw_fd());
        }
        tcp.set_read_timeout(Some(Duration::from_secs(5))).ok()?;
        tcp.set_write_timeout(Some(Duration::from_secs(5))).ok()?;

        let server_name = create_server_name(host)?;
        let conn = ClientConnection::new(self.tls_config.clone(), server_name).ok()?;
        let mut tls = StreamOwned::new(conn, tcp);

        let dns_param = base64url_encode(query);
        let http_request = format!(
            "GET /{}?dns={} HTTP/1.1\r\n\
             Host: {}\r\n\
             Accept: application/dns-message\r\n\
             Connection: close\r\n\
             User-Agent: Fityah/1.0\r\n\
             \r\n",
            path, dns_param, host
        );

        use std::io::Write;
        tls.write_all(http_request.as_bytes()).ok()?;
        tls.flush().ok()?;

        let mut buf = Vec::new();
        tls.read_to_end(&mut buf).ok()?;
        parse_http_body(&buf)
    }

    fn resolve_dot(
        &self,
        query: &[u8],
        address: &str,
        protector: &dyn SocketProtector,
    ) -> Option<Vec<u8>> {
        use std::io::Write;
        use std::net::ToSocketAddrs;
        use rustls::ClientConnection;
        use rustls::StreamOwned;

        info!("DNS (DoT) -> {}", address);

        let addr = (address, 853).to_socket_addrs().ok()?.next()?;

        let tcp = std::net::TcpStream::connect_timeout(&addr, Duration::from_secs(5)).ok()?;
        #[cfg(unix)]
        {
            let _ = protector.protect_socket(tcp.as_raw_fd());
        }
        tcp.set_read_timeout(Some(Duration::from_secs(5))).ok()?;
        tcp.set_write_timeout(Some(Duration::from_secs(5))).ok()?;

        let server_name = create_server_name(address)?;
        let conn = ClientConnection::new(self.tls_config.clone(), server_name).ok()?;
        let mut tls = StreamOwned::new(conn, tcp);

        // DoT uses a 2-byte length prefix (RFC 7858)
        let len_prefix = (query.len() as u16).to_be_bytes();
        tls.write_all(&len_prefix).ok()?;
        tls.write_all(query).ok()?;
        tls.flush().ok()?;

        // Read response length prefix
        let mut len_buf = [0u8; 2];
        read_exact_buf(&mut tls, &mut len_buf).ok()?;
        let resp_len = u16::from_be_bytes(len_buf) as usize;

        // Read response payload
        let mut buf = vec![0u8; resp_len];
        read_exact_buf(&mut tls, &mut buf).ok()?;
        Some(buf)
    }
}

fn read_exact_buf<R: std::io::Read>(reader: &mut R, buf: &mut [u8]) -> std::io::Result<()> {
    reader.read_exact(buf)
}

#[derive(uniffi::Object)]
pub struct VpnController {
    stop_signal: Arc<AtomicBool>,
    resolver: DnsResolver,
    reel_count: Arc<AtomicU64>,
}

#[uniffi::export]
impl VpnController {
    #[uniffi::constructor]
    pub fn new() -> Arc<Self> {
        Arc::new(VpnController {
            stop_signal: Arc::new(AtomicBool::new(false)),
            resolver: DnsResolver::new(),
            reel_count: Arc::new(AtomicU64::new(0)),
        })
    }

    pub fn resolve_dns(
        &self,
        query: Vec<u8>,
        servers: Vec<DnsServer>,
        protector: Box<dyn SocketProtector>,
    ) -> Option<Vec<u8>> {
        debug!(
            "resolve_dns called with query ({} bytes), {} servers",
            query.len(),
            servers.len()
        );

        if let Some(domain) = parse_dns_query_name(&query) {
            if is_reel_domain(&domain) {
                let count = self.reel_count.fetch_add(1, Ordering::SeqCst) + 1;
                info!("Reel domain detected: {} (count: {})", domain, count);
            }
        }

        let result = self.resolver.resolve(&query, &servers, &*protector);
        if result.is_none() {
            warn!(
                "DNS resolution failed for query ({} bytes) across all servers",
                query.len()
            );
        }
        result
    }

    pub fn get_reel_count(&self) -> u64 {
        self.reel_count.load(Ordering::SeqCst)
    }

    pub fn reset_reel_count(&self) {
        self.reel_count.store(0, Ordering::SeqCst);
        info!("Reel count reset");
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
