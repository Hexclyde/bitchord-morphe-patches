//! Retained-socket STUN + certificate-pinned QUIC download streams.
//! All public STUN receives happen before the same UDP socket becomes Quinn's.
//! Subsequent raw socket clones send only; Quinn is the sole receiver.
use base64::{Engine as _, engine::general_purpose::STANDARD as B64};
use jni::{
    JNIEnv,
    objects::{JByteArray, JClass, JObjectArray, JString},
    sys::{jint, jlong, jstring},
};
use quinn::{
    ClientConfig, Connection, Endpoint, EndpointConfig, RecvStream, SendStream, ServerConfig,
    TransportConfig,
};
use rand::RngCore;
use rustls::pki_types::{CertificateDer, PrivatePkcs8KeyDer};
use serde::{Deserialize, Serialize};
use std::{
    collections::HashMap,
    net::{Ipv4Addr, SocketAddr, SocketAddrV4, UdpSocket},
    sync::{
        Arc, Mutex, OnceLock,
        atomic::{AtomicBool, AtomicU64, Ordering},
    },
    time::{Duration, Instant},
};
use tokio::{
    runtime::Runtime,
    sync::{Mutex as AsyncMutex, mpsc},
    time::timeout,
};

type Result<T> = std::result::Result<T, String>;
const MAGIC: u32 = 0x2112_a442;
const MAX_DESCRIPTOR: usize = 2048;
const MAX_IO: usize = 65536;
const NAME: &str = "bitchord.local";
const ALPN: &[u8] = b"bitchord-download/1";
static RT: OnceLock<Runtime> = OnceLock::new();
static NODES: OnceLock<Mutex<HashMap<u64, Arc<Node>>>> = OnceLock::new();
static LINKS: OnceLock<Mutex<HashMap<u64, Arc<Link>>>> = OnceLock::new();
static NEXT: AtomicU64 = AtomicU64::new(1);
fn runtime() -> &'static Runtime {
    RT.get_or_init(|| {
        tokio::runtime::Builder::new_multi_thread()
            .worker_threads(2)
            .enable_all()
            .thread_name("bitchord-direct")
            .build()
            .expect("Tokio runtime")
    })
}
fn nodes() -> &'static Mutex<HashMap<u64, Arc<Node>>> {
    NODES.get_or_init(|| Mutex::new(HashMap::new()))
}
fn links() -> &'static Mutex<HashMap<u64, Arc<Link>>> {
    LINKS.get_or_init(|| Mutex::new(HashMap::new()))
}
fn ms(value: i32) -> Result<Duration> {
    if !(1..=120000).contains(&value) {
        Err("Direct timeout outside bounds".into())
    } else {
        Ok(Duration::from_millis(value as u64))
    }
}
fn err(e: impl std::fmt::Display) -> String {
    format!("Direct transport: {e}")
}
fn node(id: u64) -> Result<Arc<Node>> {
    nodes()
        .lock()
        .map_err(err)?
        .get(&id)
        .cloned()
        .filter(|n| !n.closed.load(Ordering::Acquire))
        .ok_or("Direct node closed".into())
}
fn link(id: u64) -> Result<Arc<Link>> {
    links()
        .lock()
        .map_err(err)?
        .get(&id)
        .cloned()
        .filter(|n| !n.closed.load(Ordering::Acquire))
        .ok_or("Direct stream closed".into())
}

#[derive(Serialize, Deserialize, Clone, Debug)]
#[serde(deny_unknown_fields)]
struct Descriptor {
    v: u8,
    candidates: Vec<String>,
    cert: String,
}
impl Descriptor {
    fn parse(text: &str) -> Result<(Self, Vec<SocketAddr>, CertificateDer<'static>)> {
        if text.len() > MAX_DESCRIPTOR {
            return Err("Direct descriptor too large".into());
        }
        let d: Self = serde_json::from_str(text).map_err(err)?;
        if d.v != 1 || d.candidates.is_empty() || d.candidates.len() > 4 || d.cert.len() > 1536 {
            return Err("Invalid direct descriptor bounds".into());
        }
        let mut addresses = Vec::new();
        for item in &d.candidates {
            if item.len() > 32 {
                return Err("Invalid direct candidate".into());
            }
            let address: SocketAddr = item
                .parse()
                .map_err(|_| "Direct candidate must be a numeric IPv4 address".to_string())?;
            match address {
                SocketAddr::V4(a)
                    if a.port() != 0
                        && !a.ip().is_unspecified()
                        && !a.ip().is_multicast()
                        && !a.ip().is_broadcast() => {}
                _ => return Err("Invalid direct candidate".into()),
            }
            if !addresses.contains(&address) {
                addresses.push(address)
            }
        }
        let cert = B64.decode(&d.cert).map_err(err)?;
        if cert.is_empty() || cert.len() > 1152 {
            return Err("Invalid direct certificate bounds".into());
        }
        // Parsing through RootCertStore ensures DER is a usable trust anchor. No trust-all verifier.
        let cert = CertificateDer::from(cert);
        let mut roots = rustls::RootCertStore::empty();
        roots.add(cert.clone()).map_err(err)?;
        Ok((d, addresses, cert))
    }
}
struct Node {
    id: u64,
    endpoint: Endpoint,
    raw: Arc<UdpSocket>,
    descriptor: String,
    incoming: AsyncMutex<mpsc::Receiver<Arc<Link>>>,
    shutdown: tokio::sync::watch::Sender<bool>,
    closed: AtomicBool,
}
struct Link {
    owner: u64,
    connection: Connection,
    send: AsyncMutex<SendStream>,
    recv: AsyncMutex<RecvStream>,
    finished: AtomicBool,
    closed: AtomicBool,
}
impl Link {
    fn close(&self) {
        if !self.closed.swap(true, Ordering::AcqRel) {
            self.connection.close(0u32.into(), b"closed");
        }
    }
}

