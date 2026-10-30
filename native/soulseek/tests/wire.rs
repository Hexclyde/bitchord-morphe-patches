//! Exercises the adapter against real loopback Soulseek frames, not a mock engine.
use bitchord_soulseek::{close, connect, create, download, search, transfers};
use serde_json::Value;
use soulseek_rs::message::Message;
use soulseek_rs::message::peer::{FileEntry, build_file_search_response};
use std::fs;
use std::io::{self, Read, Write};
use std::net::{Shutdown, TcpListener, TcpStream};
use std::path::PathBuf;
use std::sync::atomic::{AtomicBool, AtomicU64, Ordering};
use std::sync::{Arc, Mutex};
use std::thread::{self, JoinHandle};
use std::time::{Duration, Instant};

const USER: &str = "fixture-user";
const PASSWORD: &str = "fixture-password-not-a-real-account";
const PEER: &str = "fixture-peer";
const AUDIO_PATH: &str = "Fixture\\Café\\original.flac";
static NEXT_TEST: AtomicU64 = AtomicU64::new(1);
static TEST_LOCK: Mutex<()> = Mutex::new(());

struct Frames(Vec<u8>);
impl Frames {
    fn next(&mut self, socket: &mut TcpStream) -> io::Result<Option<Message>> {
        if self.0.len() >= 4 {
            let size = u32::from_le_bytes(self.0[..4].try_into().unwrap()) as usize;
            if size > 1024 * 1024 {
                return Err(io::Error::new(
                    io::ErrorKind::InvalidData,
                    "oversized fixture frame",
                ));
            }
            if self.0.len() >= size + 4 {
                let data: Vec<u8> = self.0.drain(..size + 4).collect();
                return Ok(Some(Message::new_with_data(data)));
            }
        }
        let mut bytes = [0; 8192];
        match socket.read(&mut bytes) {
            Ok(0) => Err(io::ErrorKind::UnexpectedEof.into()),
            Ok(n) => {
                self.0.extend_from_slice(&bytes[..n]);
                Ok(None)
            }
            Err(e)
                if matches!(
                    e.kind(),
                    io::ErrorKind::WouldBlock | io::ErrorKind::TimedOut
                ) =>
            {
                Ok(None)
            }
            Err(e) => Err(e),
        }
    }
}

fn init(peer: &str, kind: &str) -> Message {
    Message::new()
        .write_int8(1)
        .write_string(peer)
        .write_string(kind)
        .write_int32(0)
        .clone()
}

fn send(socket: &mut TcpStream, message: &Message) -> io::Result<()> {
    socket.write_all(&message.get_buffer())
}

struct Fixture {
    port: u16,
    stopped: Arc<AtomicBool>,
    server: Option<JoinHandle<()>>,
    peers: Arc<Mutex<Vec<JoinHandle<()>>>>,
}
impl Fixture {
    fn start(accept_login: bool, file: Option<Vec<u8>>) -> Self {
        let listener = TcpListener::bind(("127.0.0.1", 0)).unwrap();
        let port = listener.local_addr().unwrap().port();
        listener.set_nonblocking(true).unwrap();
        let stopped = Arc::new(AtomicBool::new(false));
        let peers = Arc::new(Mutex::new(Vec::new()));
        let stop = stopped.clone();
        let peers_for_server = peers.clone();
        let server = thread::spawn(move || {
            let mut socket = loop {
                if stop.load(Ordering::SeqCst) {
                    return;
                }
                match listener.accept() {
                    Ok((socket, _)) => break socket,
                    Err(e) if e.kind() == io::ErrorKind::WouldBlock => {
                        thread::sleep(Duration::from_millis(5))
                    }
                    Err(e) => panic!("fixture accept: {e}"),
                }
            };
            socket
                .set_read_timeout(Some(Duration::from_millis(50)))
                .unwrap();
            socket
                .set_write_timeout(Some(Duration::from_secs(2)))
                .unwrap();
            let mut frames = Frames(Vec::new());
            let mut phone_port = None;
            let started = Instant::now();
            while !stop.load(Ordering::SeqCst) && started.elapsed() < Duration::from_secs(20) {
                let mut frame = match frames.next(&mut socket) {
                    Ok(Some(frame)) => frame,
                    Ok(None) => continue,
                    Err(_) => break,
                };
                frame.set_pointer(4);
                match frame.read_int32() {
                    1 => {
                        assert_eq!(frame.read_string(), USER);
                        assert_eq!(frame.read_string(), PASSWORD);
                        let reply = Message::new()
                            .write_int32(1)
                            .write_bool(accept_login)
                            .write_string(if accept_login {
                                "local test"
                            } else {
                                "INVALIDPASS"
                            })
                            .clone();
                        send(&mut socket, &reply).unwrap();
                    }
                    2 => phone_port = Some(frame.read_int32() as u16),
                    26 => {
                        let token = frame.read_int32();
                        assert_eq!(frame.read_string(), "fixture song");
                        let phone_port = phone_port.expect("listener advertised before search");
                        let stop_peer = stop.clone();
                        let peer_file = file.clone();
                        peers_for_server
                            .lock()
                            .unwrap()
                            .push(thread::spawn(move || {
                                serve_peer(phone_port, token, peer_file, stop_peer);
                            }));
                    }
                    _ => {} // Shared counts, online status and ordinary setup frames.
                }
            }
            let _ = socket.shutdown(Shutdown::Both);
        });
        Self {
            port,
            stopped,
            server: Some(server),
            peers,
        }
    }
}
impl Drop for Fixture {
    fn drop(&mut self) {
        self.stopped.store(true, Ordering::SeqCst);
        if let Some(server) = self.server.take() {
            server.join().unwrap();
        }
        for peer in self.peers.lock().unwrap().drain(..) {
            peer.join().unwrap();
        }
    }
}

