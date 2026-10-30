//! Encrypted, short-lived signaling between explicitly paired app identities.

use crate::{Result, input, invoke, io, java_string, output, state};
use async_wsocket::{ConnectionMode, Message};
use futures_util::{Sink, SinkExt, StreamExt, TryStreamExt};
use jni::JNIEnv;
use jni::objects::{JClass, JString};
use jni::sys::{jint, jlong, jstring};
use nostr_sdk::nostr::util::BoxedFuture;
use nostr_sdk::pool::transport::error::TransportError;
use nostr_sdk::pool::transport::websocket::{WebSocketSink, WebSocketStream, WebSocketTransport};
use nostr_sdk::prelude::limits::{RelayEventLimits, RelayMessageLimits};
use nostr_sdk::prelude::*;
use serde_json::{Value, json};
use std::collections::{HashMap, HashSet, VecDeque};
use std::pin::Pin;
use std::sync::atomic::{AtomicBool, AtomicI64, Ordering};
use std::sync::{Arc, Condvar, Mutex, OnceLock, mpsc as std_mpsc};
use std::task::{Context, Poll};
use std::thread::JoinHandle;
use std::time::{Duration, Instant};
use tokio::sync::{mpsc, watch};

const PROTOCOL: &str = "app.bitchord.signal/1";
const KIND: Kind = Kind::Custom(21118);
const TTL: u64 = 120;
const FUTURE_SKEW: u64 = 30;
const MAX_PAYLOAD: usize = 8192;
const MAX_CIPHERTEXT: usize = 20_000;
const MAX_PEERS: usize = 16;
const MAX_INBOX: usize = 32;
const MAX_REPLAY: usize = 2048;
const SEND_TIMEOUT: Duration = Duration::from_secs(6);
static NEXT: AtomicI64 = AtomicI64::new(1);
static SESSIONS: OnceLock<Mutex<HashMap<i64, Arc<Session>>>> = OnceLock::new();

#[derive(Debug)]
struct BoundedTransport;

type WireSocket =
    tokio_tungstenite::WebSocketStream<tokio_tungstenite::MaybeTlsStream<tokio::net::TcpStream>>;
type WireMessage = tokio_tungstenite::tungstenite::Message;
struct WireSink(futures_util::stream::SplitSink<WireSocket, WireMessage>);

impl Sink<Message> for WireSink {
    type Error = TransportError;
    fn poll_ready(
        mut self: Pin<&mut Self>,
        cx: &mut Context<'_>,
    ) -> Poll<std::result::Result<(), Self::Error>> {
        Pin::new(&mut self.0)
            .poll_ready_unpin(cx)
            .map_err(TransportError::backend)
    }
    fn start_send(mut self: Pin<&mut Self>, item: Message) -> std::result::Result<(), Self::Error> {
        Pin::new(&mut self.0)
            .start_send_unpin(item.into())
            .map_err(TransportError::backend)
    }
    fn poll_flush(
        mut self: Pin<&mut Self>,
        cx: &mut Context<'_>,
    ) -> Poll<std::result::Result<(), Self::Error>> {
        Pin::new(&mut self.0)
            .poll_flush_unpin(cx)
            .map_err(TransportError::backend)
    }
    fn poll_close(
        mut self: Pin<&mut Self>,
        cx: &mut Context<'_>,
    ) -> Poll<std::result::Result<(), Self::Error>> {
        Pin::new(&mut self.0)
            .poll_close_unpin(cx)
            .map_err(TransportError::backend)
    }
}

impl WebSocketTransport for BoundedTransport {
    fn support_ping(&self) -> bool {
        true
    }
    fn connect<'a>(
        &'a self,
        url: &'a Url,
        _: &'a ConnectionMode,
        timeout: Duration,
    ) -> BoxedFuture<'a, std::result::Result<(WebSocketSink, WebSocketStream), TransportError>>
    {
        Box::pin(async move {
            let config = tokio_tungstenite::tungstenite::protocol::WebSocketConfig::default()
                .read_buffer_size(8192)
                .write_buffer_size(8192)
                .max_write_buffer_size(65_536)
                .max_message_size(Some(32_768))
                .max_frame_size(Some(32_768));
            let (socket, _) = tokio::time::timeout(
                timeout.min(Duration::from_secs(6)),
                tokio_tungstenite::connect_async_with_config(url.as_str(), Some(config), true),
            )
            .await
            .map_err(TransportError::backend)?
            .map_err(TransportError::backend)?;
            let (tx, rx) = socket.split();
            let stream = rx
                .map_ok(|wire| match wire {
                    WireMessage::Text(text) => Message::Text(text.to_string()),
                    WireMessage::Binary(bytes) => Message::Binary(bytes.to_vec()),
                    WireMessage::Ping(bytes) => Message::Ping(bytes.to_vec()),
                    WireMessage::Pong(bytes) => Message::Pong(bytes.to_vec()),
                    WireMessage::Close(frame) => Message::Close(frame.map(Into::into)),
                    WireMessage::Frame(_) => Message::Close(None),
                })
                .map_err(TransportError::backend);
            Ok((
                Box::new(WireSink(tx)) as WebSocketSink,
                Box::pin(stream) as WebSocketStream,
            ))
        })
    }
}

