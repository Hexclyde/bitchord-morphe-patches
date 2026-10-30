//! On-device Soulseek JNI adapter. Network operations run on the caller's IO executor.
//!
//! Handles are registry IDs rather than raw pointers. Closing invalidates the ID,
//! cancels outstanding work, then disconnects and releases the reserved home. An in-flight operation
//! retains an Arc, so Java races cannot free an object beneath another call.

mod discovery;
mod nostr;
mod relay_probe;

use jni::JNIEnv;
use jni::objects::{JClass, JString};
use jni::sys::{jint, jlong, jstring};
use serde_json::{Value, json};
use soulseek_rs::{CancelHandle, Client, ClientSettings, DownloadStatus, PeerAddress};
use std::collections::HashMap;
use std::fs;
use std::panic::{AssertUnwindSafe, catch_unwind};
use std::path::{Path, PathBuf};
use std::sync::atomic::{AtomicBool, AtomicI64, AtomicU64, Ordering};
use std::sync::{Arc, Mutex, OnceLock, RwLock};
use std::time::{Duration, SystemTime, UNIX_EPOCH};

pub type Result<T> = std::result::Result<T, String>;
const MAX_RESULTS: usize = 200;
const MAX_TRANSFERS: usize = 64;
const MAX_ACTIVE: usize = 2;
const MAX_CACHE: u64 = 256 * 1024 * 1024;
static NEXT_HANDLE: AtomicI64 = AtomicI64::new(1);
static NEXT_ASSET: AtomicU64 = AtomicU64::new(1);
static ENGINES: OnceLock<Mutex<HashMap<i64, Arc<Engine>>>> = OnceLock::new();

struct Config {
    home: PathBuf,
    host: String,
    server_port: u16,
    listen_port: u16,
}

struct Engine {
    config: Config,
    closed: AtomicBool,
    client: RwLock<Option<Client>>,
    cancel: Mutex<Option<CancelHandle>>,
    search_cancel: Arc<AtomicBool>,
    searching: AtomicBool,
    records: Mutex<Vec<Record>>,
    teardown: Mutex<()>,
}

#[derive(Clone)]
struct Record {
    id: String,
    peer: String,
    remote: String,
    size: u64,
    directory: PathBuf,
    cancelled: bool,
}

fn registry() -> &'static Mutex<HashMap<i64, Arc<Engine>>> {
    ENGINES.get_or_init(|| Mutex::new(HashMap::new()))
}

fn state(message: &str) -> String {
    format!("STATE:{message}")
}
fn input(message: &str) -> String {
    format!("INPUT:{message}")
}
fn io(message: &str) -> String {
    format!("IO:{message}")
}

fn engine(handle: i64) -> Result<Arc<Engine>> {
    let engines = registry()
        .lock()
        .map_err(|_| state("Soulseek registry unavailable"))?;
    engines
        .get(&handle)
        .cloned()
        .ok_or_else(|| state("Soulseek session is closed"))
}

fn live(engine: &Engine) -> Result<()> {
    if engine.closed.load(Ordering::Acquire) {
        Err(state("Soulseek session is closed"))
    } else {
        Ok(())
    }
}

fn bounded(value: &str, max_bytes: usize, label: &str) -> Result<()> {
    if value.is_empty() || value.len() > max_bytes || value.chars().any(char::is_control) {
        Err(input(label))
    } else {
        Ok(())
    }
}

fn username(value: &str) -> Result<()> {
    if value.is_empty()
        || value.len() > 30
        || !value.bytes().all(|b| (32..=126).contains(&b))
        || value.trim() != value
    {
        Err(input("Invalid Soulseek username"))
    } else {
        Ok(())
    }
}

fn extension(path: &str) -> Option<String> {
    let basename = path.rsplit(['/', '\\']).next()?;
    if basename.len() > 200 {
        return None;
    }
    let ext = basename.rsplit_once('.')?.1.to_ascii_lowercase();
    matches!(
        ext.as_str(),
        "mp3"
            | "flac"
            | "wav"
            | "wave"
            | "ogg"
            | "oga"
            | "vorbis"
            | "opus"
            | "m4a"
            | "mp4"
            | "aac"
            | "aif"
            | "aiff"
            | "aifc"
            | "alac"
            | "dsf"
            | "dff"
    )
    .then_some(ext)
}

fn validate_remote(path: &str, size: i64) -> Result<()> {
    bounded(path, 4096, "Invalid remote audio path")?;
    if extension(path).is_none() {
        return Err(input("Only supported audio files may be downloaded"));
    }
    if size <= 0 || size as u64 > MAX_CACHE {
        return Err(input("Audio file exceeds the 256 MiB staging limit"));
    }
    Ok(())
}