fn serve_peer(phone_port: u16, search_token: u32, file: Option<Vec<u8>>, stop: Arc<AtomicBool>) {
    let mut socket = TcpStream::connect(("127.0.0.1", phone_port)).unwrap();
    socket
        .set_read_timeout(Some(Duration::from_millis(50)))
        .unwrap();
    socket
        .set_write_timeout(Some(Duration::from_secs(2)))
        .unwrap();
    send(&mut socket, &init(PEER, "P")).unwrap();
    let attributes = [(0, 1000), (1, 180), (4, 44100), (5, 16)];
    let entries = [
        FileEntry {
            name: AUDIO_PATH,
            size: file.as_ref().map_or(393_216, |f| f.len()) as u64,
            attribs: &attributes,
        },
        FileEntry {
            name: "Fixture\\second.MP3",
            size: 300_000,
            attribs: &[(0, 320), (1, 180)],
        },
        FileEntry {
            name: "Fixture\\installer.exe",
            size: 4000,
            attribs: &[],
        },
        FileEntry {
            name: "Fixture\\ambiguous-audio-container.mp4",
            size: 500_000,
            attribs: &[],
        },
        FileEntry {
            name: "Fixture\\video.mkv",
            size: 500_000,
            attribs: &[],
        },
        FileEntry {
            name: "Fixture\\dsd.DSF",
            size: 500_000,
            attribs: &[],
        },
        FileEntry {
            name: "Fixture\\dsd.dff",
            size: 500_000,
            attribs: &[],
        },
        FileEntry {
            name: "Fixture\\audio.OGA",
            size: 500_000,
            attribs: &[],
        },
        FileEntry {
            name: "Fixture\\audio.vorbis",
            size: 500_000,
            attribs: &[],
        },
        FileEntry {
            name: "Fixture\\audio.ALAC",
            size: 500_000,
            attribs: &[],
        },
    ];
    send(
        &mut socket,
        &build_file_search_response(PEER, search_token, &entries, 1, 1_000_000, 0),
    )
    .unwrap();
    let mut frames = Frames(Vec::new());
    let started = Instant::now();
    let mut next_transfer_token = 0x1234_5678u32;
    while !stop.load(Ordering::SeqCst) && started.elapsed() < Duration::from_secs(15) {
        let mut frame = match frames.next(&mut socket) {
            Ok(Some(frame)) => frame,
            Ok(None) => continue,
            Err(_) => break,
        };
        frame.set_pointer(4);
        match frame.read_int32() {
            43 => {
                assert_eq!(frame.read_string(), AUDIO_PATH);
                send(
                    &mut socket,
                    &Message::new()
                        .write_int32(44)
                        .write_string(AUDIO_PATH)
                        .write_int32(7)
                        .clone(),
                )
                .unwrap();
                if let Some(data) = &file {
                    let transfer_token = next_transfer_token;
                    next_transfer_token += 1;
                    send(
                        &mut socket,
                        &Message::new()
                            .write_int32(40)
                            .write_int32(1)
                            .write_int32(transfer_token)
                            .write_string(AUDIO_PATH)
                            .write_int64(data.len() as u64)
                            .clone(),
                    )
                    .unwrap();
                }
            }
            41 => {
                let token = frame.read_int32();
                assert_eq!(
                    frame.read_int8(),
                    1,
                    "native accepts the advertised transfer"
                );
                if let Some(data) = &file {
                    let mut stream = TcpStream::connect(("127.0.0.1", phone_port)).unwrap();
                    stream
                        .set_read_timeout(Some(Duration::from_secs(3)))
                        .unwrap();
                    stream
                        .set_write_timeout(Some(Duration::from_secs(3)))
                        .unwrap();
                    send(&mut stream, &init(PEER, "F")).unwrap();
                    stream.write_all(&token.to_le_bytes()).unwrap();
                    let mut offset = [0; 8];
                    stream.read_exact(&mut offset).unwrap();
                    let offset = u64::from_le_bytes(offset) as usize;
                    assert!(offset <= data.len());
                    let middle = offset + (data.len() - offset) / 2;
                    stream.write_all(&data[offset..middle]).unwrap();
                    thread::sleep(Duration::from_millis(400));
                    // Cancellation/close deliberately interrupts this fixture transfer.
                    let _ = stream.write_all(&data[middle..]);
                    // Downloader closes after receiving exactly the advertised byte count.
                    let mut trailing = [0; 1];
                    let _ = stream.read(&mut trailing);
                }
            }
            _ => {}
        }
    }
    let _ = socket.shutdown(Shutdown::Both);
}

