use super::*;
use jni::{
    JNIEnv,
    objects::{JClass, JString},
    sys::{jbyteArray, jlong, jstring},
};
use std::{
    panic::{AssertUnwindSafe, catch_unwind},
    sync::OnceLock,
};

static REGISTRY: OnceLock<Mutex<HashMap<jlong, Arc<Node>>>> = OnceLock::new();
static NEXT: AtomicU64 = AtomicU64::new(1);
fn registry() -> &'static Mutex<HashMap<jlong, Arc<Node>>> {
    REGISTRY.get_or_init(|| Mutex::new(HashMap::new()))
}
fn node(handle: jlong) -> Result<Arc<Node>> {
    registry()
        .lock()
        .map_err(err)?
        .get(&handle)
        .cloned()
        .ok_or_else(|| "invalid or closed IPFS handle".into())
}
fn string(env: &mut JNIEnv, value: &JString) -> Result<String> {
    if value.is_null() {
        return Err("null native string".into());
    }
    let text: String = env
        .get_string(value)
        .map_err(|_| "invalid native string")?
        .into();
    if text.len() > 16_384 {
        return Err("native string input too long".into());
    }
    Ok(text)
}

fn boundary<T: Default>(env: &mut JNIEnv, action: impl FnOnce(&mut JNIEnv) -> Result<T>) -> T {
    match catch_unwind(AssertUnwindSafe(|| action(env))) {
        Ok(Ok(value)) => value,
        Ok(Err(message)) => {
            if !env.exception_check().unwrap_or(true) {
                let _ = env.throw_new("java/io/IOException", message);
            }
            T::default()
        }
        Err(_) => {
            let _ = env.throw_new("java/io/IOException", "IPFS runtime panic");
            T::default()
        }
    }
}
#[unsafe(no_mangle)]
pub extern "system" fn Java_app_bitchord_soulseek_IpfsNative_create(
    mut env: JNIEnv,
    _: JClass,
    home: JString,
    peers: JString,
) -> jlong {
    boundary(&mut env, |env| {
        let home = PathBuf::from(string(env, &home).map_err(|e| format!("home input: {e}"))?);
        let peers = string(env, &peers).map_err(|e| format!("peers input: {e}"))?;
        if !home.is_absolute() {
            return Err("home must be an absolute app-private path".into());
        }
        let mut registry = registry().lock().map_err(err)?;
        if registry.len() >= 4 {
            return Err("at most four IPFS runtimes".into());
        }
        fs::create_dir_all(&home).map_err(err)?;
        let home = home.canonicalize().map_err(err)?;
        if registry
            .values()
            .any(|n| n.store.home.parent() == Some(home.as_path()))
        {
            return Err("IPFS home is already open".into());
        }
        let node = Node::create(&home, &peers, false).map_err(|e| format!("IPFS create: {e}"))?;
        let handle = NEXT.fetch_add(1, Ordering::AcqRel) as jlong;
        if handle <= 0 {
            return Err("handle space exhausted".into());
        }
        registry.insert(handle, node);
        Ok(handle)
    })
}
#[unsafe(no_mangle)]
pub extern "system" fn Java_app_bitchord_soulseek_IpfsNative_info(
    mut env: JNIEnv,
    _: JClass,
    handle: jlong,
) -> jstring {
    boundary(&mut env, |env| {
        Ok(env
            .new_string(node(handle)?.info()?)
            .map_err(err)?
            .into_raw())
    })
}
#[unsafe(no_mangle)]
pub extern "system" fn Java_app_bitchord_soulseek_IpfsNative_setPeers(
    mut env: JNIEnv,
    _: JClass,
    handle: jlong,
    peers: JString,
) {
    boundary(&mut env, |env| {
        node(handle)?.set_peers(&string(env, &peers)?)
    });
}
#[unsafe(no_mangle)]
pub extern "system" fn Java_app_bitchord_soulseek_IpfsNative_connect(
    mut env: JNIEnv,
    _: JClass,
    handle: jlong,
    peer: JString,
    addresses: JString,
    timeout_ms: i32,
) {
    boundary(&mut env, |env| {
        node(handle)?.connect(&string(env, &peer)?, &string(env, &addresses)?, timeout_ms)
    });
}
#[unsafe(no_mangle)]
pub extern "system" fn Java_app_bitchord_soulseek_IpfsNative_publish(
    mut env: JNIEnv,
    _: JClass,
    handle: jlong,
    path: JString,
) -> jstring {
    boundary(&mut env, |env| {
        let path = string(env, &path)?;
        let value = node(handle)?.publish(Path::new(&path))?;
        Ok(env.new_string(value).map_err(err)?.into_raw())
    })
}
#[unsafe(no_mangle)]
pub extern "system" fn Java_app_bitchord_soulseek_IpfsNative_unpublish(
    mut env: JNIEnv,
    _: JClass,
    handle: jlong,
    root: JString,
) {
    boundary(&mut env, |env| {
        node(handle)?.unpublish(&string(env, &root)?)
    });
}
#[unsafe(no_mangle)]
pub extern "system" fn Java_app_bitchord_soulseek_IpfsNative_grantPublication(
    mut env: JNIEnv,
    _: JClass,
    handle: jlong,
    root: JString,
    peers: JString,
) {
    boundary(&mut env, |env| {
        node(handle)?.grant_publication(&string(env, &root)?, &string(env, &peers)?)
    });
}
#[unsafe(no_mangle)]
pub extern "system" fn Java_app_bitchord_soulseek_IpfsNative_clearPrivate(
    mut env: JNIEnv,
    _: JClass,
    handle: jlong,
) -> jlong {
    boundary(&mut env, |_| Ok(node(handle)?.clear_private()? as jlong))
}
#[unsafe(no_mangle)]
pub extern "system" fn Java_app_bitchord_soulseek_IpfsNative_fetchBlock(
    mut env: JNIEnv,
    _: JClass,
    handle: jlong,
    peer: JString,
    cid: JString,
    timeout_ms: i32,
) -> jbyteArray {
    boundary(&mut env, |env| {
        let bytes =
            node(handle)?.fetch_block(&string(env, &peer)?, &string(env, &cid)?, timeout_ms)?;
        Ok(env.byte_array_from_slice(&bytes).map_err(err)?.into_raw())
    })
}
#[unsafe(no_mangle)]
pub extern "system" fn Java_app_bitchord_soulseek_IpfsNative_cancel(
    mut env: JNIEnv,
    _: JClass,
    handle: jlong,
) {
    boundary(&mut env, |_| {
        node(handle)?.cancel();
        Ok(())
    });
}
#[unsafe(no_mangle)]
pub extern "system" fn Java_app_bitchord_soulseek_IpfsNative_close(
    mut env: JNIEnv,
    _: JClass,
    handle: jlong,
) {
    boundary(&mut env, |_| {
        let value = registry().lock().map_err(err)?.remove(&handle);
        if let Some(node) = value {
            node.close();
        }
        Ok(())
    });
}