fn directory_bytes(path: &Path, budget: &mut usize) -> Result<u64> {
    let mut bytes = 0u64;
    for entry in fs::read_dir(path).map_err(|_| io("Cannot inspect Soulseek staging directory"))? {
        if *budget == 0 {
            return Err(io("Soulseek staging directory has too many entries"));
        }
        *budget -= 1;
        let entry = entry.map_err(|_| io("Cannot inspect Soulseek staging entry"))?;
        let kind = entry
            .file_type()
            .map_err(|_| io("Cannot inspect Soulseek staging entry"))?;
        if kind.is_symlink() {
            return Err(io("Links are not allowed in Soulseek staging"));
        }
        let amount = if kind.is_dir() {
            directory_bytes(&entry.path(), budget)?
        } else {
            entry
                .metadata()
                .map_err(|_| io("Cannot inspect Soulseek staging entry"))?
                .len()
        };
        bytes = bytes
            .checked_add(amount)
            .ok_or_else(|| io("Soulseek staging size overflow"))?;
    }
    Ok(bytes)
}

fn new_directory(home: &Path) -> Result<(String, PathBuf)> {
    for _ in 0..10 {
        let epoch = SystemTime::now()
            .duration_since(UNIX_EPOCH)
            .map_err(|_| io("Invalid system clock"))?
            .as_nanos();
        let sequence = NEXT_ASSET.fetch_add(1, Ordering::Relaxed);
        let id = format!("s{epoch:x}-{sequence:x}");
        let directory = home.join(&id);
        match fs::create_dir(&directory) {
            Ok(()) => return Ok((id, directory)),
            Err(error) if error.kind() == std::io::ErrorKind::AlreadyExists => {}
            Err(_) => return Err(io("Cannot create private transfer directory")),
        }
    }
    Err(io("Cannot allocate private transfer identity"))
}

/// Create a session without opening sockets. Server override supports controlled testing.
pub fn create(home: &str, host: &str, server_port: i32, listen_port: i32) -> Result<i64> {
    bounded(home, 4096, "Invalid Soulseek home directory")?;
    bounded(host, 253, "Invalid Soulseek server")?;
    if host.contains(['/', '\\', ':', '@', ' '])
        || !(1..=65535).contains(&server_port)
        || !(0..=65535).contains(&listen_port)
    {
        return Err(input("Invalid Soulseek server or listener address"));
    }
    let home = Path::new(home);
    if !home.is_absolute() {
        return Err(input("Soulseek home must be an absolute private path"));
    }
    fs::create_dir_all(home).map_err(|_| io("Cannot create Soulseek home"))?;
    let home = fs::canonicalize(home)
        .map_err(|_| io("Cannot resolve Soulseek home"))?
        .join("transfers");
    fs::create_dir_all(&home).map_err(|_| io("Cannot create Soulseek staging directory"))?;
    if fs::symlink_metadata(&home)
        .map_err(|_| io("Cannot inspect Soulseek staging"))?
        .file_type()
        .is_symlink()
    {
        return Err(io("Soulseek staging cannot be a link"));
    }
    let _ = directory_bytes(&home, &mut 10_000)?;
    let mut engines = registry()
        .lock()
        .map_err(|_| state("Soulseek registry unavailable"))?;
    if engines.len() >= 4 {
        return Err(state("Too many native Soulseek sessions"));
    }
    if engines.values().any(|e| e.config.home == home) {
        return Err(state("Soulseek home is already open"));
    }
    let handle = NEXT_HANDLE.fetch_add(1, Ordering::Relaxed);
    if handle <= 0 {
        return Err(state("Soulseek handle space exhausted"));
    }
    engines.insert(
        handle,
        Arc::new(Engine {
            config: Config {
                home,
                host: host.to_string(),
                server_port: server_port as u16,
                listen_port: listen_port as u16,
            },
            closed: AtomicBool::new(false),
            client: RwLock::new(None),
            cancel: Mutex::new(None),
            search_cancel: Arc::new(AtomicBool::new(false)),
            searching: AtomicBool::new(false),
            records: Mutex::new(Vec::new()),
            teardown: Mutex::new(()),
        }),
    );
    Ok(handle)
}

/// Connect and wait for the server's real login verdict. Call only from an IO thread.
pub fn connect(handle: i64, user: &str, password: &str) -> Result<()> {
    username(user)?;
    if password.is_empty() || password.len() > 1024 || password.contains('\0') {
        return Err(input("Invalid Soulseek password"));
    }
    let engine = engine(handle)?;
    let mut slot = engine
        .client
        .write()
        .map_err(|_| state("Soulseek session unavailable"))?;
    live(&engine)?;
    if slot.is_some() {
        return Err(state("Soulseek session is already connected"));
    }
    let mut settings = ClientSettings::new(user, password);
    settings.server_address =
        PeerAddress::new(engine.config.host.clone(), engine.config.server_port);
    settings.listen_port = engine.config.listen_port;
    settings.accept_children = false;
    settings.shared_directories = Vec::new();
    // Library's designated unidentified embedding ID. Reserve a project ID before publication.
    let mut client = Client::with_settings(settings);
    *engine
        .cancel
        .lock()
        .map_err(|_| state("Soulseek cancellation unavailable"))? = Some(client.cancel_handle());
    client
        .connect()
        .map_err(|_| io("Could not connect to the Soulseek server"))?;
    match client.login() {
        Ok(true) => {}
        _ => {
            client.disconnect();
            return Err(io("Soulseek login was rejected or timed out"));
        }
    }
    live(&engine)?;
    engine.search_cancel.store(false, Ordering::Release);
    *slot = Some(client);
    Ok(())
}

