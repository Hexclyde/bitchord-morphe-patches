//! Opt-in, signed public provider advertisements. This module discovers identities;
//! it never pairs a peer, exposes an endpoint, or initiates a transfer.

use crate::{Result, input, invoke, io, java_string, output, state};
use jni::JNIEnv;
use jni::objects::{JClass, JString};
use jni::sys::{jboolean, jint, jlong, jstring};
use nostr_sdk::prelude::limits::{RelayEventLimits, RelayMessageLimits};
use nostr_sdk::prelude::*;
use serde_json::{Value, json};
use std::collections::{HashMap, HashSet, VecDeque};
use std::sync::atomic::{AtomicBool, AtomicI64, AtomicUsize, Ordering};
use std::sync::{Arc, Condvar, Mutex, OnceLock};
use std::thread::JoinHandle;
use std::time::{Duration, Instant};
use tokio::sync::watch;

const PROTOCOL: &str = "app.bitchord.providers/1";
// Replaceable kinds retain one latest ad per identity, allowing a cold read to
// find providers immediately instead of waiting for their next live renewal.
const KIND: Kind = Kind::Custom(11120);
const TTL: u64 = 300;
const FUTURE_SKEW: u64 = 30;
const MAX_EVENT: usize = 2048;
const MAX_BATCH: usize = 128;
const MAX_INBOX: usize = 128;
const MAX_SEEN: usize = 512;
const MAX_VALIDATIONS: usize = 1024;
const READ_BUDGET: Duration = Duration::from_secs(10);
const RENEW_INTERVAL: Duration = Duration::from_secs(60);
static NEXT: AtomicI64 = AtomicI64::new(1);
static SESSIONS: OnceLock<Mutex<HashMap<i64, Arc<Session>>>> = OnceLock::new();

struct Session {
    closed: AtomicBool,
    finished: AtomicBool,
    read_complete: AtomicBool,
    advertise: bool,
    configured: usize,
    accepted: AtomicUsize,
    rejected: AtomicUsize,
    stop: watch::Sender<bool>,
    worker: Mutex<Option<JoinHandle<()>>>,
    inbox: Mutex<VecDeque<Value>>,
    available: Condvar,
    snapshot: Mutex<Value>,
}

fn sessions() -> &'static Mutex<HashMap<i64, Arc<Session>>> {
    SESSIONS.get_or_init(|| Mutex::new(HashMap::new()))
}

fn get(handle: i64) -> Result<Arc<Session>> {
    sessions()
        .lock()
        .map_err(|_| state("Discovery registry unavailable"))?
        .get(&handle)
        .cloned()
        .ok_or_else(|| state("Discovery session is closed"))
}

fn snapshot(session: &Session, status: &str, connected: usize) {
    if let Ok(mut value) = session.snapshot.lock() {
        *value = json!({"state":status, "connected_relays":connected});
    }
}

fn exact_keys(value: &Value, expected: &[&str]) -> bool {
    value.as_object().is_some_and(|map| {
        map.len() == expected.len() && expected.iter().all(|key| map.contains_key(*key))
    })
}

