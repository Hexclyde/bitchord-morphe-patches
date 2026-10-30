//! Bounded relay qualification using encrypted events addressed only to this app identity.

use crate::{Result, input, invoke, io, java_string, nostr, output, state};
use futures_util::{SinkExt, StreamExt};
use jni::JNIEnv;
use jni::objects::{JClass, JString};
use jni::sys::{jint, jlong, jstring};
use nostr_sdk::prelude::*;
use serde_json::{Value, json};
use std::collections::HashMap;
use std::sync::atomic::{AtomicBool, AtomicI64, AtomicUsize, Ordering};
use std::sync::{Arc, Condvar, Mutex, OnceLock};
use std::time::{Duration, Instant};
use tokio::sync::watch;
use tokio_tungstenite::tungstenite::Message;

const MAX_SESSIONS: usize = 2;
const MAX_CONCURRENT: usize = 2;
const MAX_MESSAGES: usize = 96;
const SAMPLE_COUNT: usize = 3;
static NEXT: AtomicI64 = AtomicI64::new(1);
static ACTIVE: AtomicUsize = AtomicUsize::new(0);
static CHECKS: OnceLock<Mutex<HashMap<i64, Arc<Check>>>> = OnceLock::new();

type Socket =
    tokio_tungstenite::WebSocketStream<tokio_tungstenite::MaybeTlsStream<tokio::net::TcpStream>>;

struct Check {
    keys: Keys,
    closed: AtomicBool,
    cancel: watch::Sender<bool>,
    active: Mutex<usize>,
    idle: Condvar,
}

struct Operation(Arc<Check>);

impl Drop for Operation {
    fn drop(&mut self) {
        if let Ok(mut active) = self.0.active.lock() {
            *active = active.saturating_sub(1);
            ACTIVE.fetch_sub(1, Ordering::AcqRel);
            self.0.idle.notify_all();
        }
    }
}

#[derive(Clone, Copy)]
enum Phase {
    Connecting,
    Subscribing,
    RoundTrip,
}

struct Observation {
    online: bool,
    connect_ms: i64,
    roundtrips: Vec<i64>,
    phase: Phase,
}

impl Observation {
    fn new() -> Self {
        Self {
            online: false,
            connect_ms: -1,
            roundtrips: Vec::with_capacity(SAMPLE_COUNT),
            phase: Phase::Connecting,
        }
    }

    fn timeout(&self) -> &'static str {
        match self.phase {
            Phase::Connecting => "connect_timeout",
            Phase::Subscribing => "read_timeout",
            Phase::RoundTrip => "roundtrip_timeout",
        }
    }

    fn response(&self, relay: &str, status: &str, elapsed: Duration) -> String {
        let mut sorted = self.roundtrips.clone();
        sorted.sort_unstable();
        let median = if sorted.is_empty() {
            -1
        } else {
            sorted[sorted.len() / 2]
        };
        json!({
            "relay": relay,
            "status": status,
            "online": self.online,
            "usable": status == "ok" && self.roundtrips.len() == SAMPLE_COUNT,
            "connect_ms": self.connect_ms,
            "roundtrip_ms": median,
            "completed_ms": elapsed.as_millis().min(i64::MAX as u128) as i64,
            "samples": self.roundtrips.len()
        })
        .to_string()
    }
}

fn registry() -> &'static Mutex<HashMap<i64, Arc<Check>>> {
    CHECKS.get_or_init(|| Mutex::new(HashMap::new()))
}

fn get(handle: i64) -> Result<Arc<Check>> {
    registry()
        .lock()
        .map_err(|_| state("Relay check registry unavailable"))?
        .get(&handle)
        .cloned()
        .ok_or_else(|| state("Relay check is closed"))
}

pub fn create(secret: &str) -> Result<i64> {
    let keys = nostr::keys(secret)?;
    let mut registry = registry()
        .lock()
        .map_err(|_| state("Relay check registry unavailable"))?;
    if registry.len() >= MAX_SESSIONS {
        return Err(state("Too many relay checks"));
    }
    let id = NEXT.fetch_add(1, Ordering::Relaxed);
    if id <= 0 {
        return Err(state("Relay check identifiers exhausted"));
    }
    let (cancel, _) = watch::channel(false);
    registry.insert(
        id,
        Arc::new(Check {
            keys,
            closed: AtomicBool::new(false),
            cancel,
            active: Mutex::new(0),
            idle: Condvar::new(),
        }),
    );
    Ok(id)
}