struct SearchGuard<'a>(&'a AtomicBool);
impl Drop for SearchGuard<'_> {
    fn drop(&mut self) {
        self.0.store(false, Ordering::Release);
    }
}

/// Collect bounded audio-only results from the actual network. No credentials are returned.
pub fn search(handle: i64, query: &str, timeout_ms: i32) -> Result<String> {
    search_window(handle, query, timeout_ms, None)
}

/// A preferred filename hint can settle the initial batch; it never verifies a codec.
pub fn search_adaptive(
    handle: i64,
    query: &str,
    timeout_ms: i32,
    preferred_extensions: &str,
) -> Result<String> {
    bounded(preferred_extensions, 128, "Invalid preferred audio formats")?;
    let preferred: std::collections::HashSet<&str> = preferred_extensions.split(',').collect();
    if preferred
        .iter()
        .any(|ext| extension(&format!("audio.{ext}")).as_deref() != Some(*ext))
    {
        return Err(input("Invalid preferred audio formats"));
    }
    search_window(handle, query, timeout_ms, Some(&preferred))
}

fn search_window(
    handle: i64,
    query: &str,
    timeout_ms: i32,
    preferred: Option<&std::collections::HashSet<&str>>,
) -> Result<String> {
    bounded(query, 512, "Invalid Soulseek search query")?;
    if query.trim().is_empty() || !(100..=20_000).contains(&timeout_ms) {
        return Err(input("Search timeout must be 100–20000 ms"));
    }
    let engine = engine(handle)?;
    if engine
        .searching
        .compare_exchange(false, true, Ordering::AcqRel, Ordering::Acquire)
        .is_err()
    {
        return Err(state("A Soulseek search is already active"));
    }
    let _search = SearchGuard(&engine.searching);
    let slot = engine
        .client
        .read()
        .map_err(|_| state("Soulseek session unavailable"))?;
    live(&engine)?;
    let client = slot
        .as_ref()
        .ok_or_else(|| state("Connect Soulseek before searching"))?;
    if client.session_loss().is_some() {
        return Err(io("Soulseek server session was lost"));
    }
    let timeout = Duration::from_millis(timeout_ms as u64);
    let cancellation = Some(engine.search_cancel.clone());
    let collected = if let Some(preferred) = preferred {
        client.search_adaptive_with_cancel(query, timeout, cancellation, |response| {
            if username(&response.username).is_err() {
                return 0;
            }
            response
                .files
                .iter()
                .filter(|file| {
                    extension(&file.name).is_some_and(|ext| preferred.contains(ext.as_str()))
                        && validate_remote(&file.name, file.size as i64).is_ok()
                })
                .count()
        })
    } else {
        client.search_with_cancel(query, timeout, cancellation)
    };
    let _ = client.forget_search(query);
    let results = collected.map_err(|_| io("Soulseek search failed"))?;
    live(&engine)?;
    if client.session_loss().is_some() {
        return Err(io("Soulseek server session was lost"));
    }
    let mut tracks = Vec::new();
    let mut seen = std::collections::HashSet::new();
    for response in results {
        for file in response.files {
            if preferred.is_none() && tracks.len() >= MAX_RESULTS {
                break;
            }
            let Some(format) = extension(&file.name) else {
                continue;
            };
            if validate_remote(&file.name, file.size as i64).is_err()
                || username(&response.username).is_err()
            {
                continue;
            }
            let identity = (response.username.clone(), file.name.clone(), file.size);
            if !seen.insert(identity) {
                continue;
            }
            let is_preferred = preferred.is_some_and(|allowed| allowed.contains(format.as_str()));
            tracks.push((is_preferred, json!({
                "id": format!("candidate-{}", tracks.len() + 1), "peer": response.username, "path": file.name,
                "size": file.size, "format": format, "extension": format,
                "duration": file.attribs.get(&1).copied().unwrap_or(0),
                "bitrate": file.attribs.get(&0).copied().unwrap_or(0),
                "sampleRate": file.attribs.get(&4).copied().unwrap_or(0),
                "bitDepth": file.attribs.get(&5).copied().unwrap_or(0),
                "freeSlots": response.slots, "speed": response.speed,
            })));
        }
        if preferred.is_none() && tracks.len() >= MAX_RESULTS {
            break;
        }
    }
    Ok(json!({"tracks": initial_search_batch(tracks)}).to_string())
}

fn initial_search_batch(tracks: Vec<(bool, Value)>) -> Vec<Value> {
    // Keep preferred candidates even when an early lower-priority batch filled
    // the 200-row output cap. Selection does not reorder different recordings.
    let mut selected = vec![false; tracks.len()];
    let mut remaining = MAX_RESULTS;
    for wanted in [true, false] {
        for (index, (is_preferred, _)) in tracks.iter().enumerate() {
            if remaining > 0 && *is_preferred == wanted {
                selected[index] = true;
                remaining -= 1;
            }
        }
    }
    tracks
        .into_iter()
        .enumerate()
        .filter_map(|(index, (_, row))| selected[index].then_some(row))
        .collect()
}