struct Session {
    handle: i64,
    home: PathBuf,
}
impl Session {
    fn new(port: u16) -> Self {
        let home = std::env::temp_dir().join(format!(
            "bitchord-wire-{}-{}",
            std::process::id(),
            NEXT_TEST.fetch_add(1, Ordering::SeqCst)
        ));
        fs::create_dir_all(&home).unwrap();
        let handle = create(home.to_str().unwrap(), "127.0.0.1", i32::from(port), 0).unwrap();
        Self { handle, home }
    }
    fn login(&self) {
        connect(self.handle, USER, PASSWORD).unwrap();
    }
}
impl Drop for Session {
    fn drop(&mut self) {
        let _ = close(self.handle);
        let _ = fs::remove_dir_all(&self.home);
    }
}

#[test]
fn rejected_login_does_not_return_success_or_expose_password() {
    let _serial = TEST_LOCK.lock().unwrap();
    let server = Fixture::start(false, None);
    let session = Session::new(server.port);
    let error =
        connect(session.handle, USER, PASSWORD).expect_err("login rejected by local server");
    assert!(!error.contains(PASSWORD));
    assert!(
        search(session.handle, "fixture song", 100).is_err(),
        "failed login cannot search"
    );
}

#[test]
fn real_compressed_peer_results_keep_audio_candidates_aliases_and_metadata() {
    let _serial = TEST_LOCK.lock().unwrap();
    let server = Fixture::start(true, None);
    let session = Session::new(server.port);
    session.login();
    let response: Value =
        serde_json::from_str(&search(session.handle, "fixture song", 1200).unwrap()).unwrap();
    let tracks = response["tracks"].as_array().unwrap();
    assert_eq!(
        tracks.len(),
        8,
        "audio containers reach byte validation; unsupported video and executables are excluded"
    );
    let flac = tracks
        .iter()
        .find(|row| row["path"] == AUDIO_PATH)
        .expect("Unicode path survives wire decoding");
    assert_eq!(flac["peer"], PEER);
    assert_eq!(flac["size"], 393_216);
    assert_eq!(flac["duration"], 180);
    assert_eq!(flac["sampleRate"], 44100);
    assert_eq!(flac["bitDepth"], 16);
    assert!(!flac["id"].as_str().unwrap().is_empty());
    for alias in ["mp4", "dsf", "dff", "oga", "vorbis", "alac"] {
        assert!(
            tracks.iter().any(|row| row["extension"] == alias),
            "wire search must retain {alias}"
        );
    }
    assert!(
        !tracks
            .iter()
            .any(|row| row["path"].as_str().unwrap().ends_with(".mkv")
                || row["path"].as_str().unwrap().ends_with(".exe"))
    );
    let container = tracks.iter().find(|row| row["extension"] == "mp4").unwrap();
    assert_eq!(
        container["format"], "mp4",
        "extension hints must not claim ALAC or AAC without inspecting bytes"
    );
}