pub fn probe(handle: i64, relay: &str, timeout_ms: i32) -> Result<String> {
    probe_inner(handle, relay, timeout_ms, false)
}

fn probe_inner(handle: i64, relay: &str, timeout_ms: i32, local_test: bool) -> Result<String> {
    let minimum = if cfg!(test) && local_test { 100 } else { 2000 };
    if !(minimum..=15_000).contains(&timeout_ms) {
        return Err(input(
            "Relay check timeout must be between 2 and 15 seconds",
        ));
    }
    let urls = nostr::relays(&json!([relay]).to_string(), local_test)?;
    let relay = &urls[0];
    let url = Url::parse(relay).map_err(|_| input("Invalid relay address"))?;
    if url
        .host_str()
        .is_some_and(|host| host.ends_with(".onion") || host.ends_with(".i2p"))
    {
        return Err(input("This relay check requires a public WSS address"));
    }
    let check = get(handle)?;
    {
        let mut active = check
            .active
            .lock()
            .map_err(|_| state("Relay check unavailable"))?;
        if check.closed.load(Ordering::Acquire) {
            return Err(state("Relay check is closed"));
        }
        if *active >= MAX_CONCURRENT {
            return Err(state("Two relay checks are already running"));
        }
        ACTIVE
            .fetch_update(Ordering::AcqRel, Ordering::Acquire, |count| {
                (count < MAX_CONCURRENT).then_some(count + 1)
            })
            .map_err(|_| state("Two relay checks are already running"))?;
        *active += 1;
    }
    let operation = Operation(check);
    let mut cancel = operation.0.cancel.subscribe();
    let runtime = tokio::runtime::Builder::new_current_thread()
        .enable_all()
        .build()
        .map_err(|_| io("Could not start relay check"))?;
    let started = Instant::now();
    let mut observation = Observation::new();
    let status = runtime.block_on(async {
        if *cancel.borrow() || operation.0.closed.load(Ordering::Acquire) {
            return "cancelled";
        }
        let result = tokio::select! {
            biased;
            _ = cancel.changed() => None,
            result = tokio::time::timeout(Duration::from_millis(timeout_ms as u64),
                measure(&operation.0.keys, relay, started, &mut observation)) => Some(result),
        };
        match result {
            None => "cancelled",
            Some(Err(_)) => observation.timeout(),
            Some(Ok(status)) => status,
        }
    });
    runtime.shutdown_timeout(Duration::from_millis(250));
    Ok(observation.response(relay, status, started.elapsed()))
}

async fn receive(
    socket: &mut Socket,
    remaining: &mut usize,
) -> std::result::Result<Value, &'static str> {
    while *remaining > 0 {
        *remaining -= 1;
        match socket.next().await {
            Some(Ok(Message::Text(text))) => {
                let message: Value = serde_json::from_str(&text).map_err(|_| "protocol_error")?;
                if !message.is_array() {
                    return Err("protocol_error");
                }
                return Ok(message);
            }
            Some(Ok(Message::Ping(bytes))) => {
                socket
                    .send(Message::Pong(bytes))
                    .await
                    .map_err(|_| "disconnected")?;
            }
            Some(Ok(Message::Pong(_))) => {}
            Some(Ok(Message::Close(_))) | None => return Err("disconnected"),
            Some(Ok(_)) => return Err("protocol_error"),
            Some(Err(_)) => return Err("disconnected"),
        }
    }
    Err("protocol_error")
}

fn common_rejection(message: &Value, subscription: &str) -> Option<&'static str> {
    match message[0].as_str() {
        Some("AUTH") => Some("auth_required"),
        Some("CLOSED") if message[1].as_str() == Some(subscription) => Some("read_rejected"),
        _ => None,
    }
}