fn snapshot(record: &Record, downloads: &[soulseek_rs::types::Download]) -> Result<Value> {
    let current = downloads
        .iter()
        .rev()
        .find(|d| d.username == record.peer && d.filename == record.remote);
    let mut status = if record.cancelled {
        "cancelled"
    } else {
        "queued"
    };
    let mut downloaded = 0;
    let mut queue_position = None;
    let mut error = None;
    if let Some(download) = current {
        downloaded = download.bytes_downloaded().min(record.size);
        queue_position = download.queue_position;
        if !record.cancelled {
            status = match download.status {
                DownloadStatus::Queued => "queued",
                DownloadStatus::InProgress { .. } => "downloading",
                DownloadStatus::Paused { .. } => "paused",
                DownloadStatus::Completed => "complete",
                DownloadStatus::Cancelled => "cancelled",
                DownloadStatus::Failed(_) | DownloadStatus::TimedOut => "failed",
            };
            if status == "failed" {
                error = Some("Peer transfer failed; retry or choose another peer");
            }
        }
    }
    let basename = record
        .remote
        .rsplit(['/', '\\'])
        .next()
        .ok_or_else(|| state("Invalid transfer identity"))?;
    let final_path = record.directory.join(basename);
    let part_path = record.directory.join(format!("{basename}.part"));
    let selected = if status == "complete" {
        &final_path
    } else {
        &part_path
    };
    let mut local_path = String::new();
    if let Ok(metadata) = fs::symlink_metadata(selected) {
        if metadata.is_file() && !metadata.file_type().is_symlink() {
            let actual = metadata.len().min(record.size);
            if status == "complete" && metadata.len() != record.size {
                status = "failed";
                downloaded = 0;
                error = Some("Completed audio has an unexpected size");
            } else {
                downloaded = downloaded.min(actual);
                local_path = selected.to_string_lossy().into_owned();
            }
        }
    } else if status == "complete" {
        status = "failed";
        downloaded = 0;
        error = Some("Completed audio is no longer in staging");
    }
    Ok(
        json!({"id":record.id,"peer":record.peer,"path":record.remote,"total":record.size,"downloaded":downloaded,"status":status,"localPath":local_path,"complete":status=="complete","queuePosition":queue_position,"error":error}),
    )
}

/// Queue an audio transfer, isolating each peer/path/size in a private directory.
pub fn download(handle: i64, peer: &str, remote: &str, size: i64) -> Result<String> {
    username(peer)?;
    validate_remote(remote, size)?;
    let engine = engine(handle)?;
    let slot = engine
        .client
        .read()
        .map_err(|_| state("Soulseek session unavailable"))?;
    live(&engine)?;
    let client = slot
        .as_ref()
        .ok_or_else(|| state("Connect Soulseek before downloading"))?;
    if client.session_loss().is_some() {
        return Err(io("Soulseek server session was lost"));
    }
    let mut records = engine
        .records
        .lock()
        .map_err(|_| state("Soulseek transfer store unavailable"))?;
    let existing_downloads = client.get_all_downloads();
    // Java may have evicted completed cached files. Retire empty terminal records
    // before the per-session metadata cap is reached; active identities stay intact.
    if records.len() >= MAX_TRANSFERS {
        records.retain(|r| {
            let active = existing_downloads
                .iter()
                .any(|d| d.username == r.peer && d.filename == r.remote && !d.is_finished());
            active || directory_bytes(&r.directory, &mut 100).unwrap_or(0) > 0
        });
    }
    let existing = records
        .iter()
        .position(|r| r.peer == peer && r.remote == remote);
    if let Some(index) = existing {
        if records[index].size != size as u64 {
            return Err(input(
                "Peer file size changed; start a new session before retrying",
            ));
        }
        let current = snapshot(&records[index], &existing_downloads)?;
        if !matches!(current["status"].as_str(), Some("failed" | "cancelled")) {
            return Ok(current.to_string());
        }
    }
    let active = existing_downloads
        .iter()
        .filter(|d| !d.is_finished())
        .count();
    if active >= MAX_ACTIVE || client.active_download_workers() >= MAX_ACTIVE {
        return Err(state("Two peer transfers are already active"));
    }
    if existing.is_none() && records.len() >= MAX_TRANSFERS {
        return Err(state("Session transfer limit reached"));
    }
    let occupied = directory_bytes(&engine.config.home, &mut 10_000)?;
    let pending = records
        .iter()
        .try_fold(0u64, |total, r| {
            let active = existing_downloads
                .iter()
                .any(|d| d.username == r.peer && d.filename == r.remote && !d.is_finished());
            let physical = directory_bytes(&r.directory, &mut 100).unwrap_or(0);
            total.checked_add(if active {
                r.size.saturating_sub(physical)
            } else {
                0
            })
        })
        .ok_or_else(|| io("Staging size overflow"))?;
    let reserved = occupied
        .checked_add(pending)
        .ok_or_else(|| io("Staging size overflow"))?;
    // Cancelled workers can still be leaving their old file; never reuse that
    // directory on retry. Failed workers publish failure only after leaving IO.
    let cancelled_retry = existing.is_some_and(|i| records[i].cancelled);
    let reuse = existing.filter(|_| !cancelled_retry);
    let physical = reuse
        .map(|i| directory_bytes(&records[i].directory, &mut 100).unwrap_or(0))
        .unwrap_or(0);
    let required = (size as u64).saturating_sub(physical);
    if reserved.saturating_add(required) > MAX_CACHE {
        return Err(io(
            "Soulseek staging limit reached; clear completed files before downloading",
        ));
    }
    let record = if let Some(index) = reuse {
        let mut record = records[index].clone();
        record.cancelled = false;
        record
    } else {
        let (id, directory) = new_directory(&engine.config.home)?;
        Record {
            id,
            peer: peer.to_string(),
            remote: remote.to_string(),
            size: size as u64,
            directory,
            cancelled: false,
        }
    };
    let _ = client.remove_download(peer, remote);
    client
        .download(
            remote.to_string(),
            peer.to_string(),
            size as u64,
            record.directory.to_string_lossy().into_owned(),
        )
        .map_err(|_| io("Could not queue Soulseek audio transfer"))?;
    let record = if let Some(index) = existing {
        records[index] = record;
        &records[index]
    } else {
        records.push(record);
        records.last().unwrap()
    };
    snapshot(record, &client.get_all_downloads()).map(|value| value.to_string())
}