struct Session {
    closed: AtomicBool,
    commands: mpsc::Sender<Command>,
    stop: watch::Sender<bool>,
    worker: Mutex<Option<JoinHandle<()>>>,
    inbox: Mutex<VecDeque<Value>>,
    available: Condvar,
    snapshot: Mutex<Value>,
}

struct Command {
    peer: PublicKey,
    session: String,
    kind: String,
    payload: Value,
    deadline: Instant,
    reply: std_mpsc::SyncSender<Result<String>>,
}

struct Gate {
    peers: HashSet<PublicKey>,
    seen: VecDeque<(EventId, String, u64)>,
    received: HashMap<PublicKey, VecDeque<u64>>,
    sent: HashMap<PublicKey, VecDeque<u64>>,
}

fn sessions() -> &'static Mutex<HashMap<i64, Arc<Session>>> {
    SESSIONS.get_or_init(|| Mutex::new(HashMap::new()))
}

fn get(handle: i64) -> Result<Arc<Session>> {
    sessions()
        .lock()
        .map_err(|_| state("Signaling registry unavailable"))?
        .get(&handle)
        .cloned()
        .ok_or_else(|| state("Signaling session is closed"))
}

fn fixed_hex(value: &str, len: usize) -> bool {
    value.len() == len
        && value
            .bytes()
            .all(|b| b.is_ascii_digit() || (b'a'..=b'f').contains(&b))
}

pub(crate) fn keys(secret: &str) -> Result<Keys> {
    if !fixed_hex(secret, 64) {
        return Err(input("Invalid signaling identity"));
    }
    Keys::parse(secret).map_err(|_| input("Invalid signaling identity"))
}

fn public_key(value: &str) -> Result<PublicKey> {
    if !fixed_hex(value, 64) {
        return Err(input("Invalid peer public key"));
    }
    let peer = PublicKey::from_hex(value).map_err(|_| input("Invalid peer public key"))?;
    peer.xonly().map_err(|_| input("Invalid peer public key"))?;
    Ok(peer)
}

fn public_hostname(host: &str) -> bool {
    host.len() <= 253
        && host.contains('.')
        && !host.bytes().all(|b| b.is_ascii_digit() || b == b'.')
        && ![".localhost", ".local", ".internal", ".invalid", ".test"]
            .iter()
            .any(|suffix| host.ends_with(suffix))
        && host.split('.').all(|label| {
            !label.is_empty()
                && label.len() <= 63
                && label
                    .as_bytes()
                    .first()
                    .is_some_and(u8::is_ascii_alphanumeric)
                && label
                    .as_bytes()
                    .last()
                    .is_some_and(u8::is_ascii_alphanumeric)
                && label
                    .bytes()
                    .all(|b| b.is_ascii_lowercase() || b.is_ascii_digit() || b == b'-')
        })
}

fn safe_path(path: &str) -> bool {
    let bytes = path.as_bytes();
    let mut index = 0;
    while index < bytes.len() {
        if bytes[index] == b'%' {
            if index + 2 >= bytes.len() {
                return false;
            }
            let Some(high) = (bytes[index + 1] as char).to_digit(16) else {
                return false;
            };
            let Some(low) = (bytes[index + 2] as char).to_digit(16) else {
                return false;
            };
            let value = high * 16 + low;
            if value < 32 || value == 127 {
                return false;
            }
            index += 3;
        } else {
            index += 1;
        }
    }
    true
}

pub(crate) fn relays(raw: &str, allow_local_test: bool) -> Result<Vec<String>> {
    if raw.len() > 2048 {
        return Err(input("Too many relay settings"));
    }
    let values: Vec<String> =
        serde_json::from_str(raw).map_err(|_| input("Invalid relay settings"))?;
    if values.is_empty() || values.len() > 3 {
        return Err(input("Choose between one and three relays"));
    }
    let mut unique = HashSet::new();
    values
        .into_iter()
        .map(|value| {
            if value.len() > 512 || value.chars().any(char::is_control) {
                return Err(input("Invalid relay address"));
            }
            let url = Url::parse(&value).map_err(|_| input("Invalid relay address"))?;
            let local_test = cfg!(test)
                && allow_local_test
                && url.scheme() == "ws"
                && matches!(
                    url.host_str(),
                    Some("127.0.0.1") | Some("[::1]") | Some("localhost")
                );
            let public_name = url.host_str().is_some_and(public_hostname);
            if (url.scheme() != "wss" && !local_test)
                || (!local_test && (!public_name || url.port_or_known_default() != Some(443)))
                || url.host_str().is_none()
                || !url.username().is_empty()
                || url.password().is_some()
                || url.query().is_some()
                || url.fragment().is_some()
                || !safe_path(url.path())
                || !unique.insert(url.to_string())
            {
                return Err(input(
                    "Use distinct secure relay addresses without credentials",
                ));
            }
            Ok(url.to_string())
        })
        .collect()
}