/// Validate the signed wire object before constructing Event, which otherwise
/// tolerates unknown JSON fields. Return only the canonical, signed event.
fn validate(raw: &Value, now: u64) -> Result<Value> {
    if raw.to_string().len() > MAX_EVENT
        || !exact_keys(
            raw,
            &[
                "id",
                "pubkey",
                "created_at",
                "kind",
                "tags",
                "content",
                "sig",
            ],
        )
        || raw["kind"].as_u64() != Some(11120)
    {
        return Err(input("Invalid public provider record"));
    }
    let created = raw["created_at"]
        .as_u64()
        .ok_or_else(|| input("Invalid provider creation time"))?;
    if created > now.saturating_add(FUTURE_SKEW) {
        return Err(input("Provider record is from the future"));
    }
    let content = raw["content"]
        .as_str()
        .ok_or_else(|| input("Invalid provider content"))?;
    let content: Value =
        serde_json::from_str(content).map_err(|_| input("Invalid provider content"))?;
    if !exact_keys(
        &content,
        &[
            "protocol",
            "version",
            "public",
            "pex",
            "expires_at",
            "transports",
        ],
    ) || content["protocol"] != PROTOCOL
        || content["version"].as_u64() != Some(1)
        || content["public"] != true
        || content["pex"] != true
    {
        return Err(input("Unsupported public provider record"));
    }
    let expires = content["expires_at"]
        .as_u64()
        .ok_or_else(|| input("Invalid provider expiration"))?;
    if expires <= now || expires <= created || expires.saturating_sub(created) > TTL {
        return Err(input("Provider record is expired or exceeds its lifetime"));
    }
    let transports = content["transports"]
        .as_array()
        .ok_or_else(|| input("Invalid provider transports"))?;
    let mut unique = HashSet::new();
    if transports.is_empty()
        || transports.len() > 4
        || transports.iter().any(|value| {
            !matches!(value.as_str(), Some("direct" | "tor" | "i2p" | "ipfs"))
                || !unique.insert(value.as_str())
        })
    {
        return Err(input("Invalid provider transports"));
    }
    // Exactly the public protocol and its signature-bound expiration may be tagged.
    let expected_topic = json!(["t", PROTOCOL]);
    let expected_expiration = json!(["expiration", expires.to_string()]);
    let tags = raw["tags"]
        .as_array()
        .ok_or_else(|| input("Invalid provider tags"))?;
    if tags.len() != 2 || !tags.contains(&expected_topic) || !tags.contains(&expected_expiration) {
        return Err(input("Provider tags do not match the public schema"));
    }
    let event: Event =
        serde_json::from_value(raw.clone()).map_err(|_| input("Invalid signed provider event"))?;
    event
        .verify()
        .map_err(|_| input("Invalid provider signature"))?;
    serde_json::to_value(event).map_err(|_| state("Cannot serialize provider record"))
}

fn advertisement(keys: &Keys, now: u64) -> Result<Event> {
    let expires = now
        .checked_add(TTL)
        .ok_or_else(|| state("Invalid system time"))?;
    // These are protocol capabilities, never transport addresses or a catalog.
    let content = json!({"protocol":PROTOCOL, "version":1, "public":true, "pex":true,
        "expires_at":expires, "transports":["direct", "tor", "i2p", "ipfs"]});
    EventBuilder::new(KIND, content.to_string())
        .custom_created_at(Timestamp::from_secs(now))
        .tags([
            Tag::hashtag(PROTOCOL),
            Tag::expiration(Timestamp::from_secs(expires)),
        ])
        .sign_with_keys(keys)
        .map_err(|_| state("Could not sign provider advertisement"))
}

fn expires(record: &Value, now: u64) -> bool {
    record["tags"].as_array().is_some_and(|tags| {
        tags.iter().any(|tag| {
            tag[0] == "expiration"
                && tag[1]
                    .as_str()
                    .and_then(|value| value.parse::<u64>().ok())
                    .is_some_and(|expiry| expiry > now)
        })
    })
}

/// Also used for cached and PEX-forwarded records: the current native UTC clock
/// and full signature/schema verification apply on every invocation.
pub fn verify(raw: &str) -> Result<String> {
    if raw.len() > MAX_BATCH * MAX_EVENT {
        return Err(input("Provider batch exceeds 256 KiB"));
    }
    let records: Value =
        serde_json::from_str(raw).map_err(|_| input("Invalid provider record batch"))?;
    let records = records
        .as_array()
        .ok_or_else(|| input("Provider batch must be an array"))?;
    if records.len() > MAX_BATCH {
        return Err(input("Provider batch exceeds 128 records"));
    }
    let now = Timestamp::now().as_secs();
    let mut seen = HashSet::new();
    let verified: Vec<Value> = records
        .iter()
        .filter_map(|record| {
            let value = validate(record, now).ok()?;
            seen.insert(value["id"].as_str()?.to_owned())
                .then_some(value)
        })
        .collect();
    Ok(json!(verified).to_string())
}