/// Only the explicit, flat, app-private share inventory may be exposed to peers.
fn share_root(home: &Path) -> Result<PathBuf> {
    let root = home.join("public-shares");
    fs::create_dir_all(&root).map_err(|_| io("Cannot create public share inventory"))?;
    if fs::symlink_metadata(&root)
        .map_err(|_| io("Share inventory unavailable"))?
        .file_type()
        .is_symlink()
        || root
            .canonicalize()
            .map_err(|_| io("Share inventory unavailable"))?
            != root
    {
        return Err(input("Share inventory must not be a symlink"));
    }
    let mut count = 0;
    let mut bytes = 0u64;
    for item in fs::read_dir(&root).map_err(|_| io("Cannot read share inventory"))? {
        let item = item.map_err(|_| io("Cannot read share inventory"))?;
        let meta = fs::symlink_metadata(item.path()).map_err(|_| io("Cannot read shared file"))?;
        count += 1;
        bytes = bytes.saturating_add(meta.len());
        if !meta.file_type().is_file()
            || meta.len() == 0
            || meta.len() > MAX_CACHE
            || count > 200
            || bytes > 2 * 1024 * 1024 * 1024
            || extension(&item.file_name().to_string_lossy()).is_none()
        {
            return Err(input("Invalid or oversized public share inventory"));
        }
    }
    Ok(root)
}

pub fn configure_shares(handle: i64, enabled: bool, slots: i32) -> Result<()> {
    if !(1..=4).contains(&slots) {
        return Err(input("Choose one to four upload slots"));
    }
    let engine = engine(handle)?;
    let slot = engine
        .client
        .read()
        .map_err(|_| state("Soulseek session unavailable"))?;
    live(&engine)?;
    let client = slot
        .as_ref()
        .ok_or_else(|| state("Connect Soulseek before sharing"))?;
    let roots = if enabled {
        vec![
            share_root(
                engine
                    .config
                    .home
                    .parent()
                    .ok_or_else(|| state("Invalid Soulseek home"))?,
            )?
            .to_string_lossy()
            .into_owned(),
        ]
    } else {
        Vec::new()
    };
    client.set_upload_slots(slots as usize);
    if client.set_shared_directories(roots).is_err() {
        // Updating the index precedes the server announcement. Withdraw it on a failed enable.
        if enabled {
            let _ = client.set_shared_directories(Vec::new());
        }
        return Err(io("Could not announce public shares"));
    }
    Ok(())
}

pub fn sharing(handle: i64) -> Result<String> {
    let engine = engine(handle)?;
    let slot = engine
        .client
        .read()
        .map_err(|_| state("Soulseek session unavailable"))?;
    live(&engine)?;
    let client = slot
        .as_ref()
        .ok_or_else(|| state("Connect Soulseek before reading uploads"))?;
    let (folders, files) = client.shared_counts();
    let uploads: Vec<Value> = client
        .uploads()
        .into_iter()
        .take(256)
        .map(|upload| {
            let (phase, position) = match upload.status {
                soulseek_rs::UploadStatus::Queued(p) => ("queued", p),
                soulseek_rs::UploadStatus::InProgress => ("uploading", 0),
                soulseek_rs::UploadStatus::Completed => ("complete", 0),
                soulseek_rs::UploadStatus::Cancelled => ("cancelled", 0),
                soulseek_rs::UploadStatus::Failed(_) => ("failed", 0),
            };
            json!({"peer":upload.username,"path":upload.filename,"size":upload.size,
            "sent":upload.bytes_sent,"speed":upload.speed_bytes_per_sec,
            "phase":phase,"position":position})
        })
        .collect();
    Ok(json!({"folders":folders,"files":files,"uploads":uploads}).to_string())
}

