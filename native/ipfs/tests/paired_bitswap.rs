use bitchord_ipfs::{BLOCK_BYTES, Node, raw_cid};
use serde_json::{Value, json};
use std::{
    fs,
    path::PathBuf,
    sync::atomic::{AtomicU64, Ordering},
    time::{Duration, Instant},
};

static NEXT: AtomicU64 = AtomicU64::new(1);
struct TestHome(PathBuf);
impl TestHome {
    fn new() -> Self {
        let p = std::env::temp_dir().join(format!(
            "bitchord-ipfs-test-{}-{}",
            std::process::id(),
            NEXT.fetch_add(1, Ordering::Relaxed)
        ));
        fs::create_dir_all(&p).unwrap();
        Self(p)
    }
}
impl Drop for TestHome {
    fn drop(&mut self) {
        let _ = fs::remove_dir_all(&self.0);
    }
}
fn info(n: &Node) -> Value {
    serde_json::from_str(&n.info().unwrap()).unwrap()
}
fn peer(n: &Node) -> String {
    info(n)["peerId"].as_str().unwrap().into()
}
fn pair(a: &Node, b: &Node) {
    a.set_peers(&json!([peer(b)]).to_string()).unwrap();
    b.set_peers(&json!([peer(a)]).to_string()).unwrap();
}
fn connect(a: &Node, b: &Node) {
    a.connect(&peer(b), &info(b)["listenAddresses"].to_string(), 5000)
        .unwrap();
}

#[test]
fn actual_two_node_bitswap_manifest_and_multiblock_download() {
    let ha = TestHome::new();
    let hb = TestHome::new();
    let a = Node::create(&ha.0, "[]", true).unwrap();
    let b = Node::create(&hb.0, "[]", true).unwrap();
    pair(&a, &b);
    let bytes: Vec<u8> = (0..BLOCK_BYTES * 2 + 73)
        .map(|i| ((i * 31 + i / 71) % 251) as u8)
        .collect();
    let path = ha.0.join("selected.flac");
    fs::write(&path, &bytes).unwrap();
    let publication: Value = serde_json::from_str(&a.publish(&path).unwrap()).unwrap();
    a.grant_publication(
        publication["manifestCid"].as_str().unwrap(),
        &json!([peer(&b)]).to_string(),
    )
    .unwrap();
    connect(&b, &a);
    let manifest = b
        .fetch_block(
            &peer(&a),
            publication["manifestCid"].as_str().unwrap(),
            5000,
        )
        .unwrap();
    assert_eq!(
        manifest,
        publication["manifestJson"].as_str().unwrap().as_bytes()
    );
    let mut result = Vec::new();
    for block in publication["manifest"]["blocks"].as_array().unwrap() {
        let cid = block["cid"].as_str().unwrap();
        let data = b.fetch_block(&peer(&a), cid, 5000).unwrap();
        assert_eq!(raw_cid(&data).to_string(), cid);
        assert_eq!(data.len(), block["size"].as_u64().unwrap() as usize);
        result.extend(data);
    }
    assert_eq!(bytes, result);
    let id = peer(&a);
    a.close();
    b.close();
    let restarted = Node::create(&ha.0, "[]", true).unwrap();
    assert_eq!(peer(&restarted), id);
    restarted.close();
}