async fn run(
    session: Arc<Session>,
    keys: Keys,
    relay_urls: Vec<String>,
    mut stop: watch::Receiver<bool>,
    read_budget: Duration,
) {
    if *stop.borrow() || session.closed.load(Ordering::Acquire) {
        return;
    }
    let deadline = tokio::time::Instant::now() + read_budget;
    let limits = RelayLimits {
        messages: RelayMessageLimits {
            max_size: Some(8192),
        },
        events: RelayEventLimits {
            max_size: Some(MAX_EVENT as u32),
            max_num_tags: Some(2),
            ..Default::default()
        },
    };
    let options = ClientOptions::new()
        .relay_limits(limits)
        .verify_subscriptions(true)
        .automatic_authentication(false)
        .pool(
            RelayPoolOptions::new()
                .max_relays(Some(3))
                .notification_channel_size(MAX_INBOX)
                .automatic_authentication(false),
        );
    let client = Client::builder()
        .signer(keys.clone())
        .opts(options)
        .websocket_transport(crate::nostr::BoundedTransport)
        .database(MemoryDatabase::with_opts(MemoryDatabaseOptions {
            events: false,
            max_events: Some(MAX_SEEN),
        }))
        .build();
    let mut notifications = client.notifications();
    for url in &relay_urls {
        if client.add_relay(url).await.is_err() {
            snapshot(&session, "failed", 0);
            return;
        }
    }
    tokio::select! {
        biased;
        _ = stop.changed() => return,
        _ = tokio::time::sleep_until(deadline) => {
            session.read_complete.store(true, Ordering::Release);
            snapshot(&session, "complete", 0);
            let _ = tokio::time::timeout(Duration::from_secs(1), client.shutdown()).await;
            return;
        },
        _ = client.connect() => {},
    }
    let filter = Filter::new()
        .kind(KIND)
        .hashtag(PROTOCOL)
        .since(Timestamp::from_secs(
            Timestamp::now().as_secs().saturating_sub(TTL),
        ))
        .limit(MAX_INBOX);
    let subscribed = tokio::select! {
        biased;
        _ = stop.changed() => false,
        result = tokio::time::timeout(Duration::from_secs(1), client.subscribe(filter, None)) => matches!(result, Ok(Ok(_))),
    };
    if !subscribed || *stop.borrow() || session.closed.load(Ordering::Acquire) {
        snapshot(
            &session,
            if *stop.borrow() { "closed" } else { "failed" },
            0,
        );
        let _ = tokio::time::timeout(Duration::from_secs(1), client.shutdown()).await;
        return;
    }
    let mut tick = tokio::time::interval(Duration::from_millis(250));
    let mut next_advertisement = Instant::now();
    let mut seen = HashSet::<EventId>::new();
    let mut latest = HashMap::<PublicKey, u64>::new();
    let mut examined = 0usize;
    let mut read_complete = false;
    let mut failed = false;
    loop {
        tokio::select! {
            biased;
            _ = stop.changed() => break,
            _ = tokio::time::sleep_until(deadline), if !read_complete => {
                read_complete = true;
                session.read_complete.store(true, Ordering::Release);
                session.available.notify_all();
                let _ = tokio::time::timeout(Duration::from_millis(500), client.unsubscribe_all()).await;
                if !session.advertise { break; }
            }
            _ = tick.tick() => {
                let connected = client.relays().await.values()
                    .filter(|relay| relay.status() == RelayStatus::Connected).count();
                snapshot(&session, if connected > 0 {"connected"} else {"connecting"}, connected);
                if session.advertise && connected > 0 && Instant::now() >= next_advertisement {
                    next_advertisement = Instant::now() + RENEW_INTERVAL;
                    if let Ok(event) = advertisement(&keys, Timestamp::now().as_secs()) {
                        tokio::select! {
                            biased;
                            _ = stop.changed() => break,
                            _ = tokio::time::timeout(Duration::from_secs(1), client.send_event(&event)) => {},
                        }
                    }
                }
            }
            received = notifications.recv(), if !read_complete => {
                if matches!(received, Err(tokio::sync::broadcast::error::RecvError::Closed)) {
                    failed = true;
                    break;
                }
                if let Ok(RelayPoolNotification::Event {event, ..}) = received {
                    // Bound expensive verification and deduplication work even when a relay floods.
                    if event.pubkey == keys.public_key() || seen.contains(&event.id)
                        || examined >= MAX_VALIDATIONS || seen.len() >= MAX_SEEN { continue; }
                    examined += 1;
                    let now = Timestamp::now().as_secs();
                    let record = serde_json::to_value(&*event)
                        .ok().and_then(|value| validate(&value, now).ok());
                    if let Some(record) = record {
                        seen.insert(event.id);
                        if latest.get(&event.pubkey).is_some_and(|created| *created >= event.created_at.as_secs()) { continue; }
                        latest.insert(event.pubkey, event.created_at.as_secs());
                        if let Ok(mut inbox) = session.inbox.lock() {
                            inbox.retain(|old| expires(old, now) && old["pubkey"] != record["pubkey"]);
                            if inbox.len() == MAX_INBOX { inbox.pop_front(); }
                            inbox.push_back(record);
                            session.accepted.fetch_add(1, Ordering::Relaxed);
                            session.available.notify_all();
                        }
                    } else {
                        session.rejected.fetch_add(1, Ordering::Relaxed);
                    }
                }
            }
        }
    }
    let _ = tokio::time::timeout(Duration::from_secs(1), client.shutdown()).await;
    snapshot(
        &session,
        if failed {
            "failed"
        } else if session.closed.load(Ordering::Acquire) {
            "closed"
        } else {
            "complete"
        },
        0,
    );
}