#[test]
fn queued_peer_transfer_exposes_partial_bytes_then_complete_original() {
    let _serial = TEST_LOCK.lock().unwrap();
    let mut bytes = vec![0u8; 393_216];
    bytes[..4].copy_from_slice(b"fLaC");
    for (i, byte) in bytes.iter_mut().enumerate().skip(4) {
        *byte = (i % 251) as u8;
    }
    let server = Fixture::start(true, Some(bytes.clone()));
    let session = Session::new(server.port);
    session.login();
    let _: Value =
        serde_json::from_str(&search(session.handle, "fixture song", 1200).unwrap()).unwrap();
    let initial: Value = serde_json::from_str(
        &download(session.handle, PEER, AUDIO_PATH, bytes.len() as i64).unwrap(),
    )
    .unwrap();
    let id = initial["id"]
        .as_str()
        .expect("opaque transfer id")
        .to_owned();
    let deadline = Instant::now() + Duration::from_secs(8);
    let mut observed_partial = false;
    loop {
        let response: Value = serde_json::from_str(&transfers(session.handle).unwrap()).unwrap();
        let row = response["transfers"]
            .as_array()
            .unwrap()
            .iter()
            .find(|row| row["id"] == id)
            .unwrap();
        let downloaded = row["downloaded"].as_u64().unwrap();
        if downloaded > 0 && downloaded < bytes.len() as u64 {
            observed_partial = true;
        }
        if row["complete"] == true {
            assert!(
                observed_partial,
                "reader can observe bytes before completion"
            );
            assert_eq!(downloaded, bytes.len() as u64);
            let local = PathBuf::from(row["localPath"].as_str().unwrap());
            assert!(
                local.starts_with(&session.home),
                "remote path cannot choose the output directory"
            );
            assert_eq!(
                fs::read(local).unwrap(),
                bytes,
                "original bytes are preserved"
            );
            break;
        }
        assert!(Instant::now() < deadline, "transfer did not finish: {row}");
        thread::sleep(Duration::from_millis(20));
    }
}

#[test]
fn queued_transfer_can_be_cancelled_without_a_file_connection() {
    let _serial = TEST_LOCK.lock().unwrap();
    let server = Fixture::start(true, None);
    let session = Session::new(server.port);
    session.login();
    search(session.handle, "fixture song", 1200).unwrap();
    let initial: Value =
        serde_json::from_str(&download(session.handle, PEER, AUDIO_PATH, 393_216).unwrap())
            .unwrap();
    let id = initial["id"].as_str().unwrap().to_owned();
    let deadline = Instant::now() + Duration::from_secs(3);
    loop {
        let response: Value = serde_json::from_str(&transfers(session.handle).unwrap()).unwrap();
        let row = response["transfers"]
            .as_array()
            .unwrap()
            .iter()
            .find(|row| row["id"] == id)
            .unwrap();
        if row["queuePosition"] == 7 {
            break;
        }
        assert!(
            Instant::now() < deadline,
            "peer queue update not reflected: {row}"
        );
        thread::sleep(Duration::from_millis(20));
    }
    bitchord_soulseek::cancel(session.handle, &id).unwrap();
    let response: Value = serde_json::from_str(&transfers(session.handle).unwrap()).unwrap();
    let row = response["transfers"]
        .as_array()
        .unwrap()
        .iter()
        .find(|row| row["id"] == id)
        .unwrap();
    assert_eq!(row["status"], "cancelled");
    assert_eq!(row["complete"], false);
}

#[test]
fn closing_session_interrupts_a_long_search_and_invalidates_the_handle() {
    let _serial = TEST_LOCK.lock().unwrap();
    let server = Fixture::start(true, None);
    let session = Session::new(server.port);
    session.login();
    let handle = session.handle;
    let searching = thread::spawn(move || search(handle, "fixture song", 15_000));
    thread::sleep(Duration::from_millis(200));
    let started = Instant::now();
    close(handle).unwrap();
    assert!(
        started.elapsed() < Duration::from_secs(3),
        "close cannot wait for the full search window"
    );
    let result = searching.join().unwrap();
    assert!(
        result.is_err(),
        "a closed session must not publish stale search candidates"
    );
    assert!(transfers(handle).is_err(), "closed handles are invalidated");
    close(handle).unwrap(); // Cleanup is idempotent for lifecycle callers.
}