fn peers(raw: &str, own: PublicKey) -> Result<HashSet<PublicKey>> {
    if raw.len() > 1200 {
        return Err(input("Too many peer settings"));
    }
    let values: Vec<String> =
        serde_json::from_str(raw).map_err(|_| input("Invalid peer settings"))?;
    if values.is_empty() || values.len() > MAX_PEERS {
        return Err(input("Pair between one and 16 peers"));
    }
    let mut result = HashSet::new();
    for value in values {
        let peer = public_key(&value)?;
        if peer == own || !result.insert(peer) {
            return Err(input("Peer keys must be distinct from this identity"));
        }
    }
    Ok(result)
}

fn validate_message(session: &str, kind: &str, payload: &Value) -> Result<()> {
    if !fixed_hex(session, 32) {
        return Err(input("Invalid signaling session ID"));
    }
    if !matches!(
        kind,
        "hello" | "offer" | "answer" | "candidate" | "cancel" | "ping" | "pong"
    ) {
        return Err(input("Unsupported signaling message type"));
    }
    if !payload.is_object() || payload.to_string().len() > MAX_PAYLOAD {
        return Err(input(
            "Signaling payload must be an object of at most 8 KiB",
        ));
    }
    Ok(())
}

fn rate(queue: &mut VecDeque<u64>, now: u64) -> bool {
    while queue
        .front()
        .is_some_and(|old| now.saturating_sub(*old) >= 60)
    {
        queue.pop_front();
    }
    if queue.len() >= 60 {
        return false;
    }
    queue.push_back(now);
    true
}

pub(crate) fn seal(
    keys: &Keys,
    peer: PublicKey,
    session: &str,
    kind: &str,
    payload: Value,
    now: u64,
) -> Result<Event> {
    validate_message(session, kind, &payload)?;
    let expires = now.checked_add(TTL).ok_or_else(|| state("Invalid clock"))?;
    let envelope = json!({
        "protocol": PROTOCOL, "sender": keys.public_key().to_hex(), "recipient": peer.to_hex(),
        "session": session, "type": kind, "created_at": now, "expires_at": expires,
        "nonce": Keys::generate().secret_key().to_secret_hex(), "payload": payload
    });
    let ciphertext = nip44::encrypt(
        keys.secret_key(),
        &peer,
        envelope.to_string(),
        nip44::Version::V2,
    )
    .map_err(|_| io("Could not encrypt connection details"))?;
    EventBuilder::new(KIND, ciphertext)
        .custom_created_at(Timestamp::from_secs(now))
        .tags([
            Tag::public_key(peer),
            Tag::expiration(Timestamp::from_secs(expires)),
        ])
        .sign_with_keys(keys)
        .map_err(|_| io("Could not sign connection details"))
}

impl Gate {
    fn new(peers: HashSet<PublicKey>) -> Self {
        Self {
            peers,
            seen: VecDeque::new(),
            received: HashMap::new(),
            sent: HashMap::new(),
        }
    }

    fn accept(&mut self, keys: &Keys, event: &Event, now: u64) -> Result<Value> {
        if event.kind != KIND
            || !self.peers.contains(&event.pubkey)
            || event.content.len() > MAX_CIPHERTEXT
            || event.tags.len() != 2
            || event.created_at.as_secs() > now.saturating_add(FUTURE_SKEW)
            || now.saturating_sub(event.created_at.as_secs()) >= TTL
        {
            return Err(input("Unaccepted signaling event"));
        }
        let expected_p = vec!["p".to_owned(), keys.public_key().to_hex()];
        let expected_expiration = event
            .created_at
            .as_secs()
            .checked_add(TTL)
            .ok_or_else(|| input("Invalid event expiry"))?;
        let expected_e = vec!["expiration".to_owned(), expected_expiration.to_string()];
        if !event.tags.iter().any(|tag| tag.as_slice() == expected_p)
            || !event.tags.iter().any(|tag| tag.as_slice() == expected_e)
        {
            return Err(input("Wrong signaling recipient or expiry"));
        }
        self.seen.retain(|(_, _, expiry)| *expiry > now);
        if self.seen.iter().any(|(id, _, _)| *id == event.id) {
            return Err(input("Repeated signaling event"));
        }
        event
            .verify()
            .map_err(|_| input("Invalid signaling signature"))?;
        let plain = nip44::decrypt(keys.secret_key(), &event.pubkey, &event.content)
            .map_err(|_| input("Invalid encrypted signaling event"))?;
        if plain.len() > MAX_PAYLOAD + 1024 {
            return Err(input("Signaling event is too large"));
        }
        let value: Value =
            serde_json::from_str(&plain).map_err(|_| input("Invalid signaling envelope"))?;
        let data = value
            .as_object()
            .ok_or_else(|| input("Invalid signaling envelope"))?;
        let session = value["session"]
            .as_str()
            .ok_or_else(|| input("Invalid signaling session"))?;
        let kind = value["type"]
            .as_str()
            .ok_or_else(|| input("Invalid signaling type"))?;
        let nonce = value["nonce"]
            .as_str()
            .ok_or_else(|| input("Invalid signaling nonce"))?;
        if data.len() != 9
            || value["protocol"] != PROTOCOL
            || value["sender"] != event.pubkey.to_hex()
            || value["recipient"] != keys.public_key().to_hex()
            || value["created_at"].as_u64() != Some(event.created_at.as_secs())
            || value["expires_at"].as_u64() != Some(expected_expiration)
            || now >= expected_expiration
            || !fixed_hex(nonce, 64)
        {
            return Err(input("Invalid signaling envelope binding"));
        }
        validate_message(session, kind, &value["payload"])?;
        let nonce_key = format!("{}:{nonce}", event.pubkey.to_hex());
        if self
            .seen
            .iter()
            .any(|(id, nonce, _)| *id == event.id || *nonce == nonce_key)
        {
            return Err(input("Repeated signaling event"));
        }
        if self.seen.len() >= MAX_REPLAY {
            return Err(input("Signaling replay window is full"));
        }
        if !rate(self.received.entry(event.pubkey).or_default(), now) {
            return Err(input("Peer signaling rate exceeded"));
        }
        self.seen
            .push_back((event.id, nonce_key, expected_expiration));
        Ok(
            json!({"peer":event.pubkey.to_hex(), "event":event.id.to_hex(), "session":session,
            "type":kind, "payload":value["payload"], "expires_at":expected_expiration}),
        )
    }
}