pub fn open(secret: &str, relay_json: &str, advertise: bool) -> Result<i64> {
    open_inner(secret, relay_json, advertise, false, READ_BUDGET)
}

fn open_inner(
    secret: &str,
    relay_json: &str,
    advertise: bool,
    local_test: bool,
    read_budget: Duration,
) -> Result<i64> {
    let keys = crate::nostr::keys(secret)?;
    let relay_urls = crate::nostr::relays(relay_json, local_test)?;
    let mut registry = sessions()
        .lock()
        .map_err(|_| state("Discovery registry unavailable"))?;
    if registry.len() >= 2 {
        return Err(state("Too many discovery sessions"));
    }
    let handle = NEXT.fetch_add(1, Ordering::Relaxed);
    if handle <= 0 {
        return Err(state("Discovery handles exhausted"));
    }
    let (stop, stopped) = watch::channel(false);
    let session = Arc::new(Session {
        closed: AtomicBool::new(false),
        finished: AtomicBool::new(false),
        read_complete: AtomicBool::new(false),
        advertise,
        configured: relay_urls.len(),
        accepted: AtomicUsize::new(0),
        rejected: AtomicUsize::new(0),
        stop,
        worker: Mutex::new(None),
        inbox: Mutex::new(VecDeque::new()),
        available: Condvar::new(),
        snapshot: Mutex::new(json!({"state":"connecting", "connected_relays":0})),
    });
    let thread_session = session.clone();
    let worker = std::thread::Builder::new()
        .name("bitchord-discovery".to_owned())
        .spawn(move || {
            match tokio::runtime::Builder::new_current_thread()
                .enable_all()
                .build()
            {
                Ok(runtime) => {
                    let outcome = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
                        runtime.block_on(run(
                            thread_session.clone(),
                            keys,
                            relay_urls,
                            stopped,
                            read_budget,
                        ));
                    }));
                    if outcome.is_err() {
                        snapshot(&thread_session, "failed", 0);
                    }
                    runtime.shutdown_timeout(Duration::from_millis(250));
                }
                Err(_) => snapshot(&thread_session, "failed", 0),
            }
            thread_session.finished.store(true, Ordering::Release);
            thread_session.available.notify_all();
        })
        .map_err(|_| io("Could not start discovery worker"))?;
    *session
        .worker
        .lock()
        .map_err(|_| state("Discovery worker unavailable"))? = Some(worker);
    registry.insert(handle, session);
    Ok(handle)
}

pub fn status(handle: i64) -> Result<String> {
    let session = get(handle)?;
    let mut value = session
        .snapshot
        .lock()
        .map_err(|_| state("Discovery status unavailable"))?
        .clone();
    value["configured_relays"] = json!(session.configured);
    value["read_complete"] = json!(session.read_complete.load(Ordering::Acquire));
    value["advertising"] = json!(
        session.advertise
            && !session.closed.load(Ordering::Acquire)
            && !session.finished.load(Ordering::Acquire)
    );
    value["accepted"] = json!(session.accepted.load(Ordering::Relaxed));
    value["rejected"] = json!(session.rejected.load(Ordering::Relaxed));
    value["queued"] = json!(
        session
            .inbox
            .lock()
            .map_err(|_| state("Discovery inbox unavailable"))?
            .len()
    );
    Ok(value.to_string())
}

