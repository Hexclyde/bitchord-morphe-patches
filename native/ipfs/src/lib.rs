//! Bounded explicitly paired Bitswap transport for BitChord downloads.
//! No DHT, mDNS, bootstrap peers, gateways, public relays or automatic seeding.
use blockstore::Blockstore;
use cid::{Cid, CidGeneric};
use futures::StreamExt;
use libp2p::{
    Multiaddr, PeerId, SwarmBuilder, allow_block_list, connection_limits, identity, noise,
    swarm::{NetworkBehaviour, SwarmEvent},
    tcp, yamux,
};
use multihash_codetable::{Code, MultihashDigest};
use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};
use std::{
    collections::{HashMap, HashSet},
    fs,
    io::{Read, Write},
    path::{Path, PathBuf},
    sync::{
        Arc, Mutex,
        atomic::{AtomicBool, AtomicU64, Ordering},
        mpsc,
    },
    thread,
    time::{Duration, Instant},
};
use tokio::sync::mpsc as async_mpsc;

pub const BLOCK_BYTES: usize = 256 * 1024;
pub const MAX_FILE_BYTES: u64 = 256 * 1024 * 1024;
const STORE_BYTES: u64 = 512 * 1024 * 1024;
const STORE_BLOCKS: usize = 2048;
const MAX_PEERS: usize = 16;
type Result<T> = std::result::Result<T, String>;

fn err(e: impl std::fmt::Display) -> String {
    e.to_string()
}
pub fn raw_cid(data: &[u8]) -> Cid {
    Cid::new_v1(0x55, Code::Sha2_256.digest(data))
}
fn checked_cid(s: &str) -> Result<Cid> {
    if s.len() > 100 {
        return Err("CID too long".into());
    }
    let c: Cid = s.parse().map_err(err)?;
    if c.version() != cid::Version::V1
        || c.codec() != 0x55
        || c.hash().code() != 0x12
        || c.hash().size() != 32
        || c.to_string() != s
    {
        return Err("expected canonical CIDv1 raw SHA256".into());
    }
    Ok(c)
}
fn atomic_write(path: &Path, bytes: &[u8]) -> Result<()> {
    let tmp = path.with_extension("pending");
    let mut opts = fs::OpenOptions::new();
    opts.write(true).create_new(true);
    #[cfg(unix)]
    {
        use std::os::unix::fs::OpenOptionsExt;
        opts.mode(0o600);
    }
    let _ = fs::remove_file(&tmp);
    let mut file = opts.open(&tmp).map_err(err)?;
    file.write_all(bytes)
        .and_then(|_| file.sync_all())
        .map_err(err)?;
    fs::rename(&tmp, path).map_err(err)
}