pub fn cancel_upload(handle: i64, peer: &str, path: &str) -> Result<()> {
    bounded(peer, 128, "Invalid upload peer")?;
    bounded(path, 4096, "Invalid upload path")?;
    let engine = engine(handle)?;
    let slot = engine
        .client
        .read()
        .map_err(|_| state("Soulseek session unavailable"))?;
    live(&engine)?;
    let client = slot
        .as_ref()
        .ok_or_else(|| state("Soulseek disconnected"))?;
    if !client.cancel_upload(peer, path) {
        return Err(state("Upload is no longer active"));
    }
    Ok(())
}

pub fn transfers(handle: i64) -> Result<String> {
    let engine = engine(handle)?;
    let slot = engine
        .client
        .read()
        .map_err(|_| state("Soulseek session unavailable"))?;
    live(&engine)?;
    let client = slot
        .as_ref()
        .ok_or_else(|| state("Connect Soulseek before reading transfers"))?;
    let records = engine
        .records
        .lock()
        .map_err(|_| state("Soulseek transfer store unavailable"))?;
    let downloads = client.get_all_downloads();
    let snapshots: Result<Vec<Value>> = records.iter().map(|r| snapshot(r, &downloads)).collect();
    Ok(json!({"transfers":snapshots?}).to_string())
}

pub fn cancel(handle: i64, transfer_id: &str) -> Result<()> {
    bounded(transfer_id, 100, "Invalid transfer ID")?;
    let engine = engine(handle)?;
    let slot = engine
        .client
        .read()
        .map_err(|_| state("Soulseek session unavailable"))?;
    live(&engine)?;
    let client = slot
        .as_ref()
        .ok_or_else(|| state("Connect Soulseek before cancelling"))?;
    let mut records = engine
        .records
        .lock()
        .map_err(|_| state("Soulseek transfer store unavailable"))?;
    let record = records
        .iter_mut()
        .find(|r| r.id == transfer_id)
        .ok_or_else(|| input("Unknown Soulseek transfer ID"))?;
    let current = snapshot(record, &client.get_all_downloads())?;
    if current["complete"] == true {
        return Ok(());
    }
    record.cancelled = true;
    let _ = client.cancel_download(&record.peer, &record.remote);
    Ok(())
}

/// Idempotent. Reserve the home during teardown; failed shutdown stays quarantined.
pub fn close(handle: i64) -> Result<()> {
    let engine = registry()
        .lock()
        .map_err(|_| state("Soulseek registry unavailable"))?
        .get(&handle)
        .cloned();
    let Some(engine) = engine else { return Ok(()) };
    let _teardown = engine
        .teardown
        .lock()
        .map_err(|_| state("Soulseek teardown unavailable"))?;
    engine.closed.store(true, Ordering::Release);
    engine.search_cancel.store(true, Ordering::Release);
    if let Some(cancel) = engine
        .cancel
        .lock()
        .map_err(|_| state("Soulseek cancellation unavailable"))?
        .as_ref()
    {
        cancel.cancel();
    }
    let mut slot = engine
        .client
        .write()
        .map_err(|_| state("Soulseek session unavailable"))?;
    if let Some(client) = slot.as_mut() {
        let _ = client.stop_download_workers();
        for download in client.get_all_downloads() {
            if !download.is_finished() {
                let _ = client.cancel_download(&download.username, &download.filename);
            }
        }
        client.disconnect();
        let deadline = std::time::Instant::now() + Duration::from_secs(25);
        while client.active_download_workers() > 0 && std::time::Instant::now() < deadline {
            std::thread::sleep(Duration::from_millis(20));
        }
        if client.active_download_workers() > 0 {
            return Err(io(
                "Soulseek downloads are still shutting down; retain staging data",
            ));
        }
    }
    *slot = None;
    registry()
        .lock()
        .map_err(|_| state("Soulseek registry unavailable"))?
        .remove(&handle);
    Ok(())
}

fn java_string(env: &mut JNIEnv<'_>, value: JString<'_>) -> Result<String> {
    if value.is_null() {
        return Err(input("Null native string argument"));
    }
    env.get_string(&value)
        .map(Into::into)
        .map_err(|_| input("Invalid native string argument"))
}