fn fixture_audio() -> Vec<u8> {
    let mut bytes = vec![0; 393_216];
    bytes[..4].copy_from_slice(b"fLaC");
    for (i, byte) in bytes.iter_mut().enumerate().skip(4) {
        *byte = (i % 251) as u8;
    }
    bytes
}

fn transfer_until(handle: i64, id: &str, predicate: impl Fn(&Value) -> bool) -> Value {
    let deadline = Instant::now() + Duration::from_secs(8);
    loop {
        let snapshot: Value = serde_json::from_str(&transfers(handle).unwrap()).unwrap();
        let row = snapshot["transfers"]
            .as_array()
            .unwrap()
            .iter()
            .find(|row| row["id"] == id)
            .expect("tracked transfer");
        if predicate(row) {
            return row.clone();
        }
        assert!(
            Instant::now() < deadline,
            "transfer did not reach requested state: {row}"
        );
        thread::sleep(Duration::from_millis(10));
    }
}

#[test]
fn cancelling_then_immediately_retrying_uses_a_new_staging_identity() {
    let _serial = TEST_LOCK.lock().unwrap();
    let bytes = fixture_audio();
    let server = Fixture::start(true, Some(bytes.clone()));
    let session = Session::new(server.port);
    session.login();
    search(session.handle, "fixture song", 1200).unwrap();
    let initial: Value = serde_json::from_str(
        &download(session.handle, PEER, AUDIO_PATH, bytes.len() as i64).unwrap(),
    )
    .unwrap();
    let original_id = initial["id"].as_str().unwrap();
    let partial = transfer_until(session.handle, original_id, |row| {
        let count = row["downloaded"].as_u64().unwrap();
        count > 0 && count < bytes.len() as u64
    });
    let old_path = PathBuf::from(partial["localPath"].as_str().unwrap());
    bitchord_soulseek::cancel(session.handle, original_id).unwrap();
    let retried: Value = serde_json::from_str(
        &download(session.handle, PEER, AUDIO_PATH, bytes.len() as i64).unwrap(),
    )
    .unwrap();
    let new_id = retried["id"].as_str().unwrap();
    assert_ne!(
        original_id, new_id,
        "retry cannot reuse a cancelled worker's asset identity"
    );
    let completed = transfer_until(session.handle, new_id, |row| row["complete"] == true);
    let new_path = PathBuf::from(completed["localPath"].as_str().unwrap());
    assert_ne!(
        old_path.parent(),
        new_path.parent(),
        "an exiting old worker must not delete or append to the retry file"
    );
    assert_eq!(fs::read(new_path).unwrap(), bytes);
}

fn staging_snapshot(home: &std::path::Path) -> Vec<(PathBuf, Vec<u8>)> {
    fn collect(path: &std::path::Path, result: &mut Vec<(PathBuf, Vec<u8>)>) {
        for entry in fs::read_dir(path).unwrap() {
            let path = entry.unwrap().path();
            if path.is_dir() {
                collect(&path, result);
            } else {
                result.push((path.clone(), fs::read(path).unwrap()));
            }
        }
    }
    let mut result = Vec::new();
    collect(home, &mut result);
    result.sort_by(|a, b| a.0.cmp(&b.0));
    result
}

#[test]
fn close_mid_transfer_quiesces_files_before_the_same_home_is_reopened() {
    let _serial = TEST_LOCK.lock().unwrap();
    let bytes = fixture_audio();
    let server = Fixture::start(true, Some(bytes.clone()));
    let session = Session::new(server.port);
    session.login();
    search(session.handle, "fixture song", 1200).unwrap();
    let initial: Value = serde_json::from_str(
        &download(session.handle, PEER, AUDIO_PATH, bytes.len() as i64).unwrap(),
    )
    .unwrap();
    let id = initial["id"].as_str().unwrap();
    transfer_until(session.handle, id, |row| {
        let count = row["downloaded"].as_u64().unwrap();
        count > 0 && count < bytes.len() as u64
    });
    close(session.handle).unwrap();
    assert!(transfers(session.handle).is_err());
    let after_close = staging_snapshot(&session.home);
    let reopened = create(
        session.home.to_str().unwrap(),
        "127.0.0.1",
        i32::from(server.port),
        0,
    )
    .expect("successful close releases the home only once transfer workers are quiescent");
    thread::sleep(Duration::from_millis(700));
    assert_eq!(
        staging_snapshot(&session.home),
        after_close,
        "old transfer workers cannot write, rename, or delete staging after close returned"
    );
    close(reopened).unwrap();
}