struct Publication {
    cids: HashSet<String>,
    peers: HashSet<PeerId>,
}
struct StoreState {
    published: HashMap<String, Publication>,
    bytes: u64,
    blocks: usize,
}
struct Store {
    home: PathBuf,
    state: Mutex<StoreState>,
}
impl Store {
    fn open(home: &Path) -> Result<Self> {
        fs::create_dir_all(home).map_err(err)?;
        let mut files = Vec::new();
        for item in fs::read_dir(home).map_err(err)? {
            let item = item.map_err(err)?;
            let m = item.metadata().map_err(err)?;
            let name = item.file_name().to_string_lossy().into_owned();
            if m.is_file() && checked_cid(&name).is_ok() && m.len() <= BLOCK_BYTES as u64 {
                files.push((item.path(), m.len(), m.modified().ok()));
            } else if m.is_file() {
                fs::remove_file(item.path()).map_err(err)?;
            }
        }
        files.sort_by_key(|x| x.2);
        let mut bytes: u64 = files.iter().map(|x| x.1).sum();
        let mut blocks = files.len();
        for (path, len, _) in files {
            if bytes <= STORE_BYTES && blocks <= STORE_BLOCKS {
                break;
            }
            fs::remove_file(path).map_err(err)?;
            bytes -= len;
            blocks -= 1;
        }
        // Publication leases deliberately do not survive process restart. Cached data does.
        Ok(Self {
            home: home.into(),
            state: Mutex::new(StoreState {
                published: HashMap::new(),
                bytes,
                blocks,
            }),
        })
    }
    fn read_private(&self, cid: &Cid) -> Result<Option<Vec<u8>>> {
        let path = self.home.join(cid.to_string());
        let mut file = match fs::File::open(&path) {
            Ok(f) => f,
            Err(e) if e.kind() == std::io::ErrorKind::NotFound => return Ok(None),
            Err(e) => return Err(err(e)),
        };
        if file.metadata().map_err(err)?.len() > BLOCK_BYTES as u64 {
            return Err("oversized cached block".into());
        }
        let mut bytes = Vec::new();
        std::io::Read::by_ref(&mut file)
            .take(BLOCK_BYTES as u64 + 1)
            .read_to_end(&mut bytes)
            .map_err(err)?;
        if raw_cid(&bytes) != *cid {
            return Err("cached CID mismatch".into());
        }
        Ok(Some(bytes))
    }
    fn put_private(&self, cid: &Cid, data: &[u8]) -> Result<()> {
        if data.len() > BLOCK_BYTES || raw_cid(data) != *cid {
            return Err("invalid or oversized block".into());
        }
        let mut state = self.state.lock().map_err(err)?;
        let path = self.home.join(cid.to_string());
        if path.exists() {
            return Ok(());
        }
        if state.blocks >= STORE_BLOCKS || state.bytes + data.len() as u64 > STORE_BYTES {
            return Err("IPFS cache quota reached; clear unused downloads".into());
        }
        atomic_write(&path, data)?;
        state.blocks += 1;
        state.bytes += data.len() as u64;
        Ok(())
    }
    fn publish(&self, path: &Path, canceled: &AtomicBool) -> Result<String> {
        let mut file = fs::File::open(path).map_err(err)?;
        let len = file.metadata().map_err(err)?.len();
        if len > MAX_FILE_BYTES || !file.metadata().map_err(err)?.is_file() {
            return Err("expected a regular file of at most 256 MiB".into());
        }
        let mut chunks = Vec::new();
        let mut digest = Sha256::new();
        let mut count = 0u64;
        let mut buffer = vec![0; BLOCK_BYTES];
        loop {
            if canceled.load(Ordering::Acquire) {
                return Err("canceled".into());
            }
            let mut n = 0;
            while n < buffer.len() {
                let k = file.read(&mut buffer[n..]).map_err(err)?;
                if k == 0 {
                    break;
                }
                n += k;
            }
            if n == 0 {
                break;
            }
            count += n as u64;
            if count > MAX_FILE_BYTES {
                return Err("file grew beyond limit".into());
            }
            digest.update(&buffer[..n]);
            let cid = raw_cid(&buffer[..n]);
            self.put_private(&cid, &buffer[..n])?;
            chunks.push(Chunk {
                cid: cid.to_string(),
                size: n,
                sha256: hex(&Sha256::digest(&buffer[..n])),
            });
        }
        if count != len {
            return Err("source size changed during publication".into());
        }
        let manifest = Manifest {
            version: 1,
            block_size: BLOCK_BYTES,
            size: count,
            sha256: hex(&digest.finalize()),
            blocks: chunks,
        };
        let bytes = serde_json::to_vec(&manifest).map_err(err)?;
        let root = raw_cid(&bytes);
        self.put_private(&root, &bytes)?;
        let mut cids: HashSet<String> = manifest.blocks.iter().map(|x| x.cid.clone()).collect();
        cids.insert(root.to_string());
        let mut state = self.state.lock().map_err(err)?;
        if canceled.load(Ordering::Acquire) {
            return Err("canceled".into());
        }
        if state.published.len() >= 16 && !state.published.contains_key(&root.to_string()) {
            return Err("at most 16 explicit publications".into());
        }
        state.published.insert(
            root.to_string(),
            Publication {
                cids,
                peers: HashSet::new(),
            },
        );
        Ok(serde_json::json!({"manifestCid":root.to_string(),"manifestSha256":hex(&Sha256::digest(&bytes)),"manifestJson":String::from_utf8(bytes).map_err(err)?,"manifest":manifest}).to_string())
    }
    fn unpublish(&self, root: &str) -> Result<()> {
        checked_cid(root)?;
        self.state.lock().map_err(err)?.published.remove(root);
        Ok(())
    }
    fn grant(&self, root: &str, peers: HashSet<PeerId>) -> Result<()> {
        checked_cid(root)?;
        let mut state = self.state.lock().map_err(err)?;
        state
            .published
            .get_mut(root)
            .ok_or("unknown publication")?
            .peers = peers;
        Ok(())
    }
    fn revoke_peer(&self, peer: PeerId) {
        if let Ok(mut state) = self.state.lock() {
            for publication in state.published.values_mut() {
                publication.peers.remove(&peer);
            }
        }
    }
    fn authorized(&self, peer: PeerId, cid: &Cid) -> bool {
        self.state.lock().is_ok_and(|s| {
            s.published
                .values()
                .any(|p| p.peers.contains(&peer) && p.cids.contains(&cid.to_string()))
        })
    }
    fn clear_private(&self) -> Result<u64> {
        let mut state = self.state.lock().map_err(err)?;
        let mut freed = 0;
        for entry in fs::read_dir(&self.home).map_err(err)? {
            let entry = entry.map_err(err)?;
            let name = entry.file_name().to_string_lossy().into_owned();
            if state.published.values().any(|p| p.cids.contains(&name)) {
                continue;
            }
            let m = entry.metadata().map_err(err)?;
            if m.is_file() && checked_cid(&name).is_ok() {
                fs::remove_file(entry.path()).map_err(err)?;
                state.bytes = state.bytes.saturating_sub(m.len());
                state.blocks = state.blocks.saturating_sub(1);
                freed += m.len();
            }
        }
        Ok(freed)
    }
}
fn hex(bytes: &[u8]) -> String {
    bytes.iter().map(|b| format!("{b:02x}")).collect()
}
#[derive(Serialize, Deserialize, Debug)]
pub struct Chunk {
    pub cid: String,
    pub size: usize,
    pub sha256: String,
}
#[derive(Serialize, Deserialize, Debug)]
pub struct Manifest {
    pub version: u32,
    #[serde(rename = "blockSize")]
    pub block_size: usize,
    pub size: u64,
    pub sha256: String,
    pub blocks: Vec<Chunk>,
}
impl Blockstore for Store {
    async fn get<const S: usize>(
        &self,
        cid: &CidGeneric<S>,
    ) -> blockstore::Result<Option<Vec<u8>>> {
        let name = cid.to_string();
        let published = self
            .state
            .lock()
            .map_err(|e| blockstore::Error::StoredDataError(e.to_string()))?
            .published
            .values()
            .any(|publication| publication.cids.contains(&name));
        if !published {
            return Ok(None);
        }
        let c = checked_cid(&name).map_err(blockstore::Error::StoredDataError)?;
        self.read_private(&c)
            .map_err(blockstore::Error::StoredDataError)
    }
    // This is a read-only publication view. Beetswap's successful automatic put
    // also forwards the received bytes straight to waiting peers, bypassing get.
    // Rejecting that automatic put suppresses its new-block announcement. The
    // actor separately verifies and commits the response to the PRIVATE cache.
    async fn put_keyed<const S: usize>(
        &self,
        _cid: &CidGeneric<S>,
        _data: &[u8],
    ) -> blockstore::Result<()> {
        Err(blockstore::Error::ExecutorError(
            "automatic block publication disabled".into(),
        ))
    }
    async fn remove<const S: usize>(&self, _cid: &CidGeneric<S>) -> blockstore::Result<()> {
        Ok(())
    }
    async fn close(self) -> blockstore::Result<()> {
        Ok(())
    }
}