fn transaction(kind: u16) -> ([u8; 20], [u8; 12]) {
    let mut bytes = [0u8; 20];
    bytes[..2].copy_from_slice(&kind.to_be_bytes());
    bytes[4..8].copy_from_slice(&MAGIC.to_be_bytes());
    rand::thread_rng().fill_bytes(&mut bytes[8..]);
    let mut id = [0u8; 12];
    id.copy_from_slice(&bytes[8..]);
    (bytes, id)
}
fn binding_response(bytes: &[u8], transaction: &[u8; 12]) -> Result<SocketAddr> {
    if bytes.len() < 20
        || u16::from_be_bytes([bytes[0], bytes[1]]) != 0x0101
        || bytes[4..8] != MAGIC.to_be_bytes()
        || bytes[8..20] != *transaction
    {
        return Err("Unmatched STUN response".into());
    }
    let length = u16::from_be_bytes([bytes[2], bytes[3]]) as usize;
    if length % 4 != 0 || length + 20 != bytes.len() || bytes.len() > 2048 {
        return Err("Malformed STUN length".into());
    }
    let mut at = 20;
    let mut found = None;
    while at < bytes.len() {
        if at + 4 > bytes.len() {
            return Err("Truncated STUN attribute".into());
        }
        let kind = u16::from_be_bytes([bytes[at], bytes[at + 1]]);
        let n = u16::from_be_bytes([bytes[at + 2], bytes[at + 3]]) as usize;
        at += 4;
        if at + n > bytes.len() {
            return Err("Truncated STUN attribute".into());
        }
        if kind == 0x0020 {
            if n != 8 || bytes[at] != 0 || bytes[at + 1] != 1 {
                return Err("Unsupported STUN mapped address".into());
            }
            let port = u16::from_be_bytes([bytes[at + 2], bytes[at + 3]]) ^ ((MAGIC >> 16) as u16);
            let ip = u32::from_be_bytes(bytes[at + 4..at + 8].try_into().map_err(err)?) ^ MAGIC;
            let ip = Ipv4Addr::from(ip);
            if port == 0 || ip.is_unspecified() || ip.is_multicast() || ip.is_broadcast() {
                return Err("Invalid STUN mapped address".into());
            }
            if found.is_some() {
                return Err("Duplicate STUN mapped address".into());
            }
            found = Some(SocketAddr::V4(SocketAddrV4::new(ip, port)));
        }
        at += (n + 3) & !3;
    }
    if at != bytes.len() {
        return Err("Invalid STUN padding".into());
    }
    found.ok_or("STUN response has no XOR-MAPPED-ADDRESS".into())
}
fn resolve_stun(value: &str, wait: Duration) -> Result<SocketAddr> {
    let host = value.strip_prefix("stun:").ok_or("Invalid STUN URI")?;
    if host.len() > 260
        || host.contains('/')
        || host.contains('@')
        || host.contains('[')
        || host.bytes().any(|b| b <= 32 || b >= 127)
    {
        return Err("Invalid STUN URI".into());
    }
    // Resolve off the caller with Tokio's bounded DNS future; numeric addresses never use DNS.
    if let Ok(SocketAddr::V4(address)) = host.parse::<SocketAddr>() {
        if address.port() > 0 {
            return Ok(SocketAddr::V4(address));
        }
    }
    runtime().block_on(async {
        let mut addresses = timeout(wait, tokio::net::lookup_host(host))
            .await
            .map_err(|_| "STUN DNS timed out")?
            .map_err(err)?;
        addresses
            .find(|a| a.is_ipv4() && a.port() != 0)
            .ok_or("STUN has no IPv4 address".into())
    })
}
fn discover(
    socket: &UdpSocket,
    servers: &[String],
    deadline: Instant,
) -> (Vec<SocketAddr>, Vec<SocketAddr>) {
    let mut candidates = Vec::new();
    let mut resolved = Vec::new();
    for source in servers.iter().take(3) {
        let remaining = deadline.saturating_duration_since(Instant::now());
        if remaining < Duration::from_millis(50) {
            break;
        }
        let Ok(server) = resolve_stun(source, remaining.min(Duration::from_millis(350))) else {
            continue;
        };
        resolved.push(server);
        let (request, id) = transaction(1);
        let round_deadline = Instant::now()
            + deadline
                .saturating_duration_since(Instant::now())
                .min(Duration::from_millis(500));
        for _ in 0..2 {
            if socket.send_to(&request, server).is_err() {
                break;
            }
            let until = round_deadline
                .saturating_duration_since(Instant::now())
                .min(Duration::from_millis(200));
            if until.is_zero() {
                break;
            }
            let _ = socket.set_read_timeout(Some(until));
            let mut buffer = [0u8; 2048];
            if let Ok((n, peer)) = socket.recv_from(&mut buffer) {
                if peer == server {
                    if let Ok(mapped) = binding_response(&buffer[..n], &id) {
                        if !candidates.contains(&mapped) {
                            candidates.push(mapped)
                        }
                        break;
                    }
                }
            }
        }
    }
    // A route-selected LAN address supports same-LAN peers when no public STUN responds.
    if let Ok(route) = UdpSocket::bind("0.0.0.0:0") {
        let destination = resolved
            .first()
            .copied()
            .unwrap_or(SocketAddr::from(([192, 0, 2, 1], 9)));
        if route.connect(destination).is_ok() {
            if let Ok(local) = route.local_addr() {
                if let (SocketAddr::V4(ip), Ok(bound)) = (local, socket.local_addr()) {
                    let candidate = SocketAddr::from((*ip.ip(), bound.port()));
                    if !ip.ip().is_unspecified()
                        && !candidates.contains(&candidate)
                        && candidates.len() < 4
                    {
                        candidates.push(candidate)
                    }
                }
            }
        }
    }
    let _ = socket.set_read_timeout(None);
    (candidates, resolved)
}
fn make_node(servers: Vec<String>, wait: Duration) -> Result<Arc<Node>> {
    if servers.len() > 3 || servers.iter().any(|s| s.len() > 300) {
        return Err("Too many STUN sources".into());
    }
    if nodes().lock().map_err(err)?.len() >= 4 {
        return Err("Direct node capacity reached".into());
    }
    let socket = UdpSocket::bind("0.0.0.0:0").map_err(err)?;
    let port = socket.local_addr().map_err(err)?.port();
    let (candidates, resolved) = discover(
        &socket,
        &servers,
        Instant::now() + wait.min(Duration::from_millis(1800)),
    );
    build_node(socket, port, candidates, resolved)
}
fn build_node(
    socket: UdpSocket,
    port: u16,
    candidates: Vec<SocketAddr>,
    resolved: Vec<SocketAddr>,
) -> Result<Arc<Node>> {
    if candidates.is_empty() {
        return Err("Direct IPv4 candidate unavailable".into());
    }
    let rcgen::CertifiedKey { cert, key_pair } =
        rcgen::generate_simple_self_signed(vec![NAME.to_string()]).map_err(err)?;
    let der = cert.der().clone();
    let mut tls = rustls::ServerConfig::builder()
        .with_no_client_auth()
        .with_single_cert(
            vec![der.clone()],
            PrivatePkcs8KeyDer::from(key_pair.serialize_der()).into(),
        )
        .map_err(err)?;
    tls.alpn_protocols = vec![ALPN.to_vec()];
    let crypto = quinn::crypto::rustls::QuicServerConfig::try_from(tls).map_err(err)?;
    let mut server = ServerConfig::with_crypto(Arc::new(crypto));
    let mut transport = TransportConfig::default();
    transport
        .max_concurrent_bidi_streams(1u8.into())
        .max_concurrent_uni_streams(0u8.into());
    transport.max_idle_timeout(Some(Duration::from_secs(90).try_into().map_err(err)?));
    transport.keep_alive_interval(Some(Duration::from_secs(15)));
    server.transport_config(Arc::new(transport));
    server
        .max_incoming(32)
        .incoming_buffer_size(65536)
        .incoming_buffer_size_total(2 * 1024 * 1024);
    socket.set_nonblocking(true).map_err(err)?;
    let raw = Arc::new(socket.try_clone().map_err(err)?);
    let _entered = runtime().enter();
    let endpoint = Endpoint::new(
        EndpointConfig::default(),
        Some(server),
        socket,
        Arc::new(quinn::TokioRuntime),
    )
    .map_err(err)?;
    if endpoint.local_addr().map_err(err)?.port() != port {
        return Err("Direct socket mapping changed".into());
    }
    let descriptor = serde_json::to_string(&Descriptor {
        v: 1,
        candidates: candidates.iter().take(4).map(ToString::to_string).collect(),
        cert: B64.encode(der.as_ref()),
    })
    .map_err(err)?;
    if descriptor.len() > MAX_DESCRIPTOR {
        return Err("Direct descriptor exceeds bounds".into());
    }
    let (tx, rx) = mpsc::channel(16);
    let (shutdown, _) = tokio::sync::watch::channel(false);
    let node = Arc::new(Node {
        id: NEXT.fetch_add(1, Ordering::Relaxed),
        endpoint,
        raw,
        descriptor,
        incoming: AsyncMutex::new(rx),
        shutdown,
        closed: AtomicBool::new(false),
    });
    let accept_node = node.clone();
    let pending = Arc::new(tokio::sync::Semaphore::new(16));
    runtime().spawn(async move {
        while let Some(incoming) = accept_node.endpoint.accept().await {
            if accept_node.closed.load(Ordering::Acquire) {
                break;
            }
            let sender = tx.clone();
            let owner = accept_node.id;
            // Bounded server-side concurrent handshakes and stream queues prevent untrusted
            // Internet peers allocating unbounded tasks before BulkFileProtocol auth.
            if sender.capacity() == 0 {
                incoming.refuse();
                continue;
            }
            let Ok(permit) = pending.clone().try_acquire_owned() else {
                incoming.refuse();
                continue;
            };
            tokio::spawn(async move {
                let _permit = permit;
                let result = timeout(Duration::from_secs(5), async {
                    let connection = incoming.await.map_err(err)?;
                    let (mut send, mut recv) = connection.accept_bi().await.map_err(err)?;
                    let mut hello = [0u8; 1];
                    recv.read_exact(&mut hello).await.map_err(err)?;
                    if hello != [1] {
                        connection.close(1u32.into(), b"bad protocol");
                        return Err("Direct protocol mismatch".to_string());
                    }
                    send.write_all(&[1]).await.map_err(err)?;
                    Ok::<_, String>(Arc::new(Link {
                        owner,
                        connection,
                        send: AsyncMutex::new(send),
                        recv: AsyncMutex::new(recv),
                        finished: AtomicBool::new(false),
                        closed: AtomicBool::new(false),
                    }))
                })
                .await;
                if let Ok(Ok(link)) = result {
                    if let Err(e) = sender.try_send(link) {
                        e.into_inner().close()
                    }
                }
            });
        }
    });
    let keep = node.clone();
    runtime().spawn(async move {
        let mut timer = tokio::time::interval(Duration::from_secs(15));
        timer.tick().await;
        let mut stopping = keep.shutdown.subscribe();
        loop {
            tokio::select! { _ = timer.tick() => {}, _ = stopping.changed() => break }
            if keep.closed.load(Ordering::Acquire) {
                break;
            }
            for server in &resolved {
                let (packet, _) = transaction(0x0011);
                let _ = keep.raw.send_to(&packet, server);
            }
        }
    });
    Ok(node)
}
fn register_node(node: Arc<Node>) -> Result<u64> {
    let id = node.id;
    let mut map = nodes().lock().map_err(err)?;
    if map.len() >= 4 {
        node.closed.store(true, Ordering::Release);
        node.shutdown.send_replace(true);
        node.endpoint.close(0u32.into(), b"capacity");
        return Err("Direct node capacity reached".into());
    }
    map.insert(id, node);
    Ok(id)
}
fn register_link(link: Arc<Link>) -> Result<u64> {
    let mut map = links().lock().map_err(err)?;
    if map.len() >= 64 {
        link.close();
        return Err("Direct stream capacity reached".into());
    }
    let id = NEXT.fetch_add(1, Ordering::Relaxed);
    map.insert(id, link);
    Ok(id)
}
async fn connect_node(node: Arc<Node>, text: &str, wait: Duration) -> Result<Arc<Link>> {
    let (_, addresses, cert) = Descriptor::parse(text)?;
    let mut roots = rustls::RootCertStore::empty();
    roots.add(cert).map_err(err)?;
    let mut tls = rustls::ClientConfig::builder()
        .with_root_certificates(roots)
        .with_no_client_auth();
    tls.alpn_protocols = vec![ALPN.to_vec()];
    let crypto = quinn::crypto::rustls::QuicClientConfig::try_from(tls).map_err(err)?;
    let client = ClientConfig::new(Arc::new(crypto));
    let deadline = tokio::time::Instant::now() + wait;
    let mut last = "Direct connection failed".to_string();
    for address in addresses {
        if node.closed.load(Ordering::Acquire) {
            return Err("Direct node closed".into());
        }
        let remaining = deadline.saturating_duration_since(tokio::time::Instant::now());
        if remaining.is_zero() {
            break;
        }
        let attempt = async {
            let connection = node
                .endpoint
                .connect_with(client.clone(), address, NAME)
                .map_err(err)?
                .await
                .map_err(err)?;
            let (mut send, mut recv) = connection.open_bi().await.map_err(err)?;
            send.write_all(&[1]).await.map_err(err)?;
            let mut hello = [0u8; 1];
            recv.read_exact(&mut hello).await.map_err(err)?;
            if hello != [1] {
                connection.close(1u32.into(), b"bad protocol");
                return Err("Direct protocol mismatch".to_string());
            }
            Ok(Arc::new(Link {
                owner: node.id,
                connection,
                send: AsyncMutex::new(send),
                recv: AsyncMutex::new(recv),
                finished: AtomicBool::new(false),
                closed: AtomicBool::new(false),
            }))
        };
        match timeout(remaining.min(Duration::from_secs(2)), attempt).await {
            Ok(Ok(link)) => {
                if node.closed.load(Ordering::Acquire) {
                    link.close();
                    return Err("Direct node closed".into());
                }
                return Ok(link);
            }
            Ok(Err(e)) => last = e,
            Err(_) => last = "Direct connection timed out".into(),
        }
    }
    Err(last)
}
fn close_node(id: u64) {
    if let Ok(mut map) = nodes().lock() {
        if let Some(node) = map.remove(&id) {
            node.closed.store(true, Ordering::Release);
            node.shutdown.send_replace(true);
            node.endpoint.close(0u32.into(), b"closed");
        }
    }
    if let Ok(mut map) = links().lock() {
        map.retain(|_, link| {
            if link.owner == id {
                link.close();
                false
            } else {
                true
            }
        });
    }
}
fn close_link(id: u64) {
    if let Ok(mut map) = links().lock() {
        if let Some(link) = map.remove(&id) {
            link.close();
        }
    }
}
fn punch_node(node: &Node, text: &str, wait: Duration) -> Result<()> {
    let (_, addresses, _) = Descriptor::parse(text)?;
    let deadline = Instant::now() + wait.min(Duration::from_millis(600));
    for _ in 0..6 {
        if node.closed.load(Ordering::Acquire) {
            return Err("Direct node closed".into());
        }
        for peer in &addresses {
            let (packet, _) = transaction(0x0011);
            let _ = node.raw.send_to(&packet, peer);
        }
        let remaining = deadline.saturating_duration_since(Instant::now());
        if remaining.is_zero() {
            break;
        }
        std::thread::sleep(remaining.min(Duration::from_millis(100)));
    }
    Ok(())
}
fn jtext(env: &mut JNIEnv<'_>, value: JString<'_>) -> Result<String> {
    let text: String = env.get_string(&value).map_err(err)?.into();
    if text.len() > MAX_DESCRIPTOR {
        return Err("Direct descriptor exceeds bounds".into());
    }
    Ok(text)
}
fn failure(env: &mut JNIEnv<'_>, message: String) {
    let _ = env.throw_new("java/io/IOException", message);
}
fn guarded<T>(f: impl FnOnce() -> Result<T>) -> Result<T> {
    std::panic::catch_unwind(std::panic::AssertUnwindSafe(f))
        .map_err(|_| "Direct native operation failed".to_string())?
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_app_bitchord_soulseek_DirectNative_start(
    mut env: JNIEnv,
    _class: JClass,
    sources: JObjectArray,
    timeout_ms: jint,
) -> jlong {
    let result = guarded(|| {
        let wait = ms(timeout_ms)?;
        let count = env.get_array_length(&sources).map_err(err)?;
        if !(0..=3).contains(&count) {
            return Err("Too many STUN sources".into());
        }
        let mut servers = Vec::new();
        for i in 0..count {
            let item = JString::from(env.get_object_array_element(&sources, i).map_err(err)?);
            let text: String = env.get_string(&item).map_err(err)?.into();
            if text.len() > 300 {
                return Err("Invalid STUN source".into());
            }
            servers.push(text)
        }
        register_node(make_node(servers, wait)?)
    });
    match result {
        Ok(id) => id as jlong,
        Err(e) => {
            failure(&mut env, e);
            0
        }
    }
}
#[unsafe(no_mangle)]
pub extern "system" fn Java_app_bitchord_soulseek_DirectNative_endpoint(
    mut env: JNIEnv,
    _class: JClass,
    id: jlong,
) -> jstring {
    match guarded(|| Ok(node(id as u64)?.descriptor.clone())) {
        Ok(text) => match env.new_string(text) {
            Ok(value) => value.into_raw(),
            Err(e) => {
                failure(&mut env, err(e));
                std::ptr::null_mut()
            }
        },
        Err(e) => {
            failure(&mut env, e);
            std::ptr::null_mut()
        }
    }
}
#[unsafe(no_mangle)]
pub extern "system" fn Java_app_bitchord_soulseek_DirectNative_connect(
    mut env: JNIEnv,
    _class: JClass,
    id: jlong,
    descriptor: JString,
    timeout_ms: jint,
) -> jlong {
    let result = guarded(|| {
        let wait = ms(timeout_ms)?;
        let text = jtext(&mut env, descriptor)?;
        register_link(runtime().block_on(connect_node(node(id as u64)?, &text, wait))?)
    });
    match result {
        Ok(id) => id as jlong,
        Err(e) => {
            failure(&mut env, e);
            0
        }
    }
}
async fn accept_handle(node: Arc<Node>, wait: Duration) -> Result<u64> {
    let mut stopping = node.shutdown.subscribe();
    let incoming = timeout(wait, async {
        if *stopping.borrow() {
            return Err("Direct listener closed".to_string());
        }
        tokio::select! {
            _ = stopping.changed() => Err("Direct listener closed".to_string()),
            next = async { let mut queue = node.incoming.lock().await; queue.recv().await } =>
                next.ok_or("Direct listener closed".to_string()),
        }
    })
    .await;
    match incoming {
        Ok(Ok(link)) => {
            if node.closed.load(Ordering::Acquire) {
                link.close();
                return Err("Direct listener closed".into());
            }
            match register_link(link) {
                Err(message) if message == "Direct stream capacity reached" => Ok(0),
                result => result,
            }
        }
        Ok(Err(message)) => Err(message),
        Err(_) => Ok(0),
    }
}
#[unsafe(no_mangle)]
pub extern "system" fn Java_app_bitchord_soulseek_DirectNative_accept(
    mut env: JNIEnv,
    _class: JClass,
    id: jlong,
    timeout_ms: jint,
) -> jlong {
    let result = guarded(|| runtime().block_on(accept_handle(node(id as u64)?, ms(timeout_ms)?)));
    match result {
        Ok(id) => id as jlong,
        Err(message) => {
            failure(&mut env, message);
            0
        }
    }
}
#[unsafe(no_mangle)]
pub extern "system" fn Java_app_bitchord_soulseek_DirectNative_punch(
    mut env: JNIEnv,
    _class: JClass,
    id: jlong,
    descriptor: JString,
    timeout_ms: jint,
) {
    let result = guarded(|| {
        let wait = ms(timeout_ms)?;
        let text = jtext(&mut env, descriptor)?;
        punch_node(node(id as u64)?.as_ref(), &text, wait)
    });
    if let Err(e) = result {
        failure(&mut env, e)
    }
}
fn bounds(env: &JNIEnv, array: &JByteArray, offset: jint, length: jint) -> Result<usize> {
    let size = env.get_array_length(array).map_err(err)?;
    if offset < 0 || length < 0 || length as usize > MAX_IO || offset > size - length {
        return Err("Direct I/O bounds invalid".into());
    }
    Ok(length as usize)
}
#[unsafe(no_mangle)]
pub extern "system" fn Java_app_bitchord_soulseek_DirectNative_read(
    mut env: JNIEnv,
    _class: JClass,
    id: jlong,
    array: JByteArray,
    offset: jint,
    length: jint,
    timeout_ms: jint,
) -> jint {
    let result = guarded(|| {
        let wait = ms(timeout_ms)?;
        let n = bounds(&env, &array, offset, length)?;
        if n == 0 {
            return Ok(0);
        }
        let link = link(id as u64)?;
        let mut buffer = vec![0u8; n];
        let count = runtime().block_on(async {
            timeout(wait, async {
                let mut recv = link.recv.lock().await;
                recv.read(&mut buffer).await.map_err(err)
            })
            .await
            .map_err(|_| "Direct read timed out".to_string())?
        })?;
        if let Some(n) = count {
            let signed: Vec<i8> = buffer[..n].iter().map(|b| *b as i8).collect();
            env.set_byte_array_region(&array, offset, &signed)
                .map_err(err)?;
            Ok(n as i32)
        } else {
            Ok(-1)
        }
    });
    match result {
        Ok(n) => n,
        Err(e) => {
            failure(&mut env, e);
            -1
        }
    }
}
#[unsafe(no_mangle)]
pub extern "system" fn Java_app_bitchord_soulseek_DirectNative_write(
    mut env: JNIEnv,
    _class: JClass,
    id: jlong,
    array: JByteArray,
    offset: jint,
    length: jint,
    timeout_ms: jint,
) {
    let result = guarded(|| {
        let wait = ms(timeout_ms)?;
        let n = bounds(&env, &array, offset, length)?;
        if n == 0 {
            return Ok(());
        }
        let mut signed = vec![0i8; n];
        env.get_byte_array_region(&array, offset, &mut signed)
            .map_err(err)?;
        let buffer: Vec<u8> = signed.into_iter().map(|b| b as u8).collect();
        let link = link(id as u64)?;
        runtime().block_on(async {
            timeout(wait, async {
                let mut send = link.send.lock().await;
                send.write_all(&buffer).await.map_err(err)
            })
            .await
            .map_err(|_| "Direct write timed out".to_string())?
        })
    });
    if let Err(e) = result {
        failure(&mut env, e)
    }
}
async fn finish_link(link: Arc<Link>, wait: Duration) -> Result<()> {
    timeout(wait, async {
        let mut send = link.send.lock().await;
        if link.finished.load(Ordering::Acquire) {
            return Ok(());
        }
        send.finish().map_err(err)?;
        if send.stopped().await.map_err(err)?.is_some() {
            return Err("Direct peer rejected stream".into());
        }
        link.finished.store(true, Ordering::Release);
        Ok(())
    })
    .await
    .map_err(|_| "Direct graceful finish timed out".to_string())?
}
#[unsafe(no_mangle)]
pub extern "system" fn Java_app_bitchord_soulseek_DirectNative_finish(
    mut env: JNIEnv,
    _class: JClass,
    id: jlong,
    timeout_ms: jint,
) {
    let result = guarded(|| runtime().block_on(finish_link(link(id as u64)?, ms(timeout_ms)?)));
    if let Err(e) = result {
        failure(&mut env, e)
    }
}
#[unsafe(no_mangle)]
pub extern "system" fn Java_app_bitchord_soulseek_DirectNative_closeConnection(
    _env: JNIEnv,
    _class: JClass,
    id: jlong,
) {
    let _ = guarded(|| {
        close_link(id as u64);
        Ok(())
    });
}
#[unsafe(no_mangle)]
pub extern "system" fn Java_app_bitchord_soulseek_DirectNative_closeNode(
    _env: JNIEnv,
    _class: JClass,
    id: jlong,
) {
    let _ = guarded(|| {
        close_node(id as u64);
        Ok(())
    });
}

#[cfg(test)]
mod tests {
    use super::*;
    fn mapped_reply(request: &[u8], source: SocketAddr) -> Vec<u8> {
        let SocketAddr::V4(address) = source else {
            panic!("IPv4 fixture")
        };
        let mut result = vec![1, 1, 0, 12];
        result.extend_from_slice(&MAGIC.to_be_bytes());
        result.extend_from_slice(&request[8..20]);
        result.extend_from_slice(&[0, 0x20, 0, 8, 0, 1]);
        result.extend_from_slice(&(address.port() ^ ((MAGIC >> 16) as u16)).to_be_bytes());
        result.extend_from_slice(&(u32::from(*address.ip()) ^ MAGIC).to_be_bytes());
        result
    }
    fn loop_node() -> Arc<Node> {
        let socket = UdpSocket::bind("127.0.0.1:0").unwrap();
        let local = socket.local_addr().unwrap();
        build_node(socket, local.port(), vec![local], vec![]).unwrap()
    }
    fn stop(node: &Node) {
        node.closed.store(true, Ordering::Release);
        node.shutdown.send_replace(true);
        node.endpoint.close(0u32.into(), b"test closed")
    }
    #[test]
    fn stun_parser_transaction_bounds_and_xor() {
        let (request, id) = transaction(1);
        let source: SocketAddr = "203.0.113.7:32123".parse().unwrap();
        let response = mapped_reply(&request, source);
        assert_eq!(binding_response(&response, &id).unwrap(), source);
        let mut wrong = response.clone();
        wrong[8] ^= 1;
        assert!(binding_response(&wrong, &id).is_err());
        for size in 0..response.len() {
            assert!(binding_response(&response[..size], &id).is_err())
        }
        let mut wrong = response.clone();
        wrong[2] = 0;
        wrong[3] = 8;
        assert!(binding_response(&wrong, &id).is_err());
        let mut wrong = response.clone();
        wrong[25] = 2;
        assert!(binding_response(&wrong, &id).is_err());
        let mut duplicate = response.clone();
        duplicate.extend_from_slice(&response[20..]);
        duplicate[3] = 24;
        assert!(binding_response(&duplicate, &id).is_err());
    }
    #[test]
    fn real_quic_uses_same_retained_stun_socket_and_pinned_certificate() {
        let stun = UdpSocket::bind("127.0.0.1:0").unwrap();
        let stun_address = stun.local_addr().unwrap();
        let responder = std::thread::spawn(move || {
            let mut request = [0; 2048];
            let (n, source) = stun.recv_from(&mut request).unwrap();
            stun.send_to(&mapped_reply(&request[..n], source), source)
                .unwrap();
            source
        });
        let socket = UdpSocket::bind("127.0.0.1:0").unwrap();
        let original = socket.local_addr().unwrap();
        let (candidates, resolved) = discover(
            &socket,
            &[format!("stun:{stun_address}")],
            Instant::now() + Duration::from_secs(1),
        );
        assert_eq!(responder.join().unwrap(), original);
        assert_eq!(candidates[0], original);
        let provider = build_node(socket, original.port(), candidates, resolved).unwrap();
        assert_eq!(provider.endpoint.local_addr().unwrap(), original);
        assert_eq!(provider.raw.local_addr().unwrap(), original);
        let client = loop_node();
        let impostor = loop_node();
        runtime().block_on(async {
            let outgoing =
                connect_node(client.clone(), &provider.descriptor, Duration::from_secs(2))
                    .await
                    .unwrap();
            let incoming = timeout(Duration::from_secs(1), async {
                provider.incoming.lock().await.recv().await
            })
            .await
            .unwrap()
            .unwrap();
            let payload = vec![0x5au8; 180000];
            let sent = payload.clone();
            let sender = outgoing.clone();
            let writing =
                tokio::spawn(
                    async move { sender.send.lock().await.write_all(&sent).await.unwrap() },
                );
            let mut received = vec![0; payload.len()];
            incoming
                .recv
                .lock()
                .await
                .read_exact(&mut received)
                .await
                .unwrap();
            writing.await.unwrap();
            assert_eq!(received, payload);
            incoming
                .send
                .lock()
                .await
                .write_all(b"confirmed")
                .await
                .unwrap();
            let mut ack = [0; 9];
            outgoing
                .recv
                .lock()
                .await
                .read_exact(&mut ack)
                .await
                .unwrap();
            assert_eq!(&ack, b"confirmed");
            let mut wrong: Descriptor = serde_json::from_str(&provider.descriptor).unwrap();
            let other: Descriptor = serde_json::from_str(&impostor.descriptor).unwrap();
            wrong.cert = other.cert;
            assert!(
                connect_node(
                    client.clone(),
                    &serde_json::to_string(&wrong).unwrap(),
                    Duration::from_millis(500)
                )
                .await
                .is_err()
            );
            let (a, b) = tokio::join!(
                finish_link(outgoing.clone(), Duration::from_secs(1)),
                finish_link(incoming.clone(), Duration::from_secs(1))
            );
            assert!(a.is_ok());
            assert!(b.is_ok());
            // Both directions end only after all queued data/FIN are acknowledged.
            let mut eof = [0; 1];
            assert!(
                outgoing
                    .recv
                    .lock()
                    .await
                    .read(&mut eof)
                    .await
                    .unwrap()
                    .is_none()
            );
            outgoing.close();
            incoming.close();
            let outgoing =
                connect_node(client.clone(), &provider.descriptor, Duration::from_secs(2))
                    .await
                    .unwrap();
            let incoming = timeout(Duration::from_secs(1), async {
                provider.incoming.lock().await.recv().await
            })
            .await
            .unwrap()
            .unwrap();
            let waiting = incoming.clone();
            let blocked = tokio::spawn(async move {
                let mut buffer = [0; 1];
                waiting.recv.lock().await.read(&mut buffer).await
            });
            outgoing.close();
            assert!(
                timeout(Duration::from_secs(1), blocked)
                    .await
                    .unwrap()
                    .unwrap()
                    .is_err()
            );
            incoming.close();
        });
        for node in [&provider, &client, &impostor] {
            stop(node)
        }
    }
    #[test]
    fn descriptor_bounds_and_numeric_addresses() {
        let node = loop_node();
        assert!(Descriptor::parse(&node.descriptor).is_ok());
        let mut descriptor: Descriptor = serde_json::from_str(&node.descriptor).unwrap();
        for candidate in [
            "example.com:123",
            "0.0.0.0:123",
            "224.0.0.1:123",
            "255.255.255.255:123",
            "127.0.0.1:0",
            "[::1]:123",
            "127.0.0.1:65536",
        ] {
            descriptor.candidates = vec![candidate.to_string()];
            assert!(Descriptor::parse(&serde_json::to_string(&descriptor).unwrap()).is_err());
        }
        descriptor.candidates = vec!["127.0.0.1:123".to_string(); 5];
        assert!(Descriptor::parse(&serde_json::to_string(&descriptor).unwrap()).is_err());
        assert!(Descriptor::parse(&"x".repeat(2049)).is_err());
        descriptor.candidates = vec!["127.0.0.1:123".to_string()];
        descriptor.cert = B64.encode(b"not a certificate");
        assert!(Descriptor::parse(&serde_json::to_string(&descriptor).unwrap()).is_err());
        stop(&node)
    }
    #[test]
    fn handles_close_cancels_streams_and_endpoint() {
        let provider = loop_node();
        let client = loop_node();
        let provider_id = register_node(provider.clone()).unwrap();
        let client_id = register_node(client.clone()).unwrap();
        runtime().block_on(async {
            let outgoing =
                connect_node(client.clone(), &provider.descriptor, Duration::from_secs(2))
                    .await
                    .unwrap();
            let incoming = timeout(Duration::from_secs(1), async {
                provider.incoming.lock().await.recv().await
            })
            .await
            .unwrap()
            .unwrap();
            let handle = register_link(incoming.clone()).unwrap();
            let outgoing_handle = register_link(outgoing.clone()).unwrap();
            assert!(link(handle).is_ok());
            let waiting = incoming.clone();
            let blocked = tokio::spawn(async move {
                let mut byte = [0];
                waiting.recv.lock().await.read(&mut byte).await
            });
            close_node(provider_id);
            assert!(node(provider_id).is_err());
            assert!(link(handle).is_err());
            assert!(
                timeout(Duration::from_secs(1), blocked)
                    .await
                    .unwrap()
                    .unwrap()
                    .is_err()
            );
            close_link(outgoing_handle);
            assert!(link(outgoing_handle).is_err());
        });
        close_node(client_id);
    }
    #[test]
    fn bounded_punch_has_same_source_port() {
        let node = loop_node();
        let receiver = UdpSocket::bind("127.0.0.1:0").unwrap();
        receiver
            .set_read_timeout(Some(Duration::from_secs(1)))
            .unwrap();
        let mut descriptor: Descriptor = serde_json::from_str(&node.descriptor).unwrap();
        descriptor.candidates = vec![receiver.local_addr().unwrap().to_string()];
        punch_node(
            &node,
            &serde_json::to_string(&descriptor).unwrap(),
            Duration::from_millis(1),
        )
        .unwrap();
        let mut packet = [0; 128];
        let (n, source) = receiver.recv_from(&mut packet).unwrap();
        assert_eq!(n, 20);
        assert_eq!(source, node.endpoint.local_addr().unwrap());
        assert_eq!(&packet[..2], &0x0011u16.to_be_bytes());
        stop(&node)
    }
    #[test]
    fn accept_capacity_is_a_retryable_null_and_listener_survives() {
        let provider = loop_node();
        let client = loop_node();
        runtime().block_on(async {
            let first = connect_node(client.clone(), &provider.descriptor, Duration::from_secs(1))
                .await
                .unwrap();
            let incoming = timeout(Duration::from_secs(1), async {
                provider.incoming.lock().await.recv().await
            })
            .await
            .unwrap()
            .unwrap();
            let handles: Vec<u64> = (0..64)
                .map(|_| register_link(incoming.clone()).unwrap())
                .collect();
            let next = connect_node(client.clone(), &provider.descriptor, Duration::from_secs(1))
                .await
                .unwrap();
            assert_eq!(
                accept_handle(provider.clone(), Duration::from_secs(1))
                    .await
                    .unwrap(),
                0
            );
            for handle in handles {
                close_link(handle);
            }
            let final_connection =
                connect_node(client.clone(), &provider.descriptor, Duration::from_secs(1))
                    .await
                    .unwrap();
            let handle = accept_handle(provider.clone(), Duration::from_secs(1))
                .await
                .unwrap();
            assert!(handle > 0);
            close_link(handle);
            for link in [first, next, final_connection] {
                link.close();
            }
        });
        stop(&provider);
        stop(&client);
    }
}