fn snapshot(session: &Session, state: &str, connected: usize) {
    if let Ok(mut value) = session.snapshot.lock() {
        value["state"] = json!(state);
        value["connected_relays"] = json!(connected);
    }
}

async fn run(
    session: Arc<Session>,
    keys: Keys,
    relay_urls: Vec<String>,
    peer_keys: HashSet<PublicKey>,
    mut commands: mpsc::Receiver<Command>,
    mut stop: watch::Receiver<bool>,
) {
    if *stop.borrow() || session.closed.load(Ordering::Acquire) {
        snapshot(&session, "closed", 0);
        return;
    }
    let limits = RelayLimits {
        messages: RelayMessageLimits {
            max_size: Some(32_768),
        },
        events: RelayEventLimits {
            max_size: Some(30_000),
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
                .notification_channel_size(64)
                .automatic_authentication(false),
        );
    let client = Client::builder()
        .signer(keys.clone())
        .opts(options)
        .websocket_transport(BoundedTransport)
        .database(MemoryDatabase::with_opts(MemoryDatabaseOptions {
            events: false,
            max_events: Some(MAX_REPLAY),
        }))
        .build();
    let mut notifications = client.notifications();
    for url in &relay_urls {
        if client.add_relay(url).await.is_err() {
            snapshot(&session, "failed", 0);
            return;
        }
    }
    if *stop.borrow() || session.closed.load(Ordering::Acquire) {
        snapshot(&session, "closed", 0);
        return;
    }
    tokio::select! {
        biased;
        _ = stop.changed() => { snapshot(&session, "closed", 0); return; },
        _ = client.connect() => {},
    }
    let filter = Filter::new()
        .kind(KIND)
        .pubkey(keys.public_key())
        .authors(peer_keys.iter().copied())
        .since(Timestamp::from_secs(
            Timestamp::now().as_secs().saturating_sub(FUTURE_SKEW),
        ));
    let subscribed = tokio::select! {
        biased;
        _ = stop.changed() => false,
        result = tokio::time::timeout(Duration::from_secs(1), client.subscribe(filter, None)) => matches!(result, Ok(Ok(_))),
    };
    if !subscribed || *stop.borrow() || session.closed.load(Ordering::Acquire) {
        let _ = tokio::time::timeout(Duration::from_secs(1), client.shutdown()).await;
        snapshot(
            &session,
            if *stop.borrow() { "closed" } else { "failed" },
            0,
        );
        return;
    }
    let mut gate = Gate::new(peer_keys);
    let mut tick = tokio::time::interval(Duration::from_secs(1));
    let mut failed = false;
    loop {
        tokio::select! {
            biased;
            _ = stop.changed() => break,
            _ = tick.tick() => {
                let connected = client.relays().await.values().filter(|r| r.status() == RelayStatus::Connected).count();
                snapshot(&session, if connected > 0 {"connected"} else {"connecting"}, connected);
            }
            Some(command) = commands.recv() => {
                let result = if Instant::now() >= command.deadline {Err(io("Connection signal expired before sending"))}
                    else if !gate.peers.contains(&command.peer) {Err(input("Peer is not paired"))}
                    else if !rate(gate.sent.entry(command.peer).or_default(), Timestamp::now().as_secs()) {Err(input("Signaling rate exceeded"))}
                    else {
                        match seal(&keys, command.peer, &command.session, &command.kind, command.payload, Timestamp::now().as_secs()) {
                            Err(error) => Err(error),
                            Ok(event) => {
                                tokio::select! {
                                    biased;
                                    _ = stop.changed() => { let _ = command.reply.send(Err(state("Signaling session closed"))); break; },
                                    sent = tokio::time::timeout(command.deadline.saturating_duration_since(Instant::now()), client.send_event(&event)) => {
                                        match sent {
                                            Ok(Ok(sent)) if !sent.success.is_empty() => Ok(json!({"event":event.id.to_hex(), "accepted_relays":sent.success.len(), "failed_relays":sent.failed.len()}).to_string()),
                                            _ => Err(io("No relay confirmed accepting the connection signal"))
                                        }
                                    }
                                }
                            }
                        }
                    };
                let _ = command.reply.send(result);
            }
            received = notifications.recv() => {
                if matches!(received, Err(tokio::sync::broadcast::error::RecvError::Closed)) {
                    failed = true;
                    break;
                }
                if let Ok(RelayPoolNotification::Event{event,..}) = received {
                    let now = Timestamp::now().as_secs();
                    if let Ok(message) = gate.accept(&keys, &event, now) {
                        if let Ok(mut inbox) = session.inbox.lock() {
                            inbox.retain(|item| item["expires_at"].as_u64().is_some_and(|expiry| expiry > now));
                            if inbox.len() < MAX_INBOX {
                                inbox.push_back(message);
                                session.available.notify_all();
                            }
                        }
                    }
                }
            }
        }
    }
    let _ = tokio::time::timeout(Duration::from_secs(1), client.shutdown()).await;
    snapshot(&session, if failed { "failed" } else { "closed" }, 0);
}

pub fn generate_secret() -> String {
    Keys::generate().secret_key().to_secret_hex()
}
pub fn derive_public(secret: &str) -> Result<String> {
    Ok(keys(secret)?.public_key().to_hex())
}

pub fn open(secret: &str, relay_json: &str, peer_json: &str) -> Result<i64> {
    open_inner(secret, relay_json, peer_json, false)
}

fn open_inner(secret: &str, relay_json: &str, peer_json: &str, local_test: bool) -> Result<i64> {
    let keys = keys(secret)?;
    let relay_urls = relays(relay_json, local_test)?;
    let peer_keys = peers(peer_json, keys.public_key())?;
    let mut registry = sessions()
        .lock()
        .map_err(|_| state("Signaling registry unavailable"))?;
    if registry.len() >= 4 {
        return Err(state("Too many signaling sessions"));
    }
    let handle = NEXT.fetch_add(1, Ordering::Relaxed);
    if handle <= 0 {
        return Err(state("Signaling handles exhausted"));
    }
    let (commands, receiver) = mpsc::channel(8);
    let (stop, stopped) = watch::channel(false);
    let session = Arc::new(Session {
        closed: AtomicBool::new(false),
        commands,
        stop,
        worker: Mutex::new(None),
        inbox: Mutex::new(VecDeque::new()),
        available: Condvar::new(),
        snapshot: Mutex::new(json!({"state":"connecting","connected_relays":0,
            "configured_relays":relay_urls.len(), "trusted_peers":peer_keys.len(), "queued":0})),
    });
    let thread_session = session.clone();
    let worker = std::thread::Builder::new()
        .name("bitchord-nostr".to_owned())
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
                            peer_keys,
                            receiver,
                            stopped,
                        ))
                    }));
                    if outcome.is_err() {
                        snapshot(&thread_session, "failed", 0);
                    }
                    runtime.shutdown_timeout(Duration::from_millis(250));
                }
                Err(_) => snapshot(&thread_session, "failed", 0),
            }
            thread_session.available.notify_all();
        })
        .map_err(|_| io("Could not start signaling worker"))?;
    *session
        .worker
        .lock()
        .map_err(|_| state("Signaling worker unavailable"))? = Some(worker);
    registry.insert(handle, session);
    Ok(handle)
}