async fn measure(
    keys: &Keys,
    relay: &str,
    started: Instant,
    observed: &mut Observation,
) -> &'static str {
    let config = tokio_tungstenite::tungstenite::protocol::WebSocketConfig::default()
        .read_buffer_size(8192)
        .write_buffer_size(8192)
        .max_write_buffer_size(65_536)
        .max_message_size(Some(32_768))
        .max_frame_size(Some(32_768));
    let Ok((mut socket, _)) =
        tokio_tungstenite::connect_async_with_config(relay, Some(config), true).await
    else {
        return "offline";
    };
    observed.online = true;
    observed.connect_ms = started.elapsed().as_millis().min(i64::MAX as u128) as i64;
    observed.phase = Phase::Subscribing;
    let subscription = Keys::generate().secret_key().to_secret_hex();
    let own = keys.public_key().to_hex();
    let req = json!(["REQ", subscription,
        {"kinds":[21118], "authors":[own], "#p":[own], "limit":0,
         "since":Timestamp::now().as_secs()}]);
    if socket
        .send(Message::Text(req.to_string().into()))
        .await
        .is_err()
    {
        return "disconnected";
    }
    let mut remaining = MAX_MESSAGES;
    loop {
        let message = match receive(&mut socket, &mut remaining).await {
            Ok(message) => message,
            Err(status) => return status,
        };
        if let Some(status) = common_rejection(&message, &subscription) {
            return status;
        }
        if message[0] == "EOSE" && message[1].as_str() == Some(subscription.as_str()) {
            break;
        }
    }
    observed.phase = Phase::RoundTrip;
    for _ in 0..SAMPLE_COUNT {
        let nonce = Keys::generate().secret_key().to_secret_hex();
        let session = &nonce[..32];
        let event = match nostr::seal(
            keys,
            keys.public_key(),
            session,
            "ping",
            json!({"relay_probe":nonce}),
            Timestamp::now().as_secs(),
        ) {
            Ok(event) => event,
            Err(_) => return "protocol_error",
        };
        let roundtrip_started = Instant::now();
        if socket
            .send(Message::Text(json!(["EVENT", event]).to_string().into()))
            .await
            .is_err()
        {
            return "disconnected";
        }
        let mut acknowledged = false;
        let mut returned = false;
        while !acknowledged || !returned {
            let message = match receive(&mut socket, &mut remaining).await {
                Ok(message) => message,
                Err(status) => return status,
            };
            if let Some(status) = common_rejection(&message, &subscription) {
                return status;
            }
            if message[0] == "OK" && message[1].as_str() == Some(event.id.to_hex().as_str()) {
                if message[2].as_bool() != Some(true) {
                    return "write_rejected";
                }
                acknowledged = true;
            } else if message[0] == "EVENT" && message[1].as_str() == Some(subscription.as_str()) {
                if let Ok(received) = serde_json::from_value::<Event>(message[2].clone()) {
                    if received.id == event.id
                        && received.pubkey == keys.public_key()
                        && received.verify().is_ok()
                    {
                        if let Ok(plain) =
                            nip44::decrypt(keys.secret_key(), &keys.public_key(), &received.content)
                        {
                            if let Ok(envelope) = serde_json::from_str::<Value>(&plain) {
                                returned = envelope["session"] == session
                                    && envelope["payload"]["relay_probe"] == nonce;
                            }
                        }
                    }
                }
            }
        }
        observed.roundtrips.push(
            roundtrip_started
                .elapsed()
                .as_millis()
                .min(i64::MAX as u128) as i64,
        );
    }
    let _ = socket.close(None).await;
    "ok"
}