pub fn poll(handle: i64, timeout_ms: i32) -> Result<String> {
    if !(0..=1000).contains(&timeout_ms) {
        return Err(input("Invalid discovery poll timeout"));
    }
    let session = get(handle)?;
    let inbox = session
        .inbox
        .lock()
        .map_err(|_| state("Discovery inbox unavailable"))?;
    let (mut inbox, _) = session
        .available
        .wait_timeout_while(inbox, Duration::from_millis(timeout_ms as u64), |queue| {
            queue.is_empty()
                && !session.closed.load(Ordering::Acquire)
                && !session.finished.load(Ordering::Acquire)
                    && (session.advertise || !session.read_complete.load(Ordering::Acquire))
        })
        .map_err(|_| state("Discovery inbox unavailable"))?;
    let now = Timestamp::now().as_secs();
    inbox.retain(|record| expires(record, now));
    let records: Vec<Value> = inbox.drain(..).collect();
    Ok(json!(records).to_string())
}

pub fn close(handle: i64) -> Result<()> {
    let session = sessions()
        .lock()
        .map_err(|_| state("Discovery registry unavailable"))?
        .get(&handle)
        .cloned();
    let Some(session) = session else {
        return Ok(());
    };
    session.closed.store(true, Ordering::Release);
    let _ = session.stop.send(true);
    session.available.notify_all();
    session
        .inbox
        .lock()
        .map_err(|_| state("Discovery inbox unavailable"))?
        .clear();
    let mut worker = session
        .worker
        .lock()
        .map_err(|_| state("Discovery worker unavailable"))?;
    let deadline = Instant::now() + Duration::from_secs(2);
    if let Some(thread) = worker.as_ref() {
        while !thread.is_finished() && Instant::now() < deadline {
            std::thread::sleep(Duration::from_millis(10));
        }
        if !thread.is_finished() {
            return Err(io("Discovery is still shutting down"));
        }
    }
    if let Some(thread) = worker.take() {
        let _ = thread.join();
    }
    sessions()
        .lock()
        .map_err(|_| state("Discovery registry unavailable"))?
        .remove(&handle);
    Ok(())
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_app_bitchord_soulseek_PeerDiscoveryNative_open(
    mut env: JNIEnv,
    _: JClass,
    secret: JString,
    relays: JString,
    advertise: jboolean,
) -> jlong {
    invoke(&mut env, |env| {
        let secret = java_string(env, secret)?;
        let relays = java_string(env, relays)?;
        open(&secret, &relays, advertise != 0)
    })
}
#[unsafe(no_mangle)]
pub extern "system" fn Java_app_bitchord_soulseek_PeerDiscoveryNative_poll(
    mut env: JNIEnv,
    _: JClass,
    handle: jlong,
    timeout_ms: jint,
) -> jstring {
    invoke(&mut env, |env| output(env, poll(handle, timeout_ms)?))
}
#[unsafe(no_mangle)]
pub extern "system" fn Java_app_bitchord_soulseek_PeerDiscoveryNative_status(
    mut env: JNIEnv,
    _: JClass,
    handle: jlong,
) -> jstring {
    invoke(&mut env, |env| output(env, status(handle)?))
}
#[unsafe(no_mangle)]
pub extern "system" fn Java_app_bitchord_soulseek_PeerDiscoveryNative_verify(
    mut env: JNIEnv,
    _: JClass,
    records: JString,
) -> jstring {
    invoke(&mut env, |env| {
        let records = java_string(env, records)?;
        output(env, verify(&records)?)
    })
}
#[unsafe(no_mangle)]
pub extern "system" fn Java_app_bitchord_soulseek_PeerDiscoveryNative_close(
    mut env: JNIEnv,
    _: JClass,
    handle: jlong,
) {
    invoke(&mut env, |_| close(handle));
}

#[cfg(test)]
mod tests {
    use super::*;

    fn keys(n: u8) -> Keys {
        Keys::parse(&format!("{n:064x}")).unwrap()
    }
    fn record(now: u64) -> Value {
        serde_json::to_value(advertisement(&keys(1), now).unwrap()).unwrap()
    }
    fn signed(content: Value, now: u64, tags: Vec<Tag>) -> Value {
        serde_json::to_value(
            EventBuilder::new(KIND, content.to_string())
                .custom_created_at(Timestamp::from_secs(now))
                .tags(tags)
                .sign_with_keys(&keys(1))
                .unwrap(),
        )
        .unwrap()
    }
    fn tags(expires: u64) -> Vec<Tag> {
        vec![
            Tag::hashtag(PROTOCOL),
            Tag::expiration(Timestamp::from_secs(expires)),
        ]
    }

    #[test]
    fn signed_advertisements_have_only_public_protocol_capabilities() {
        assert!(KIND.is_replaceable());
        assert!(!KIND.is_ephemeral());
        let value = record(1000);
        assert!(validate(&value, 1000).is_ok());
        assert!(value.to_string().len() <= MAX_EVENT);
        let content: Value = serde_json::from_str(value["content"].as_str().unwrap()).unwrap();
        assert_eq!(content.as_object().unwrap().len(), 6);
        assert_eq!(
            content["transports"],
            json!(["direct", "tor", "i2p", "ipfs"])
        );
        assert!(validate(&value, 1299).is_ok());
        assert!(validate(&value, 1300).is_err());
        assert!(validate(&value, 969).is_err());
        assert!(validate(&value, 970).is_ok());
    }

    #[test]
    fn signatures_identity_and_unknown_private_fields_are_rejected() {
        let mut value = record(1000);
        value["sig"] = json!("0".repeat(128));
        assert!(validate(&value, 1000).is_err());
        let mut value = record(1000);
        value["pubkey"] = json!(keys(2).public_key().to_hex());
        assert!(validate(&value, 1000).is_err());
        let mut value = record(1000);
        value["endpoint"] = json!("192.0.2.1:1234");
        assert!(validate(&value, 1000).is_err());
        let content: Value =
            serde_json::from_str(record(1000)["content"].as_str().unwrap()).unwrap();
        for field in ["endpoint", "catalog", "name", "secret", "paired_peers"] {
            let mut bad = content.clone();
            bad[field] = json!("private");
            assert!(validate(&signed(bad, 1000, tags(1300)), 1000).is_err());
        }
    }

    #[test]
    fn signed_schema_lifetime_tags_and_size_are_strict() {
        let content: Value =
            serde_json::from_str(record(1000)["content"].as_str().unwrap()).unwrap();
        for (field, bad_value) in [
            ("public", json!(false)),
            ("pex", json!(false)),
            ("version", json!(2)),
            ("protocol", json!("other")),
            ("transports", json!(["direct", "direct"])),
            ("transports", json!(["http"])),
            ("transports", json!([])),
            ("expires_at", json!(1301)),
        ] {
            let mut bad = content.clone();
            bad[field] = bad_value;
            assert!(
                validate(&signed(bad, 1000, tags(1300)), 1000).is_err(),
                "{field}"
            );
        }
        assert!(validate(&signed(content.clone(), 1000, tags(1299)), 1000).is_err());
        let mut extra_tags = tags(1300);
        extra_tags.push(Tag::public_key(keys(2).public_key()));
        assert!(validate(&signed(content.clone(), 1000, extra_tags), 1000).is_err());
        let oversized = EventBuilder::new(KIND, format!("{}{}", content, " ".repeat(MAX_EVENT)))
            .custom_created_at(Timestamp::from_secs(1000))
            .tags(tags(1300))
            .sign_with_keys(&keys(1))
            .unwrap();
        assert!(validate(&serde_json::to_value(oversized).unwrap(), 1000).is_err());
        let mut too_long = content;
        too_long["expires_at"] = json!(1301);
        assert!(validate(&signed(too_long, 1000, tags(1301)), 1000).is_err());
    }

    #[test]
    fn cached_batches_reverify_signatures_expiry_and_limits() {
        let now = Timestamp::now().as_secs();
        let valid = record(now);
        let mut tampered = valid.clone();
        tampered["content"] = json!("{}");
        let batch = json!([valid.clone(), valid.clone(), record(now - TTL), tampered]);
        assert_eq!(
            serde_json::from_str::<Value>(&verify(&batch.to_string()).unwrap()).unwrap(),
            json!([valid])
        );
        assert!(verify("{}").is_err());
        assert!(verify("[invalid]").is_err());
        assert!(verify(&json!(vec![record(now); MAX_BATCH + 1]).to_string()).is_err());
        assert!(verify(&" ".repeat(MAX_BATCH * MAX_EVENT + 1)).is_err());
    }

    struct LocalRelay {
        url: String,
        stopped: Arc<AtomicBool>,
        published: Arc<Mutex<Vec<Value>>>,
        unsubscribed: Arc<AtomicBool>,
        worker: Option<JoinHandle<()>>,
    }
    impl LocalRelay {
        fn start() -> Self {
            let listener = std::net::TcpListener::bind("127.0.0.1:0").unwrap();
            listener.set_nonblocking(true).unwrap();
            let url = format!("ws://{}", listener.local_addr().unwrap());
            let stopped = Arc::new(AtomicBool::new(false));
            let published = Arc::new(Mutex::new(Vec::new()));
            let unsubscribed = Arc::new(AtomicBool::new(false));
            let stop = stopped.clone();
            let sent = published.clone();
            let closed = unsubscribed.clone();
            let worker = std::thread::spawn(move || {
                use tokio_tungstenite::tungstenite::{Error, Message};
                while !stop.load(Ordering::Acquire) {
                    let stream = match listener.accept() {
                        Ok((stream, _)) => stream,
                        Err(error) if error.kind() == std::io::ErrorKind::WouldBlock => {
                            std::thread::sleep(Duration::from_millis(5));
                            continue;
                        }
                        Err(_) => return,
                    };
                    stream
                        .set_read_timeout(Some(Duration::from_millis(50)))
                        .unwrap();
                    stream
                        .set_write_timeout(Some(Duration::from_millis(500)))
                        .unwrap();
                    let Ok(mut socket) = tokio_tungstenite::tungstenite::accept(stream) else {
                        continue;
                    };
                    while !stop.load(Ordering::Acquire) {
                        match socket.read() {
                            Ok(Message::Text(text)) => {
                                let value: Value = serde_json::from_str(&text).unwrap();
                                match value[0].as_str() {
                                    Some("REQ") => {
                                        assert_eq!(value[2]["#t"], json!([PROTOCOL]));
                                        assert_eq!(value[2]["kinds"], json!([11120]));
                                        let now = Timestamp::now().as_secs();
                                        let valid = record(now);
                                        let mut private: Value = serde_json::from_str(
                                            valid["content"].as_str().unwrap(),
                                        )
                                        .unwrap();
                                        private["endpoint"] = json!("private");
                                        let invalid = signed(private, now, tags(now + TTL));
                                        for event in [&valid, &valid, &invalid] {
                                            if socket
                                                .send(Message::Text(
                                                    json!(["EVENT", value[1], event])
                                                        .to_string()
                                                        .into(),
                                                ))
                                                .is_err()
                                            {
                                                break;
                                            }
                                        }
                                        let _ = socket.send(Message::Text(
                                            json!(["EOSE", value[1]]).to_string().into(),
                                        ));
                                    }
                                    Some("EVENT") => {
                                        sent.lock().unwrap().push(value[1].clone());
                                        let _ = socket.send(Message::Text(
                                            json!(["OK", value[1]["id"], true, ""])
                                                .to_string()
                                                .into(),
                                        ));
                                    }
                                    Some("CLOSE") => {
                                        closed.store(true, Ordering::Release);
                                    }
                                    _ => {}
                                }
                            }
                            Ok(Message::Ping(value)) => {
                                let _ = socket.send(Message::Pong(value));
                            }
                            Ok(Message::Close(_)) => break,
                            Err(Error::Io(error))
                                if matches!(
                                    error.kind(),
                                    std::io::ErrorKind::WouldBlock | std::io::ErrorKind::TimedOut
                                ) => {}
                            Err(_) => break,
                            _ => {}
                        }
                    }
                }
            });
            Self {
                url,
                stopped,
                published,
                unsubscribed,
                worker: Some(worker),
            }
        }
        fn open(&self, advertise: bool) -> i64 {
            open_inner(
                &keys(2).secret_key().to_secret_hex(),
                &json!([self.url]).to_string(),
                advertise,
                true,
                Duration::from_secs(2),
            )
            .unwrap()
        }
    }
    impl Drop for LocalRelay {
        fn drop(&mut self) {
            self.stopped.store(true, Ordering::Release);
            if let Some(worker) = self.worker.take() {
                worker.join().unwrap();
            }
        }
    }

    #[test]
    fn local_relay_discovery_is_bounded_signed_opt_in_and_cancellable() {
        let relay = LocalRelay::start();
        let handle = relay.open(true);
        let deadline = Instant::now() + Duration::from_secs(5);
        let mut received = Vec::new();
        loop {
            let batch: Vec<Value> = serde_json::from_str(&poll(handle, 100).unwrap()).unwrap();
            received.extend(batch);
            let status: Value = serde_json::from_str(&status(handle).unwrap()).unwrap();
            if status["read_complete"] == true
                && relay.unsubscribed.load(Ordering::Acquire)
                && !relay.published.lock().unwrap().is_empty()
            {
                break;
            }
            assert!(
                Instant::now() < deadline,
                "local discovery did not complete"
            );
            std::thread::sleep(Duration::from_millis(10));
        }
        assert_eq!(
            received.len(),
            1,
            "replayed and private records must not escape"
        );
        assert_eq!(received[0]["pubkey"], keys(1).public_key().to_hex());
        assert!(validate(&received[0], Timestamp::now().as_secs()).is_ok());
        let status_value: Value = serde_json::from_str(&status(handle).unwrap()).unwrap();
        assert_eq!(status_value["accepted"], 1);
        assert_eq!(status_value["rejected"], 1);
        assert_eq!(status_value["advertising"], true);
        assert!(relay.unsubscribed.load(Ordering::Acquire));
        let poll_started = Instant::now();
        assert_eq!(poll(handle, 100).unwrap(), "[]");
        assert!(
            poll_started.elapsed() >= Duration::from_millis(80),
            "an idle advertiser must keep bounded polling after its read window"
        );
        {
            let published = relay.published.lock().unwrap();
            assert_eq!(published.len(), 1);
            assert!(validate(&published[0], Timestamp::now().as_secs()).is_ok());
            assert_eq!(published[0]["pubkey"], keys(2).public_key().to_hex());
        }
        close(handle).unwrap();
        let publish_count = relay.published.lock().unwrap().len();
        let reader = relay.open(false);
        let deadline = Instant::now() + Duration::from_secs(5);
        loop {
            let status: Value = serde_json::from_str(&status(reader).unwrap()).unwrap();
            if status["state"] == "complete" {
                break;
            }
            assert!(Instant::now() < deadline);
            std::thread::sleep(Duration::from_millis(20));
        }
        assert_eq!(
            relay.published.lock().unwrap().len(),
            publish_count,
            "read-only discovery must not advertise"
        );
        close(reader).unwrap();
    }

    #[test]
    fn sessions_validate_before_connecting_and_close_wakes_pollers() {
        assert!(
            open(
                &keys(1).secret_key().to_secret_hex(),
                "[\"ws://relay.example\"]",
                true
            )
            .is_err()
        );
        assert!(open(&"0".repeat(64), "[\"wss://relay.example\"]", false).is_err());
        let handle = open(
            &keys(1).secret_key().to_secret_hex(),
            "[\"wss://relay.example\"]",
            false,
        )
        .unwrap();
        assert!(poll(handle, 1001).is_err());
        let waiter = std::thread::spawn(move || poll(handle, 1000));
        let start = Instant::now();
        close(handle).unwrap();
        assert!(start.elapsed() < Duration::from_secs(2));
        // A waiter racing registry removal can report closed, or receive an empty batch.
        assert!(
            matches!(waiter.join().unwrap(), Ok(value) if value == "[]") || get(handle).is_err()
        );
        close(handle).unwrap();
        assert!(status(handle).is_err());
    }
}