fn invoke<T: Default>(
    env: &mut JNIEnv<'_>,
    action: impl FnOnce(&mut JNIEnv<'_>) -> Result<T>,
) -> T {
    let result = catch_unwind(AssertUnwindSafe(|| action(env)));
    match result {
        Ok(Ok(value)) => value,
        result => {
            let message = match result {
                Ok(Err(message)) => message,
                _ => state("Soulseek native operation failed"),
            };
            let (kind, text) = if let Some(text) = message.strip_prefix("INPUT:") {
                ("java/lang/IllegalArgumentException", text)
            } else if let Some(text) = message.strip_prefix("IO:") {
                ("java/io/IOException", text)
            } else {
                (
                    "java/lang/IllegalStateException",
                    message.strip_prefix("STATE:").unwrap_or(&message),
                )
            };
            if !env.exception_check().unwrap_or(true) {
                let _ = env.throw_new(kind, text);
            }
            T::default()
        }
    }
}

fn output(env: &mut JNIEnv<'_>, value: String) -> Result<jstring> {
    env.new_string(value)
        .map(|s| s.into_raw())
        .map_err(|_| state("Cannot allocate Soulseek response"))
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_app_bitchord_soulseek_SoulseekNative_create(
    mut env: JNIEnv,
    _class: JClass,
    home: JString,
    host: JString,
    server_port: jint,
    listen_port: jint,
) -> jlong {
    invoke(&mut env, |env| {
        let home = java_string(env, home)?;
        let host = java_string(env, host)?;
        create(&home, &host, server_port, listen_port)
    })
}
#[unsafe(no_mangle)]
pub extern "system" fn Java_app_bitchord_soulseek_SoulseekNative_connect(
    mut env: JNIEnv,
    _class: JClass,
    handle: jlong,
    user: JString,
    password: JString,
) {
    invoke(&mut env, |env| {
        let user = java_string(env, user)?;
        let password = java_string(env, password)?;
        connect(handle, &user, &password)
    });
}
#[unsafe(no_mangle)]
pub extern "system" fn Java_app_bitchord_soulseek_SoulseekNative_search(
    mut env: JNIEnv,
    _class: JClass,
    handle: jlong,
    query: JString,
    timeout_ms: jint,
) -> jstring {
    invoke(&mut env, |env| {
        let query = java_string(env, query)?;
        output(env, search(handle, &query, timeout_ms)?)
    })
}
#[unsafe(no_mangle)]
pub extern "system" fn Java_app_bitchord_soulseek_SoulseekNative_searchAdaptive(
    mut env: JNIEnv,
    _class: JClass,
    handle: jlong,
    query: JString,
    timeout_ms: jint,
    preferred_extensions: JString,
) -> jstring {
    invoke(&mut env, |env| {
        let query = java_string(env, query)?;
        let preferred = java_string(env, preferred_extensions)?;
        output(
            env,
            search_adaptive(handle, &query, timeout_ms, &preferred)?,
        )
    })
}
#[unsafe(no_mangle)]
pub extern "system" fn Java_app_bitchord_soulseek_SoulseekNative_download(
    mut env: JNIEnv,
    _class: JClass,
    handle: jlong,
    peer: JString,
    path: JString,
    size: jlong,
) -> jstring {
    invoke(&mut env, |env| {
        let peer = java_string(env, peer)?;
        let path = java_string(env, path)?;
        output(env, download(handle, &peer, &path, size)?)
    })
}
#[unsafe(no_mangle)]
pub extern "system" fn Java_app_bitchord_soulseek_SoulseekNative_transfers(
    mut env: JNIEnv,
    _class: JClass,
    handle: jlong,
) -> jstring {
    invoke(&mut env, |env| output(env, transfers(handle)?))
}
#[unsafe(no_mangle)]
pub extern "system" fn Java_app_bitchord_soulseek_SoulseekNative_cancel(
    mut env: JNIEnv,
    _class: JClass,
    handle: jlong,
    transfer_id: JString,
) {
    invoke(&mut env, |env| {
        let id = java_string(env, transfer_id)?;
        cancel(handle, &id)
    });
}
#[unsafe(no_mangle)]
pub extern "system" fn Java_app_bitchord_soulseek_SoulseekNative_close(
    mut env: JNIEnv,
    _class: JClass,
    handle: jlong,
) {
    invoke(&mut env, |_| close(handle));
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_app_bitchord_soulseek_SoulseekNative_configureShares(
    mut env: JNIEnv,
    _class: JClass,
    handle: jlong,
    enabled: jni::sys::jboolean,
    slots: jint,
) {
    invoke(&mut env, |_| configure_shares(handle, enabled != 0, slots));
}
#[unsafe(no_mangle)]
pub extern "system" fn Java_app_bitchord_soulseek_SoulseekNative_sharing(
    mut env: JNIEnv,
    _class: JClass,
    handle: jlong,
) -> jstring {
    invoke(&mut env, |env| output(env, sharing(handle)?))
}
#[unsafe(no_mangle)]
pub extern "system" fn Java_app_bitchord_soulseek_SoulseekNative_cancelUpload(
    mut env: JNIEnv,
    _class: JClass,
    handle: jlong,
    peer: JString,
    path: JString,
) {
    invoke(&mut env, |env| {
        let peer = java_string(env, peer)?;
        let path = java_string(env, path)?;
        cancel_upload(handle, &peer, &path)
    });
}

#[cfg(test)]
mod tests {
    use super::*;
    fn home() -> String {
        let (name, dir) = new_directory(&std::env::temp_dir()).unwrap();
        let _ = name;
        dir.to_str().unwrap().to_string()
    }
    #[test]
    fn preferred_results_survive_a_full_early_batch_without_reordering_selected_rows() {
        let mut rows: Vec<(bool, Value)> = (0..250).map(|i| (false, json!(i))).collect();
        rows.push((true, json!(250)));
        rows.push((true, json!(251)));
        let selected = initial_search_batch(rows);
        assert_eq!(selected.len(), MAX_RESULTS);
        assert_eq!(selected[0], json!(0));
        assert_eq!(selected[MAX_RESULTS - 3], json!(197));
        assert_eq!(selected[MAX_RESULTS - 2], json!(250));
        assert_eq!(selected[MAX_RESULTS - 1], json!(251));
    }
    #[test]
    fn closed_handles_cannot_be_used_or_double_freed() {
        let home = home();
        let h = create(&home, "localhost", 1, 0).unwrap();
        assert!(
            search(h, "test", 100)
                .unwrap_err()
                .contains("Connect Soulseek")
        );
        close(h).unwrap();
        close(h).unwrap();
        assert!(transfers(h).unwrap_err().contains("closed"));
        fs::remove_dir_all(home).unwrap();
    }
    #[test]
    fn untrusted_paths_and_sizes_cannot_escape_staging() {
        for p in [
            "../test.exe",
            "music/a.zip",
            "music/../",
            "music/test.flac\n",
            "music/test.mp4.exe",
        ] {
            assert!(validate_remote(p, 1024).is_err(), "{p}");
        }
        assert!(validate_remote("music/test.flac", 0).is_err());
        assert!(validate_remote("music/test.flac", i64::MAX).is_err());
        assert!(validate_remote("../not-a-local-path/song.FLAC", 1024).is_ok());
    }
    #[test]
    fn server_configuration_is_checked_before_network_io() {
        assert!(create("relative", "localhost", 1, 0).is_err());
        assert!(create("/tmp/unused", "https://localhost", 1, 0).is_err());
        assert!(create("/tmp/unused", "localhost", 0, 0).is_err());
        assert!(create("/tmp/unused", "localhost", 1, -1).is_err());
    }
    #[test]
    fn public_inventory_rejects_symlinks_directories_and_non_audio() {
        let path = home();
        let root = Path::new(&path).join("public-shares");
        assert!(share_root(Path::new(&path)).is_ok());
        fs::write(root.join("valid.flac"), b"fLaCbytes").unwrap();
        assert!(share_root(Path::new(&path)).is_ok());
        fs::write(root.join("secret.txt"), b"private").unwrap();
        assert!(share_root(Path::new(&path)).is_err());
        fs::remove_file(root.join("secret.txt")).unwrap();
        std::os::unix::fs::symlink(root.join("valid.flac"), root.join("linked.flac")).unwrap();
        assert!(share_root(Path::new(&path)).is_err());
        fs::remove_file(root.join("linked.flac")).unwrap();
        fs::create_dir(root.join("nested")).unwrap();
        assert!(share_root(Path::new(&path)).is_err());
        fs::remove_dir_all(path).unwrap();
    }

    #[test]
    fn same_basename_from_different_peers_has_different_storage() {
        let home = home();
        let (a, ap) = new_directory(Path::new(&home)).unwrap();
        let (b, bp) = new_directory(Path::new(&home)).unwrap();
        assert_ne!(a, b);
        assert_ne!(ap.join("song.flac"), bp.join("song.flac"));
        fs::remove_dir_all(home).unwrap();
    }
    #[test]
    fn usernames_reject_controls_and_silent_trimming() {
        for s in ["", " user", "user ", "user\n", "用户"] {
            assert!(username(s).is_err());
        }
        assert!(username("listener-1").is_ok());
    }

    #[test]
    fn dsd_and_audio_container_candidates_reach_completed_byte_validation() {
        // These are filename hints shared with AudioFormatPreferences. Native
        // staging must not discard candidates before Java can inspect bytes.
        for path in [
            "archive/album/track.DSF",
            "archive\\album\\track.dff",
            "album/lossless.ALAC",
            "album/container.m4a",
            "album/container.MP4",
            "album/container.OGA",
            "album/track.vorbis",
            "album/track.OPUS",
            "album/uncompressed.AIFC",
        ] {
            assert!(validate_remote(path, 1024).is_ok(), "{path}");
            assert!(
                !extension(path)
                    .unwrap()
                    .bytes()
                    .any(|b| b.is_ascii_uppercase())
            );
        }
        // Container admission is not permission to download executables,
        // archives, malformed names or files outside the staging quota.
        for path in [
            "album/track.dsf.exe",
            "album/track.mp4.zip",
            "album/track.vorbis ",
            "album/track.oga\n",
            "album/no-extension",
        ] {
            assert!(validate_remote(path, 1024).is_err(), "{path}");
        }
        assert!(validate_remote("album/track.dsf", MAX_CACHE as i64 + 1).is_err());
        assert!(validate_remote("album/track.dff", 0).is_err());
        assert!(extension(&format!("{}.dsf", "x".repeat(201))).is_none());
    }
}