pub fn status(handle: i64) -> Result<String> {
    let session = get(handle)?;
    let mut value = session
        .snapshot
        .lock()
        .map_err(|_| state("Signaling status unavailable"))?
        .clone();
    value["queued"] = json!(
        session
            .inbox
            .lock()
            .map_err(|_| state("Signaling inbox unavailable"))?
            .len()
    );
    Ok(value.to_string())
}

pub fn send(handle: i64, peer: &str, session_id: &str, kind: &str, raw: &str) -> Result<String> {
    if raw.len() > MAX_PAYLOAD {
        return Err(input("Signaling payload exceeds 8 KiB"));
    }
    let payload: Value =
        serde_json::from_str(raw).map_err(|_| input("Invalid signaling payload"))?;
    validate_message(session_id, kind, &payload)?;
    let peer = public_key(peer)?;
    let session = get(handle)?;
    if session.closed.load(Ordering::Acquire) {
        return Err(state("Signaling session closed"));
    }
    let (reply, receive) = std_mpsc::sync_channel(1);
    session
        .commands
        .try_send(Command {
            peer,
            session: session_id.to_owned(),
            kind: kind.to_owned(),
            payload,
            deadline: Instant::now() + SEND_TIMEOUT,
            reply,
        })
        .map_err(|_| state("Signaling is busy or stopped"))?;
    receive
        .recv_timeout(SEND_TIMEOUT + Duration::from_secs(1))
        .map_err(|_| io("Connection signal was not confirmed in time"))?
}

pub fn poll(handle: i64, timeout_ms: i32) -> Result<String> {
    if !(0..=1000).contains(&timeout_ms) {
        return Err(input("Invalid signaling poll timeout"));
    }
    let session = get(handle)?;
    let inbox = session
        .inbox
        .lock()
        .map_err(|_| state("Signaling inbox unavailable"))?;
    let (mut inbox, _) = session
        .available
        .wait_timeout_while(inbox, Duration::from_millis(timeout_ms as u64), |queue| {
            queue.is_empty() && !session.closed.load(Ordering::Acquire)
        })
        .map_err(|_| state("Signaling inbox unavailable"))?;
    let now = Timestamp::now().as_secs();
    inbox.retain(|item| {
        item["expires_at"]
            .as_u64()
            .is_some_and(|expiry| expiry > now)
    });
    let messages: Vec<Value> = inbox.drain(..).collect();
    Ok(json!(messages).to_string())
}