#[test]
fn fetched_blocks_are_private_unpublish_revokes_and_unpaired_peer_is_rejected() {
    let ha = TestHome::new();
    let hb = TestHome::new();
    let hc = TestHome::new();
    let a = Node::create(&ha.0, "[]", true).unwrap();
    let b = Node::create(&hb.0, "[]", true).unwrap();
    let c = Node::create(&hc.0, "[]", true).unwrap();
    pair(&a, &b);
    let source = ha.0.join("selected");
    fs::write(&source, b"only explicitly published").unwrap();
    let publication: Value = serde_json::from_str(&a.publish(&source).unwrap()).unwrap();
    let root = publication["manifestCid"].as_str().unwrap();
    let cid = publication["manifest"]["blocks"][0]["cid"]
        .as_str()
        .unwrap();
    a.grant_publication(root, &json!([peer(&b)]).to_string())
        .unwrap();
    connect(&b, &a);
    assert_eq!(
        b.fetch_block(&peer(&a), cid, 3000).unwrap(),
        b"only explicitly published"
    );
    c.set_peers(&json!([peer(&a), peer(&b)]).to_string())
        .unwrap();
    // Noise may finish before the remote allowlist closes the stream; no block
    // protocol response may escape that inbound authorization boundary.
    let _ = c.connect(&peer(&a), &info(&a)["listenAddresses"].to_string(), 1000);
    assert!(
        c.fetch_block(&peer(&a), cid, 300).is_err(),
        "unpaired peer must not retrieve published content"
    );
    // Disconnect original publisher before requesting B, so a want cannot reach A.
    b.set_peers(&json!([peer(&c)]).to_string()).unwrap();
    connect(&c, &b);
    assert!(
        c.fetch_block(&peer(&b), cid, 300).is_err(),
        "received blocks must never be automatically served"
    );
    a.unpublish(root).unwrap();
    pair(&a, &c);
    connect(&c, &a);
    assert!(
        c.fetch_block(&peer(&a), cid, 300).is_err(),
        "revoked publication must not serve cached bytes"
    );
    a.close();
    b.close();
    c.close();
}

#[test]
fn cancellation_wrong_peer_and_noncanonical_cid_are_bounded() {
    let ha = TestHome::new();
    let hb = TestHome::new();
    let a = Node::create(&ha.0, "[]", true).unwrap();
    let b = Node::create(&hb.0, "[]", true).unwrap();
    pair(&a, &b);
    connect(&b, &a);
    assert!(b.fetch_block(&peer(&a), "bafynonsense", 1000).is_err());
    let missing = raw_cid(b"not published anywhere").to_string();
    let b2 = b.clone();
    let provider = peer(&a);
    let start = Instant::now();
    let query = std::thread::spawn(move || b2.fetch_block(&provider, &missing, 10000));
    std::thread::sleep(Duration::from_millis(100));
    b.cancel();
    assert!(query.join().unwrap().is_err());
    assert!(start.elapsed() < Duration::from_secs(2));
    let forged = libp2p::identity::Keypair::generate_ed25519()
        .public()
        .to_peer_id()
        .to_string();
    b.set_peers(&json!([forged]).to_string()).unwrap();
    assert!(
        b.connect(&forged, &info(&a)["listenAddresses"].to_string(), 300)
            .is_err()
    );
    a.close();
    b.close();
}

#[test]
fn pending_want_is_not_fulfilled_when_private_download_arrives() {
    let ha = TestHome::new();
    let hb = TestHome::new();
    let hc = TestHome::new();
    let a = Node::create(&ha.0, "[]", true).unwrap();
    let b = Node::create(&hb.0, "[]", true).unwrap();
    let c = Node::create(&hc.0, "[]", true).unwrap();
    a.set_peers(&json!([peer(&b)]).to_string()).unwrap();
    b.set_peers(&json!([peer(&a), peer(&c)]).to_string())
        .unwrap();
    c.set_peers(&json!([peer(&b)]).to_string()).unwrap();
    let path = ha.0.join("selected");
    fs::write(&path, b"private cache even with a pending subscriber").unwrap();
    let publication: Value = serde_json::from_str(&a.publish(&path).unwrap()).unwrap();
    let cid = publication["manifest"]["blocks"][0]["cid"]
        .as_str()
        .unwrap()
        .to_string();
    a.grant_publication(
        publication["manifestCid"].as_str().unwrap(),
        &json!([peer(&b)]).to_string(),
    )
    .unwrap();
    connect(&c, &b);
    connect(&b, &a);
    let c2 = c.clone();
    let bpeer = peer(&b);
    let cid2 = cid.clone();
    let waiting = std::thread::spawn(move || c2.fetch_block(&bpeer, &cid2, 1000));
    std::thread::sleep(Duration::from_millis(100));
    assert_eq!(
        b.fetch_block(&peer(&a), &cid, 3000).unwrap(),
        b"private cache even with a pending subscriber"
    );
    assert!(
        waiting.join().unwrap().is_err(),
        "Beetswap automatic new-block forwarding must be disabled"
    );
    a.close();
    b.close();
    c.close();
}