#[derive(NetworkBehaviour)]
struct Behaviour {
    allowed: allow_block_list::Behaviour<allow_block_list::AllowedPeers>,
    limits: connection_limits::Behaviour,
    bitswap: beetswap::Behaviour<64, Store>,
}
enum Command {
    Peers(HashSet<PeerId>, mpsc::Sender<Result<()>>),
    Connect(
        PeerId,
        Vec<Multiaddr>,
        Instant,
        u64,
        mpsc::Sender<Result<()>>,
    ),
    Fetch(PeerId, Cid, Instant, u64, mpsc::Sender<Result<Vec<u8>>>),
}
struct BlockQuery {
    cid: Cid,
    deadline: Instant,
    epoch: u64,
    reply: mpsc::Sender<Result<Vec<u8>>>,
}
pub struct Node {
    tx: async_mpsc::Sender<Command>,
    store: Arc<Store>,
    info: Arc<Mutex<serde_json::Value>>,
    canceled: Arc<AtomicBool>,
    epoch: Arc<AtomicU64>,
    stopped: Arc<AtomicBool>,
    thread: Mutex<Option<thread::JoinHandle<()>>>,
    publish_lock: Mutex<()>,
}
fn parse_peers(json: &str) -> Result<HashSet<PeerId>> {
    if json.len() > 4096 {
        return Err("peer list too long".into());
    }
    let peers: Vec<String> = serde_json::from_str(json).map_err(err)?;
    if peers.len() > MAX_PEERS {
        return Err("at most 16 paired peers".into());
    }
    peers.into_iter().map(|p| p.parse().map_err(err)).collect()
}
fn timeout(ms: i32) -> Result<Duration> {
    if !(100..=120_000).contains(&ms) {
        return Err("timeout must be 100..120000 ms".into());
    }
    Ok(Duration::from_millis(ms as u64))
}
impl Node {
    pub fn create(home: &Path, peers_json: &str, loopback: bool) -> Result<Arc<Self>> {
        let peers = parse_peers(peers_json)?;
        if home.as_os_str().len() > 4096 {
            return Err("home path too long".into());
        }
        fs::create_dir_all(home).map_err(err)?;
        let identity_path = home.join("identity.key");
        let key = if identity_path.exists() {
            if fs::metadata(&identity_path).map_err(err)?.len() > 256 {
                return Err("identity too large".into());
            }
            let bytes = fs::read(&identity_path).map_err(err)?;
            identity::Keypair::from_protobuf_encoding(&bytes).map_err(err)?
        } else {
            let key = identity::Keypair::generate_ed25519();
            atomic_write(&identity_path, &key.to_protobuf_encoding().map_err(err)?)?;
            key
        };
        let peer_id = key.public().to_peer_id();
        let store = Arc::new(Store::open(&home.join("blocks"))?);
        let info = Arc::new(Mutex::new(
            serde_json::json!({"peerId":peer_id.to_string(),"listenAddresses":[],"capabilities":{"bitswap":true,"tcpNoise":true,"dht":false,"mdns":false,"relay":false,"dcutr":false,"quic":false,"automaticSeeding":false,"interfaceEnumeration":true},"blockBytes":BLOCK_BYTES,"maxFileBytes":MAX_FILE_BYTES}),
        ));
        let (tx, rx) = async_mpsc::channel(32);
        let canceled = Arc::new(AtomicBool::new(false));
        let epoch = Arc::new(AtomicU64::new(0));
        let stopped = Arc::new(AtomicBool::new(false));
        let node = Arc::new(Self {
            tx,
            store: store.clone(),
            info: info.clone(),
            canceled: canceled.clone(),
            epoch: epoch.clone(),
            stopped: stopped.clone(),
            thread: Mutex::new(None),
            publish_lock: Mutex::new(()),
        });
        let (ready_tx, ready_rx) = mpsc::channel();
        let join = thread::Builder::new()
            .name("bitchord-ipfs".into())
            .spawn(move || {
                let runtime = match tokio::runtime::Builder::new_current_thread()
                    .enable_all()
                    .build()
                {
                    Ok(r) => r,
                    Err(e) => {
                        let _ = ready_tx.send(Err(err(e)));
                        return;
                    }
                };
                runtime.block_on(async move {
                    if let Err(e) = run(
                        key,
                        peers,
                        store,
                        info,
                        rx,
                        stopped,
                        epoch,
                        loopback,
                        ready_tx.clone(),
                    )
                    .await
                    {
                        let _ = ready_tx.send(Err(e));
                    }
                });
            })
            .map_err(err)?;
        *node.thread.lock().map_err(err)? = Some(join);
        ready_rx
            .recv_timeout(Duration::from_secs(10))
            .map_err(|e| format!("IPFS startup wait: {e}"))?
            .map_err(|e| format!("IPFS startup runtime: {e}"))?;
        Ok(node)
    }
    fn request<T>(
        &self,
        command: impl FnOnce(mpsc::Sender<Result<T>>) -> Command,
        duration: Duration,
    ) -> Result<T> {
        if self.stopped.load(Ordering::Acquire) {
            return Err("node closed".into());
        }
        let (tx, rx) = mpsc::channel();
        self.tx.try_send(command(tx)).map_err(err)?;
        rx.recv_timeout(duration + Duration::from_secs(1))
            .map_err(err)?
    }
    pub fn info(&self) -> Result<String> {
        Ok(self.info.lock().map_err(err)?.to_string())
    }
    pub fn set_peers(&self, json: &str) -> Result<()> {
        let peers = parse_peers(json)?;
        self.request(|tx| Command::Peers(peers, tx), Duration::from_secs(5))
    }
    pub fn connect(&self, peer: &str, addresses: &str, timeout_ms: i32) -> Result<()> {
        let peer: PeerId = peer.parse().map_err(err)?;
        let duration = timeout(timeout_ms)?;
        if addresses.len() > 8192 {
            return Err("address list too long".into());
        }
        let addresses: Vec<String> = serde_json::from_str(addresses).map_err(err)?;
        if addresses.is_empty() || addresses.len() > 8 {
            return Err("expected 1..8 explicit IP/TCP addresses".into());
        }
        let mut parsed = Vec::new();
        for s in addresses {
            let mut a: Multiaddr = s.parse().map_err(err)?;
            // Only IP + TCP, optionally a matching PeerID. No DNS, proxy, relay or discovery.
            let protocols: Vec<_> = a.iter().collect();
            if protocols.len() < 2
                || protocols.len() > 3
                || !matches!(
                    protocols[0],
                    libp2p::multiaddr::Protocol::Ip4(_) | libp2p::multiaddr::Protocol::Ip6(_)
                )
                || !matches!(protocols[1], libp2p::multiaddr::Protocol::Tcp(p) if p != 0)
            {
                return Err("only explicit IP/TCP endpoints supported".into());
            }
            if protocols.len() == 3 {
                if protocols[2] != libp2p::multiaddr::Protocol::P2p(peer) {
                    return Err("endpoint PeerID mismatch".into());
                }
                a.pop();
            }
            parsed.push(a);
        }
        self.request(
            |tx| {
                Command::Connect(
                    peer,
                    parsed,
                    Instant::now() + duration,
                    self.epoch.load(Ordering::Acquire),
                    tx,
                )
            },
            duration,
        )
    }
    pub fn publish(&self, path: &Path) -> Result<String> {
        let _guard = self
            .publish_lock
            .try_lock()
            .map_err(|_| "publication already in progress")?;
        if self.stopped.load(Ordering::Acquire) {
            return Err("node closed".into());
        }
        self.canceled.store(false, Ordering::Release);
        self.store.publish(path, &self.canceled)
    }
    pub fn unpublish(&self, cid: &str) -> Result<()> {
        self.store.unpublish(cid)
    }
    pub fn grant_publication(&self, cid: &str, peers: &str) -> Result<()> {
        self.store.grant(cid, parse_peers(peers)?)
    }
    pub fn clear_private(&self) -> Result<u64> {
        let _guard = self
            .publish_lock
            .try_lock()
            .map_err(|_| "publication in progress")?;
        self.store.clear_private()
    }
    pub fn fetch_block(&self, peer: &str, cid: &str, timeout_ms: i32) -> Result<Vec<u8>> {
        let peer = peer.parse().map_err(err)?;
        let cid = checked_cid(cid)?;
        let duration = timeout(timeout_ms)?;
        // Even a cache hit requires an authorized peer; actor enforces this before lookup.
        self.request(
            |tx| {
                Command::Fetch(
                    peer,
                    cid,
                    Instant::now() + duration,
                    self.epoch.load(Ordering::Acquire),
                    tx,
                )
            },
            duration,
        )
    }
    pub fn cancel(&self) {
        self.canceled.store(true, Ordering::Release);
        self.epoch.fetch_add(1, Ordering::AcqRel);
    }
    pub fn close(&self) {
        self.cancel();
        self.stopped.store(true, Ordering::Release);
        if let Ok(mut guard) = self.thread.lock() {
            if let Some(join) = guard.take() {
                let _ = join.join();
            }
        }
    }
}
impl Drop for Node {
    fn drop(&mut self) {
        self.close();
    }
}