pub fn close(handle: i64) -> Result<()> {
    let session = sessions()
        .lock()
        .map_err(|_| state("Signaling registry unavailable"))?
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
        .map_err(|_| state("Signaling inbox unavailable"))?
        .clear();
    let mut worker = session
        .worker
        .lock()
        .map_err(|_| state("Signaling worker unavailable"))?;
    let deadline = Instant::now() + Duration::from_secs(2);
    if let Some(thread) = worker.as_ref() {
        while !thread.is_finished() && Instant::now() < deadline {
            std::thread::sleep(Duration::from_millis(10));
        }
        if !thread.is_finished() {
            return Err(io("Signaling is still shutting down"));
        }
    }
    if let Some(thread) = worker.take() {
        let _ = thread.join();
    }
    sessions()
        .lock()
        .map_err(|_| state("Signaling registry unavailable"))?
        .remove(&handle);
    Ok(())
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_app_bitchord_soulseek_NostrNative_generateSecret(
    mut env: JNIEnv,
    _: JClass,
) -> jstring {
    invoke(&mut env, |env| output(env, generate_secret()))
}
#[unsafe(no_mangle)]
pub extern "system" fn Java_app_bitchord_soulseek_NostrNative_publicKey(
    mut env: JNIEnv,
    _: JClass,
    secret: JString,
) -> jstring {
    invoke(&mut env, |env| {
        let secret = java_string(env, secret)?;
        output(env, derive_public(&secret)?)
    })
}
#[unsafe(no_mangle)]
pub extern "system" fn Java_app_bitchord_soulseek_NostrNative_open(
    mut env: JNIEnv,
    _: JClass,
    secret: JString,
    relays: JString,
    peers: JString,
) -> jlong {
    invoke(&mut env, |env| {
        let secret = java_string(env, secret)?;
        let relays = java_string(env, relays)?;
        let peers = java_string(env, peers)?;
        open(&secret, &relays, &peers)
    })
}
#[unsafe(no_mangle)]
pub extern "system" fn Java_app_bitchord_soulseek_NostrNative_status(
    mut env: JNIEnv,
    _: JClass,
    handle: jlong,
) -> jstring {
    invoke(&mut env, |env| output(env, status(handle)?))
}
#[unsafe(no_mangle)]
pub extern "system" fn Java_app_bitchord_soulseek_NostrNative_send(
    mut env: JNIEnv,
    _: JClass,
    handle: jlong,
    peer: JString,
    session: JString,
    kind: JString,
    payload: JString,
) -> jstring {
    invoke(&mut env, |env| {
        let peer = java_string(env, peer)?;
        let session = java_string(env, session)?;
        let kind = java_string(env, kind)?;
        let payload = java_string(env, payload)?;
        output(env, send(handle, &peer, &session, &kind, &payload)?)
    })
}
#[unsafe(no_mangle)]
pub extern "system" fn Java_app_bitchord_soulseek_NostrNative_poll(
    mut env: JNIEnv,
    _: JClass,
    handle: jlong,
    timeout_ms: jint,
) -> jstring {
    invoke(&mut env, |env| output(env, poll(handle, timeout_ms)?))
}
#[unsafe(no_mangle)]
pub extern "system" fn Java_app_bitchord_soulseek_NostrNative_close(
    mut env: JNIEnv,
    _: JClass,
    handle: jlong,
) {
    invoke(&mut env, |_| close(handle));
}

#[cfg(test)]
mod tests {
    use super::*;
    fn fixed(n: u8) -> Keys {
        Keys::parse(&format!("{n:064x}")).unwrap()
    }
    const SESSION: &str = "0123456789abcdef0123456789abcdef";
    fn event(sender: &Keys, recipient: &Keys, now: u64) -> Event {
        seal(
            sender,
            recipient.public_key(),
            SESSION,
            "offer",
            json!({"sdp":"test"}),
            now,
        )
        .unwrap()
    }
    #[test]
    fn authenticated_encrypted_roundtrip_replay_and_expiry() {
        let a = fixed(1);
        let b = fixed(2);
        let mut gate = Gate::new(HashSet::from([a.public_key()]));
        let e = event(&a, &b, 1000);
        let v = gate.accept(&b, &e, 1001).unwrap();
        assert_eq!(v["payload"]["sdp"], "test");
        assert!(!e.content.contains("test"));
        assert!(gate.accept(&b, &e, 1001).is_err());
        let mut gate = Gate::new(HashSet::from([a.public_key()]));
        assert!(gate.accept(&b, &e, 1120).is_err());
        assert!(gate.accept(&b, &event(&a, &b, 1200), 1000).is_err());
    }
    #[test]
    fn wrong_peer_recipient_tamper_and_unbound_expiry_are_rejected() {
        let a = fixed(1);
        let b = fixed(2);
        let c = fixed(3);
        let mut gate = Gate::new(HashSet::from([a.public_key()]));
        assert!(gate.accept(&b, &event(&c, &b, 1000), 1000).is_err());
        assert!(gate.accept(&b, &event(&a, &c, 1000), 1000).is_err());
        let mut e = event(&a, &b, 1000);
        e.content.push('A');
        assert!(gate.accept(&b, &e, 1000).is_err());
        let plain = json!({"protocol":PROTOCOL,"sender":a.public_key().to_hex(),"recipient":b.public_key().to_hex(),
            "session":SESSION,"type":"offer","created_at":1000,"expires_at":999999,"nonce":"a".repeat(64),"payload":{}});
        let encrypted = nip44::encrypt(
            a.secret_key(),
            &b.public_key(),
            plain.to_string(),
            nip44::Version::V2,
        )
        .unwrap();
        let e = EventBuilder::new(KIND, encrypted)
            .custom_created_at(Timestamp::from_secs(1000))
            .tags([
                Tag::public_key(b.public_key()),
                Tag::expiration(Timestamp::from_secs(1120)),
            ])
            .sign_with_keys(&a)
            .unwrap();
        assert!(gate.accept(&b, &e, 1000).is_err());
    }
    #[test]
    fn settings_and_messages_are_bounded_before_connecting() {
        let a = fixed(1);
        assert!(keys(&"0".repeat(64)).is_err());
        assert!(public_key(&"f".repeat(64)).is_err());
        for url in [
            "ws://relay.example",
            "https://relay.example",
            "wss://u:p@relay.example",
            "wss://relay.example?secret=x",
        ] {
            assert!(relays(&json!([url]).to_string(), false).is_err());
        }
        assert!(relays("[]", false).is_err());
        assert!(relays("[\"wss://relay.example\",\"wss://relay.example/\"]", false).is_err());
        assert!(relays("[\"wss://relay.example\"]", false).is_ok());
        assert!(
            peers(
                &json!([a.public_key().to_hex()]).to_string(),
                a.public_key()
            )
            .is_err()
        );
        assert!(
            validate_message(SESSION, "offer", &json!({"data":"x".repeat(MAX_PAYLOAD)})).is_err()
        );
        assert!(validate_message(SESSION, "unsupported", &json!({})).is_err());
        assert!(validate_message("invalid", "offer", &json!({})).is_err());
        assert!(validate_message(SESSION, "offer", &json!([])).is_err());
    }
    #[test]
    fn replay_memory_and_rate_are_bounded() {
        let a = fixed(1);
        let b = fixed(2);
        let mut gate = Gate::new(HashSet::from([a.public_key()]));
        for _ in 0..60 {
            assert!(gate.accept(&b, &event(&a, &b, 1000), 1000).is_ok());
        }
        assert!(gate.accept(&b, &event(&a, &b, 1000), 1000).is_err());
        assert!(gate.accept(&b, &event(&a, &b, 1060), 1060).is_ok());
        assert!(gate.seen.len() <= MAX_REPLAY);
    }

    #[test]
    fn duplicate_relay_deliveries_do_not_consume_the_peer_rate_budget() {
        let a = fixed(1);
        let b = fixed(2);
        let mut gate = Gate::new(HashSet::from([a.public_key()]));
        let first = event(&a, &b, 1000);
        gate.accept(&b, &first, 1000).unwrap();
        for _ in 0..100 {
            assert!(gate.accept(&b, &first, 1000).is_err());
        }
        assert!(gate.accept(&b, &event(&a, &b, 1000), 1000).is_ok());
        assert_eq!(gate.received[&a.public_key()].len(), 2);
    }

    struct LocalRelay {
        url: String,
        stopped: Arc<AtomicBool>,
        worker: Option<JoinHandle<()>>,
    }

    impl LocalRelay {
        fn start(acknowledge: bool) -> Self {
            let listener = std::net::TcpListener::bind("127.0.0.1:0").unwrap();
            let url = format!("ws://{}", listener.local_addr().unwrap());
            listener.set_nonblocking(true).unwrap();
            let stopped = Arc::new(AtomicBool::new(false));
            let stop = stopped.clone();
            let worker = std::thread::spawn(move || {
                let subscribers = Arc::new(Mutex::new(Vec::<std_mpsc::SyncSender<Value>>::new()));
                let mut workers = Vec::new();
                while !stop.load(Ordering::Acquire) {
                    match listener.accept() {
                        Ok((stream, _)) => {
                            let stop = stop.clone();
                            let subscribers = subscribers.clone();
                            workers.push(std::thread::spawn(move || {
                                stream
                                    .set_read_timeout(Some(Duration::from_millis(50)))
                                    .unwrap();
                                stream
                                    .set_write_timeout(Some(Duration::from_millis(500)))
                                    .unwrap();
                                let Ok(mut socket) = tokio_tungstenite::tungstenite::accept(stream)
                                else {
                                    return;
                                };
                                let (sender, events) = std_mpsc::sync_channel(32);
                                subscribers.lock().unwrap().push(sender);
                                let mut subscription = None::<String>;
                                while !stop.load(Ordering::Acquire) {
                                    while let Ok(event) = events.try_recv() {
                                        if let Some(id) = &subscription {
                                            if socket
                                                .send(WireMessage::Text(
                                                    json!(["EVENT", id, event]).to_string().into(),
                                                ))
                                                .is_err()
                                            {
                                                return;
                                            }
                                        }
                                    }
                                    match socket.read() {
                                        Ok(WireMessage::Text(text)) => {
                                            let data: Value = serde_json::from_str(&text).unwrap();
                                            match data[0].as_str() {
                                                Some("REQ") => {
                                                    subscription =
                                                        data[1].as_str().map(str::to_owned);
                                                    if socket
                                                        .send(WireMessage::Text(
                                                            json!(["EOSE", data[1]])
                                                                .to_string()
                                                                .into(),
                                                        ))
                                                        .is_err()
                                                    {
                                                        return;
                                                    }
                                                }
                                                Some("EVENT") => {
                                                    let event = data[1].clone();
                                                    if acknowledge
                                                        && socket
                                                            .send(WireMessage::Text(
                                                                json!([
                                                                    "OK",
                                                                    event["id"],
                                                                    true,
                                                                    ""
                                                                ])
                                                                .to_string()
                                                                .into(),
                                                            ))
                                                            .is_err()
                                                    {
                                                        return;
                                                    }
                                                    for receiver in
                                                        subscribers.lock().unwrap().iter()
                                                    {
                                                        let _ = receiver.try_send(event.clone());
                                                    }
                                                }
                                                _ => {}
                                            }
                                        }
                                        Ok(WireMessage::Ping(value)) => {
                                            let _ = socket.send(WireMessage::Pong(value));
                                        }
                                        Ok(WireMessage::Close(_)) => return,
                                        Err(tokio_tungstenite::tungstenite::Error::Io(error))
                                            if matches!(
                                                error.kind(),
                                                std::io::ErrorKind::WouldBlock
                                                    | std::io::ErrorKind::TimedOut
                                            ) => {}
                                        Err(_) => return,
                                        _ => {}
                                    }
                                }
                            }));
                        }
                        Err(error) if error.kind() == std::io::ErrorKind::WouldBlock => {
                            std::thread::sleep(Duration::from_millis(5))
                        }
                        Err(_) => break,
                    }
                }
                for worker in workers {
                    worker.join().unwrap();
                }
            });
            Self {
                url,
                stopped,
                worker: Some(worker),
            }
        }
        fn open(&self, own: &Keys, peer: &Keys) -> i64 {
            open_inner(
                &own.secret_key().to_secret_hex(),
                &json!([self.url]).to_string(),
                &json!([peer.public_key().to_hex()]).to_string(),
                true,
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
    fn wait_connected(handle: i64) {
        let deadline = Instant::now() + Duration::from_secs(4);
        loop {
            let value: Value = serde_json::from_str(&status(handle).unwrap()).unwrap();
            if value["connected_relays"] == 1 {
                return;
            }
            assert!(Instant::now() < deadline, "local relay did not connect");
            std::thread::sleep(Duration::from_millis(20));
        }
    }

    #[test]
    fn two_sessions_exchange_encrypted_offers_through_a_local_relay() {
        let relay = LocalRelay::start(true);
        let a = fixed(10);
        let b = fixed(11);
        let ah = relay.open(&a, &b);
        let bh = relay.open(&b, &a);
        wait_connected(ah);
        wait_connected(bh);
        let sent: Value = serde_json::from_str(
            &send(
                ah,
                &b.public_key().to_hex(),
                SESSION,
                "offer",
                "{\"sdp\":\"local-offer\"}",
            )
            .unwrap(),
        )
        .unwrap();
        assert_eq!(sent["accepted_relays"], 1);
        let received: Value = serde_json::from_str(&poll(bh, 1000).unwrap()).unwrap();
        assert_eq!(received.as_array().unwrap().len(), 1);
        assert_eq!(received[0]["payload"]["sdp"], "local-offer");
        assert_eq!(received[0]["peer"], a.public_key().to_hex());
        send(
            bh,
            &a.public_key().to_hex(),
            SESSION,
            "answer",
            "{\"sdp\":\"local-answer\"}",
        )
        .unwrap();
        let received: Value = serde_json::from_str(&poll(ah, 1000).unwrap()).unwrap();
        assert_eq!(received[0]["type"], "answer");
        close(ah).unwrap();
        close(ah).unwrap();
        close(bh).unwrap();
        assert!(send(ah, &b.public_key().to_hex(), SESSION, "ping", "{}").is_err());
    }

    #[test]
    fn closing_cancels_an_unacknowledged_send_and_wakes_pollers() {
        let relay = LocalRelay::start(false);
        let a = fixed(12);
        let b = fixed(13);
        let handle = relay.open(&a, &b);
        wait_connected(handle);
        let sending = std::thread::spawn(move || {
            send(handle, &b.public_key().to_hex(), SESSION, "ping", "{}")
        });
        let polling = std::thread::spawn(move || poll(handle, 1000));
        std::thread::sleep(Duration::from_millis(100));
        let started = Instant::now();
        close(handle).unwrap();
        assert!(started.elapsed() < Duration::from_secs(2));
        assert!(sending.join().unwrap().is_err());
        assert_eq!(polling.join().unwrap().unwrap(), "[]");
    }
}