#[test]
fn per_file_grants_and_private_cache_cleanup_are_enforced() {
    let ha = TestHome::new();
    let hb = TestHome::new();
    let hc = TestHome::new();
    let a = Node::create(&ha.0, "[]", true).unwrap();
    let b = Node::create(&hb.0, "[]", true).unwrap();
    let c = Node::create(&hc.0, "[]", true).unwrap();
    a.set_peers(&json!([peer(&b), peer(&c)]).to_string())
        .unwrap();
    b.set_peers(&json!([peer(&a)]).to_string()).unwrap();
    c.set_peers(&json!([peer(&a)]).to_string()).unwrap();
    let path = ha.0.join("selected");
    fs::write(&path, b"only B is granted this file").unwrap();
    let publication: Value = serde_json::from_str(&a.publish(&path).unwrap()).unwrap();
    let cid = publication["manifest"]["blocks"][0]["cid"]
        .as_str()
        .unwrap();
    let root = publication["manifestCid"].as_str().unwrap();
    a.grant_publication(root, &json!([peer(&b)]).to_string())
        .unwrap();
    connect(&b, &a);
    connect(&c, &a);
    assert!(
        c.fetch_block(&peer(&a), cid, 300).is_err(),
        "globally paired C must not read B's file"
    );
    assert_eq!(
        b.fetch_block(&peer(&a), cid, 3000).unwrap(),
        b"only B is granted this file"
    );
    assert_eq!(
        a.clear_private().unwrap(),
        0,
        "cleanup must retain active publication"
    );
    assert_eq!(
        b.clear_private().unwrap(),
        b"only B is granted this file".len() as u64,
        "private download may be reclaimed"
    );
    assert_eq!(
        b.fetch_block(&peer(&a), cid, 3000).unwrap(),
        b"only B is granted this file"
    );
    a.unpublish(root).unwrap();
    assert!(a.clear_private().unwrap() > b"only B is granted this file".len() as u64);
    a.close();
    b.close();
    c.close();
}

#[test]
fn corrupted_published_block_is_rejected_before_transfer() {
    let ha = TestHome::new();
    let hb = TestHome::new();
    let a = Node::create(&ha.0, "[]", true).unwrap();
    let b = Node::create(&hb.0, "[]", true).unwrap();
    pair(&a, &b);
    let path = ha.0.join("selected");
    fs::write(&path, b"original bytes").unwrap();
    let publication: Value = serde_json::from_str(&a.publish(&path).unwrap()).unwrap();
    let cid = publication["manifest"]["blocks"][0]["cid"]
        .as_str()
        .unwrap();
    a.grant_publication(
        publication["manifestCid"].as_str().unwrap(),
        &json!([peer(&b)]).to_string(),
    )
    .unwrap();
    fs::write(ha.0.join("blocks").join(cid), b"corrupt bytes!").unwrap();
    connect(&b, &a);
    assert!(b.fetch_block(&peer(&a), cid, 300).is_err());
    assert!(!hb.0.join("blocks").join(cid).exists());
    a.close();
    b.close();
}

#[test]
fn cancel_interrupts_a_local_stalled_noise_handshake() {
    use std::net::TcpListener;
    use std::sync::mpsc;
    let h = TestHome::new();
    let node = Node::create(&h.0, "[]", true).unwrap();
    let provider = libp2p::identity::Keypair::generate_ed25519()
        .public()
        .to_peer_id()
        .to_string();
    node.set_peers(&json!([provider]).to_string()).unwrap();
    let listener = TcpListener::bind("127.0.0.1:0").unwrap();
    let endpoint = format!(
        "/ip4/127.0.0.1/tcp/{}",
        listener.local_addr().unwrap().port()
    );
    let (accepted_tx, accepted_rx) = mpsc::channel();
    let (release_tx, release_rx) = mpsc::channel();
    let server = std::thread::spawn(move || {
        let (_stream, _) = listener.accept().unwrap();
        accepted_tx.send(()).unwrap();
        let _ = release_rx.recv_timeout(Duration::from_secs(5));
    });
    let n2 = node.clone();
    let start = Instant::now();
    let connect =
        std::thread::spawn(move || n2.connect(&provider, &json!([endpoint]).to_string(), 10000));
    accepted_rx.recv_timeout(Duration::from_secs(2)).unwrap();
    node.cancel();
    assert!(connect.join().unwrap().is_err());
    assert!(start.elapsed() < Duration::from_secs(2));
    let _ = release_tx.send(());
    server.join().unwrap();
    node.close();
}