async fn run(
    key: identity::Keypair,
    peers: HashSet<PeerId>,
    store: Arc<Store>,
    info: Arc<Mutex<serde_json::Value>>,
    mut rx: async_mpsc::Receiver<Command>,
    stopped: Arc<AtomicBool>,
    epoch: Arc<AtomicU64>,
    loopback: bool,
    ready: mpsc::Sender<Result<()>>,
) -> Result<()> {
    let limits = connection_limits::ConnectionLimits::default()
        .with_max_pending_incoming(Some(8))
        .with_max_pending_outgoing(Some(8))
        .with_max_established(Some(8))
        .with_max_established_per_peer(Some(1));
    let mut allowed = allow_block_list::Behaviour::default();
    for peer in peers {
        allowed.allow_peer(peer);
    }
    let authorization_store = store.clone();
    let mut bitswap = beetswap::Behaviour::new(store.clone());
    bitswap.set_server_authorization(beetswap::ServerAuthorization(Arc::new(move |peer, cid| {
        authorization_store.authorized(peer, cid)
    })));
    let mut swarm = SwarmBuilder::with_existing_identity(key)
        .with_tokio()
        .with_tcp(
            tcp::Config::default().nodelay(true),
            noise::Config::new,
            yamux::Config::default,
        )
        .map_err(|e| format!("swarm setup: {e:?}"))?
        .with_behaviour(|_| Behaviour {
            allowed,
            limits: connection_limits::Behaviour::new(limits),
            bitswap,
        })
        .map_err(|e| format!("swarm setup: {e:?}"))?
        .with_swarm_config(|c| {
            c.with_idle_connection_timeout(Duration::from_secs(120))
                .with_max_negotiating_inbound_streams(4)
                .with_per_connection_event_buffer_size(4)
                .with_notify_handler_buffer_size(std::num::NonZeroUsize::new(4).unwrap())
        })
        .build();
    // Wildcard libp2p TCP listeners subscribe to a route watcher. Explicit
    // interface snapshots work without privileged Netlink subscriptions.
    let addresses = if loopback {
        vec![std::net::Ipv4Addr::LOCALHOST]
    } else {
        let mut addresses = vec![std::net::Ipv4Addr::LOCALHOST];
        let interfaces = match if_addrs::get_if_addrs() {
            Ok(interfaces) => interfaces,
            Err(e) => {
                if let Ok(mut value) = info.lock() {
                    value["listenWarning"] = format!("Only loopback inbound binding is available; interface enumeration failed: {e}").into();
                    value["capabilities"]["interfaceEnumeration"] = false.into();
                }
                Vec::new()
            }
        };
        for interface in interfaces {
            if let std::net::IpAddr::V4(ip) = interface.ip() {
                if !ip.is_unspecified()
                    && !ip.is_multicast()
                    && !addresses.contains(&ip)
                    && addresses.len() < 8
                {
                    addresses.push(ip);
                }
            }
        }
        addresses
    };
    let expected_listeners = addresses.len();
    for ip in addresses {
        swarm
            .listen_on(
                format!("/ip4/{ip}/tcp/0")
                    .parse()
                    .map_err(|e| format!("listen address: {e:?}"))?,
            )
            .map_err(|e| format!("TCP listener: {e:?}"))?;
    }
    let mut pending: HashMap<beetswap::QueryId, BlockQuery> = HashMap::new();
    let mut connecting: HashMap<PeerId, (Instant, u64, mpsc::Sender<Result<()>>)> = HashMap::new();
    let mut ticker = tokio::time::interval(Duration::from_millis(50));
    let mut announced = false;
    let mut listen_count = 0;
    while !stopped.load(Ordering::Acquire) {
        tokio::select! {
            _ = ticker.tick() => {
                let expired: Vec<_> = pending.iter().filter(|(_,p)| p.deadline <= Instant::now() || p.epoch != epoch.load(Ordering::Acquire)).map(|(q,_)| *q).collect();
                for q in expired { swarm.behaviour_mut().bitswap.cancel(q); if let Some(p) = pending.remove(&q) { let _ = p.reply.send(Err(if p.epoch != epoch.load(Ordering::Acquire) { "canceled" } else { "Bitswap block timed out" }.into())); } }
                let expired: Vec<_> = connecting.iter().filter(|(_,p)| p.0 <= Instant::now() || p.1 != epoch.load(Ordering::Acquire)).map(|(p,_)| *p).collect();
                for p in expired { if let Some((_,started,reply)) = connecting.remove(&p) { let _ = swarm.disconnect_peer_id(p); let _ = reply.send(Err(if started != epoch.load(Ordering::Acquire) {"canceled"} else {"peer connection timed out"}.into())); } }
            },
            command = rx.recv() => match command {
                None => break,
                Some(Command::Peers(peers, reply)) => { let old: Vec<_> = swarm.behaviour().allowed.allowed_peers().iter().copied().collect(); for p in old { if !peers.contains(&p) { store.revoke_peer(p); swarm.behaviour_mut().allowed.disallow_peer(p); } } for p in peers { swarm.behaviour_mut().allowed.allow_peer(p); } let _ = reply.send(Ok(())); },
                Some(Command::Connect(peer, addresses, deadline, started, reply)) => {
                    if started != epoch.load(Ordering::Acquire) { let _ = reply.send(Err("canceled".into())); }
                    else if !swarm.behaviour().allowed.allowed_peers().contains(&peer) { let _ = reply.send(Err("unpaired peer".into())); }
                    else if swarm.is_connected(&peer) { let _ = reply.send(Ok(())); }
                    else if connecting.contains_key(&peer) { let _ = reply.send(Err("connection already pending".into())); }
                    else { let opts = libp2p::swarm::dial_opts::DialOpts::peer_id(peer).addresses(addresses).build(); match swarm.dial(opts) { Ok(()) => { connecting.insert(peer, (deadline,started,reply)); }, Err(e) => { let _ = reply.send(Err(err(e))); } } }
                },
                Some(Command::Fetch(peer, cid, deadline, started, reply)) => {
                    if started != epoch.load(Ordering::Acquire) { let _ = reply.send(Err("canceled".into())); }
                    else if !swarm.behaviour().allowed.allowed_peers().contains(&peer) { let _ = reply.send(Err("unpaired peer".into())); }
                    else if !pending.is_empty() { let _ = reply.send(Err("one block query at a time".into())); }
                    else { match store.read_private(&cid) {
                        Ok(Some(data)) => { let _ = reply.send(Ok(data)); },
                        Err(e) => { let _ = reply.send(Err(e)); },
                        Ok(None) if !swarm.is_connected(&peer) => { let _ = reply.send(Err("peer is not connected".into())); },
                        Ok(None) => { let q = swarm.behaviour_mut().bitswap.get(&cid); pending.insert(q, BlockQuery { cid, deadline, epoch: started, reply }); }
                    } }
                },
            },
            event = swarm.select_next_some() => match event {
                SwarmEvent::NewListenAddr { address, .. } => { if let Ok(mut i) = info.lock() { let a = address.with(libp2p::multiaddr::Protocol::P2p(*swarm.local_peer_id())).to_string(); if let Some(addresses) = i["listenAddresses"].as_array_mut() { if addresses.len() < 16 && !addresses.contains(&serde_json::Value::String(a.clone())) { addresses.push(a.into()); } } } listen_count += 1; if !announced && listen_count >= expected_listeners { let _ = ready.send(Ok(())); announced = true; } },
                SwarmEvent::ConnectionEstablished { peer_id, .. } => { if let Some((deadline,started,reply)) = connecting.remove(&peer_id) { let result = if started != epoch.load(Ordering::Acquire) { Err("canceled".into()) } else if deadline <= Instant::now() { Err("peer connection timed out".into()) } else { Ok(()) }; let _ = reply.send(result); } },
                SwarmEvent::OutgoingConnectionError { peer_id: Some(peer), error, .. } => { if let Some((_,_,reply)) = connecting.remove(&peer) { let _ = reply.send(Err(err(error))); } },
                SwarmEvent::Behaviour(BehaviourEvent::Bitswap(beetswap::Event::GetQueryResponse { query_id, data })) => { if let Some(p) = pending.remove(&query_id) { let result = if p.epoch != epoch.load(Ordering::Acquire) { Err("canceled".into()) } else if p.deadline <= Instant::now() { Err("Bitswap block timed out".into()) } else if data.len() > BLOCK_BYTES || raw_cid(&data) != p.cid { Err("provider block CID mismatch".into()) } else { store.put_private(&p.cid, &data).map(|_| data) }; let _ = p.reply.send(result); } },
                SwarmEvent::Behaviour(BehaviourEvent::Bitswap(beetswap::Event::GetQueryError { query_id, error })) => { if let Some(p) = pending.remove(&query_id) { let _ = p.reply.send(Err(err(error))); } },
                _ => {},
            }
        }
    }
    for (q, p) in pending {
        swarm.behaviour_mut().bitswap.cancel(q);
        let _ = p.reply.send(Err("node closed".into()));
    }
    for (_, (_, _, reply)) in connecting {
        let _ = reply.send(Err("node closed".into()));
    }
    Ok(())
}

mod jni_bridge;