pub fn close(handle: i64) -> Result<()> {
    let check = registry()
        .lock()
        .map_err(|_| state("Relay check registry unavailable"))?
        .get(&handle)
        .cloned();
    let Some(check) = check else {
        return Ok(());
    };
    check.closed.store(true, Ordering::Release);
    let _ = check.cancel.send(true);
    let active = check
        .active
        .lock()
        .map_err(|_| state("Relay check unavailable"))?;
    let (active, _) = check
        .idle
        .wait_timeout_while(active, Duration::from_secs(2), |active| *active > 0)
        .map_err(|_| state("Relay check unavailable"))?;
    if *active != 0 {
        return Err(io("Relay checks are still stopping"));
    }
    drop(active);
    registry()
        .lock()
        .map_err(|_| state("Relay check registry unavailable"))?
        .remove(&handle);
    Ok(())
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_app_bitchord_soulseek_RelayProbeNative_create(
    mut env: JNIEnv,
    _: JClass,
    secret: JString,
) -> jlong {
    invoke(&mut env, |env| {
        let secret = java_string(env, secret)?;
        create(&secret)
    })
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_app_bitchord_soulseek_RelayProbeNative_probe(
    mut env: JNIEnv,
    _: JClass,
    handle: jlong,
    relay: JString,
    timeout_ms: jint,
) -> jstring {
    invoke(&mut env, |env| {
        let relay = java_string(env, relay)?;
        output(env, probe(handle, &relay, timeout_ms)?)
    })
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_app_bitchord_soulseek_RelayProbeNative_close(
    mut env: JNIEnv,
    _: JClass,
    handle: jlong,
) {
    invoke(&mut env, |_| close(handle));
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::sync::mpsc;
    use std::thread::{self, JoinHandle};

    static SERIAL: Mutex<()> = Mutex::new(());

    #[derive(Clone, Copy)]
    enum Behavior {
        Good,
        ReadWait,
        ReadReject,
        Auth,
        WriteReject,
        AckOnly,
        WrongEvent,
        Tamper,
        NoAck,
    }

    struct Relay {
        url: String,
        stop: Arc<AtomicBool>,
        requested: mpsc::Receiver<()>,
        worker: Option<JoinHandle<()>>,
    }

    impl Relay {
        fn start(behavior: Behavior) -> Self {
            let listener = std::net::TcpListener::bind("127.0.0.1:0").unwrap();
            listener.set_nonblocking(true).unwrap();
            let url = format!("ws://{}", listener.local_addr().unwrap());
            let stop = Arc::new(AtomicBool::new(false));
            let stopped = stop.clone();
            let (request, requested) = mpsc::channel();
            let worker = thread::spawn(move || {
                let mut children = Vec::new();
                while !stopped.load(Ordering::Acquire) {
                    match listener.accept() {
                        Ok((stream, _)) => {
                            let stopped = stopped.clone();
                            let request = request.clone();
                            children.push(thread::spawn(move || {
                                stream.set_read_timeout(Some(Duration::from_millis(30))).unwrap();
                                stream.set_write_timeout(Some(Duration::from_millis(100))).unwrap();
                                let Ok(mut socket) = tokio_tungstenite::tungstenite::accept(stream) else { return; };
                                let mut subscription = String::new();
                                while !stopped.load(Ordering::Acquire) {
                                    match socket.read() {
                                        Ok(Message::Text(text)) => {
                                            let message: Value = serde_json::from_str(&text).unwrap();
                                            let reply = match message[0].as_str() {
                                                Some("REQ") => {
                                                    subscription = message[1].as_str().unwrap().to_owned();
                                                    let _ = request.send(());
                                                    match behavior {
                                                        Behavior::ReadWait => continue,
                                                        Behavior::ReadReject => json!(["CLOSED",subscription,"blocked"]),
                                                        Behavior::Auth => json!(["AUTH","challenge"]),
                                                        _ => json!(["EOSE", subscription]),
                                                    }
                                                }
                                                Some("EVENT") => {
                                                    let mut event = message[1].clone();
                                                    if matches!(behavior, Behavior::WriteReject) {
                                                        json!(["OK", event["id"], false, "blocked"])
                                                    } else {
                                                        if !matches!(behavior, Behavior::NoAck) {
                                                            if socket.send(Message::Text(json!(["OK",event["id"],true,""]).to_string().into())).is_err() { return; }
                                                        }
                                                        if matches!(behavior, Behavior::AckOnly) { continue; }
                                                        if matches!(behavior, Behavior::WrongEvent) {
                                                            let keys = Keys::parse(&format!("{:064x}",1)).unwrap();
                                                            event = serde_json::to_value(nostr::seal(&keys,keys.public_key(),"aabbccddaabbccddaabbccddaabbccdd","ping",json!({"relay_probe":"different"}),Timestamp::now().as_secs()).unwrap()).unwrap();
                                                        } else if matches!(behavior, Behavior::Tamper) {
                                                            event["sig"] = json!("0".repeat(128));
                                                        }
                                                        json!(["EVENT",subscription,event])
                                                    }
                                                }
                                                _ => continue,
                                            };
                                            if socket.send(Message::Text(reply.to_string().into())).is_err() { return; }
                                        }
                                        Ok(Message::Ping(bytes)) => { let _ = socket.send(Message::Pong(bytes)); }
                                        Ok(Message::Close(_)) => return,
                                        Err(tokio_tungstenite::tungstenite::Error::Io(error))
                                            if matches!(error.kind(),std::io::ErrorKind::WouldBlock|std::io::ErrorKind::TimedOut) => {},
                                        Err(_) => return,
                                        _ => {},
                                    }
                                }
                            }));
                        }
                        Err(error) if error.kind() == std::io::ErrorKind::WouldBlock => {
                            thread::sleep(Duration::from_millis(2))
                        }
                        Err(_) => break,
                    }
                }
                for child in children {
                    child.join().unwrap();
                }
            });
            Self {
                url,
                stop,
                requested,
                worker: Some(worker),
            }
        }
    }

    impl Drop for Relay {
        fn drop(&mut self) {
            self.stop.store(true, Ordering::Release);
            if let Some(worker) = self.worker.take() {
                worker.join().unwrap();
            }
        }
    }

    fn identity() -> String {
        format!("{:064x}", 1)
    }

    fn checked(behavior: Behavior, timeout: i32) -> Value {
        let _serial = SERIAL.lock().unwrap_or_else(|error| error.into_inner());
        let relay = Relay::start(behavior);
        let handle = create(&identity()).unwrap();
        let result = probe_inner(handle, &relay.url, timeout, true).unwrap();
        close(handle).unwrap();
        serde_json::from_str(&result).unwrap()
    }

    #[test]
    fn three_authenticated_self_loopbacks_qualify_the_relay() {
        let result = checked(Behavior::Good, 2000);
        assert_eq!(result["status"], "ok");
        assert_eq!(result["online"], true);
        assert_eq!(result["usable"], true);
        assert_eq!(result["samples"], 3);
        assert!(result["connect_ms"].as_i64().unwrap() >= 0);
        assert!(result["roundtrip_ms"].as_i64().unwrap() >= 0);
    }

    #[test]
    fn an_open_socket_does_not_establish_nostr_usability() {
        let result = checked(Behavior::ReadWait, 200);
        assert_eq!(result["status"], "read_timeout");
        assert_eq!(result["online"], true);
        assert_eq!(result["usable"], false);
        assert_eq!(result["samples"], 0);
    }

    #[test]
    fn read_auth_and_write_restrictions_are_distinct() {
        for (behavior, status) in [
            (Behavior::ReadReject, "read_rejected"),
            (Behavior::Auth, "auth_required"),
            (Behavior::WriteReject, "write_rejected"),
        ] {
            let result = checked(behavior, 2000);
            assert_eq!(result["status"], status);
            assert_eq!(result["online"], true);
            assert_eq!(result["usable"], false);
            assert_eq!(result["samples"], 0);
        }
    }

    #[test]
    fn ack_without_delivery_or_wrong_signature_nonce_does_not_qualify() {
        for behavior in [
            Behavior::AckOnly,
            Behavior::WrongEvent,
            Behavior::Tamper,
            Behavior::NoAck,
        ] {
            let result = checked(behavior, 200);
            assert_eq!(result["status"], "roundtrip_timeout");
            assert_eq!(result["usable"], false);
            assert_eq!(result["samples"], 0);
        }
    }

    #[test]
    fn invalid_configuration_is_rejected_before_any_probe() {
        let _serial = SERIAL.lock().unwrap_or_else(|error| error.into_inner());
        assert!(create(&"0".repeat(64)).is_err());
        let handle = create(&identity()).unwrap();
        for url in [
            "ws://relay.example",
            "wss://localhost",
            "wss://relay.example:444",
            "wss://user@relay.example",
            "wss://relay.example?token=secret",
            "wss://relay.onion",
            "wss://relay.i2p",
        ] {
            assert!(probe(handle, url, 2000).is_err());
        }
        assert!(probe(handle, "wss://relay.example", 1999).is_err());
        assert!(probe(handle, "wss://relay.example", 15001).is_err());
        close(handle).unwrap();
        close(handle).unwrap();
        assert!(probe(handle, "wss://relay.example", 2000).is_err());
    }

    #[test]
    fn close_cancels_concurrent_probes_and_waits_for_socket_teardown() {
        let _serial = SERIAL.lock().unwrap_or_else(|error| error.into_inner());
        let relay = Relay::start(Behavior::ReadWait);
        let handle = create(&identity()).unwrap();
        let mut workers = Vec::new();
        for _ in 0..2 {
            let url = relay.url.clone();
            workers.push(thread::spawn(move || {
                probe_inner(handle, &url, 15_000, true).unwrap()
            }));
        }
        for _ in 0..2 {
            relay
                .requested
                .recv_timeout(Duration::from_secs(2))
                .unwrap();
        }
        assert!(probe_inner(handle, &relay.url, 200, true).is_err());
        let started = Instant::now();
        close(handle).unwrap();
        assert!(started.elapsed() < Duration::from_secs(2));
        for worker in workers {
            let result: Value = serde_json::from_str(&worker.join().unwrap()).unwrap();
            assert_eq!(result["status"], "cancelled");
        }
        assert!(get(handle).is_err());
    }
}
