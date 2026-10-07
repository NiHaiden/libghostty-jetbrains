//! JNI entry points for `com.github.nihaiden.ghostty.vt.GhosttyNative`.
//!
//! Java never sees a pointer: terminals live in a registry and Java holds an
//! opaque `long` id. A stale or bogus id raises `IllegalStateException`
//! instead of touching freed memory. Each call locks its terminal, runs, then
//! releases the lock *before* reporting events to the Java listener, so a
//! listener may call back into the same terminal.

use crate::term::{PasteResult, Term};
use crate::vt::{Event, KeyInput, MouseInput};
use jni::errors::{Error, Result};
use jni::objects::{JByteArray, JClass, JIntArray, JObject, JString, JValue};
use jni::refs::{Global, Reference};
use jni::strings::JNIString;
use jni::sys::{jboolean, jdouble, jfloat, jint, jlong};
use jni::{Env, jni_sig, jni_str, native_method};
use std::collections::HashMap;
use std::sync::atomic::{AtomicI64, Ordering};
use std::sync::{Arc, LazyLock, Mutex, MutexGuard};

/// Bumped whenever the Java-facing surface changes; checked at load time.
const ABI_VERSION: jint = 2;

const EVENT_WRITE_PTY: jint = 1;
const EVENT_BELL: jint = 2;
const EVENT_TITLE: jint = 3;
const EVENT_PWD: jint = 4;
const EVENT_CLIPBOARD_WRITE: jint = 5;
const EVENT_NOTIFICATION: jint = 6;
const EVENT_PROGRESS: jint = 7;
const EVENT_COMMAND_FINISHED: jint = 8;

struct Entry {
    term: Mutex<Term>,
    /// Receives `onEvent(int type, int value, byte[] data)`.
    listener: Global<JObject<'static>>,
}

static REGISTRY: LazyLock<Mutex<HashMap<jlong, Arc<Entry>>>> = LazyLock::new(Default::default);
static NEXT_ID: AtomicI64 = AtomicI64::new(1);

/// A panic while holding a lock (caught by the JNI wrapper) must not wedge
/// the terminal forever; the state it protects stays memory-safe.
fn lock<T>(m: &Mutex<T>) -> MutexGuard<'_, T> {
    m.lock().unwrap_or_else(|e| e.into_inner())
}

fn entry(env: &mut Env<'_>, handle: jlong) -> Result<Arc<Entry>> {
    if let Some(e) = lock(&REGISTRY).get(&handle) {
        return Ok(Arc::clone(e));
    }
    env.throw_new(
        jni_str!("java/lang/IllegalStateException"),
        JNIString::new(format!("ghostty terminal {handle} is closed")),
    )?;
    Err(Error::JavaException)
}

/// Runs `f` on the terminal, then delivers the events it produced.
fn with_term<R>(env: &mut Env<'_>, handle: jlong, f: impl FnOnce(&mut Term) -> R) -> Result<R> {
    let entry = entry(env, handle)?;
    let (result, events) = {
        let mut term = lock(&entry.term);
        let result = f(&mut term);
        (result, term.take_events())
    };
    dispatch(env, &entry.listener, events)?;
    Ok(result)
}

fn dispatch(env: &mut Env<'_>, listener: &JObject<'_>, events: Vec<Event>) -> Result<()> {
    for event in events {
        let (kind, value, data): (jint, jint, Option<Vec<u8>>) = match event {
            Event::WritePty(bytes) => (EVENT_WRITE_PTY, 0, Some(bytes)),
            Event::Bell => (EVENT_BELL, 0, None),
            Event::TitleChanged(s) => (EVENT_TITLE, 0, Some(s.into_bytes())),
            Event::PwdChanged(s) => (EVENT_PWD, 0, Some(s.into_bytes())),
            Event::ClipboardWrite { text, primary } => {
                (EVENT_CLIPBOARD_WRITE, primary as jint, Some(text.into_bytes()))
            }
            Event::Notification { title, body } => {
                let mut data = title.into_bytes();
                data.push(0);
                data.extend_from_slice(body.as_bytes());
                (EVENT_NOTIFICATION, 0, Some(data))
            }
            Event::Progress { state, progress } => {
                let progress = progress.map_or(0xff, |p| p.min(100) as jint);
                (EVENT_PROGRESS, (state as jint) << 8 | progress, None)
            }
            Event::CommandFinished { exit_code } => (EVENT_COMMAND_FINISHED, exit_code.unwrap_or(jint::MIN), None),
        };
        let array = match data {
            Some(bytes) => env.byte_array_from_slice(&bytes)?,
            None => JByteArray::null(),
        };
        env.call_method(
            listener,
            jni_str!("onEvent"),
            jni_sig!((kind: jint, value: jint, data: jbyte[]) -> void),
            &[JValue::Int(kind), JValue::Int(value), JValue::Object(array.as_ref())],
        )?;
    }
    Ok(())
}

fn opt_string(env: &Env<'_>, s: &JString<'_>) -> Result<Option<String>> {
    if s.is_null() { Ok(None) } else { s.try_to_string(env).map(Some) }
}

fn new_string<'local>(env: &mut Env<'local>, s: Option<String>) -> Result<JString<'local>> {
    match s {
        Some(s) => JString::from_str(env, s),
        None => Ok(JString::null()),
    }
}

fn color(c: jint) -> Option<u32> {
    (c >= 0).then_some(c as u32 & 0xff_ffff)
}

fn mods(m: jint) -> u16 {
    (m & 0xffff) as u16
}

// ---- Exports -----------------------------------------------------------------
//
// Each `native_method!` generates the exported JNI symbol plus a wrapper that
// catches panics and turns `Err` into a Java exception.

macro_rules! export {
    ($($sig:tt)*) => {
        // jni 0.22.4's generated wrapper calls `AtomicBool::fetch_update`,
        // deprecated since Rust 1.99; nothing to fix on our side.
        #[allow(deprecated)]
        const _: () = {
            let _ = native_method! {
                java_type = "com.github.nihaiden.ghostty.vt.GhosttyNative",
                static extern $($sig)*,
            };
        };
    };
}

export!(fn abi_version() -> jint);
export!(fn create(cols: jint, rows: jint, listener: JObject) -> jlong);
export!(fn destroy(handle: jlong) -> void);
export!(fn write(handle: jlong, data: jbyte[], offset: jint, len: jint) -> void);
export!(fn reset(handle: jlong) -> void);
export!(fn resize(handle: jlong, cols: jint, rows: jint, cell_w: jint, cell_h: jint, pad_left: jint, pad_top: jint) -> jboolean);
export!(fn set_colors(handle: jlong, fg: jint, bg: jint, cursor: jint, palette: jint[], generate256: jboolean) -> void);
export!(fn set_option(handle: jlong, option: jint, value: jlong) -> jboolean);
export!(fn query(handle: jlong, what: jint, arg: jint) -> jlong);
export!(fn query_string(handle: jlong, what: jint) -> JString);
export!(fn scroll(handle: jlong, kind: jint, value: jlong) -> void);
export!(fn invalidate(handle: jlong) -> void);
export!(fn snapshot(handle: jlong) -> jint[]);
export!(fn encode_key(handle: jlong, action: jint, key: jint, mods: jint, consumed: jint, unshifted: jint, text: JString) -> jbyte[]);
export!(fn encode_mouse(handle: jlong, action: jint, button: jint, mods: jint, x: jfloat, y: jfloat, any_pressed: jboolean) -> jbyte[]);
export!(fn encode_focus(handle: jlong, gained: jboolean) -> jbyte[]);
export!(fn paste(handle: jlong, text: JString, allow_unsafe: jboolean) -> jint);
export!(fn select_event(handle: jlong, kind: jint, x: jdouble, y: jdouble, time_ns: jlong, rectangle: jboolean) -> jint);
export!(fn select_clear(handle: jlong) -> void);
export!(fn select_all(handle: jlong) -> void);
export!(fn selection_text(handle: jlong) -> JString);
export!(fn screen_text(handle: jlong) -> JString);
export!(fn hyperlink_at(handle: jlong, col: jint, row: jint) -> JString);
export!(fn search_set(handle: jlong, needle: JString) -> jboolean);
export!(fn search_select(handle: jlong, dir: jint) -> jint);

type Cls<'l> = JClass<'l>;

fn abi_version(_env: &mut Env<'_>, _: Cls<'_>) -> Result<jint> {
    Ok(ABI_VERSION)
}

fn create<'l>(env: &mut Env<'l>, _: Cls<'l>, cols: jint, rows: jint, listener: JObject<'l>) -> Result<jlong> {
    if listener.is_null() {
        env.throw_new(jni_str!("java/lang/NullPointerException"), jni_str!("listener"))?;
        return Err(Error::JavaException);
    }
    let Ok(term) = Term::new(cols, rows) else {
        env.throw_new(jni_str!("java/lang/OutOfMemoryError"), jni_str!("libghostty: cannot create terminal"))?;
        return Err(Error::JavaException);
    };
    let entry = Entry { term: Mutex::new(term), listener: env.new_global_ref(listener)? };
    let id = NEXT_ID.fetch_add(1, Ordering::Relaxed);
    lock(&REGISTRY).insert(id, Arc::new(entry));
    Ok(id)
}

/// Idempotent. A call still running on another thread keeps the terminal
/// alive until it returns.
fn destroy(_env: &mut Env<'_>, _: Cls<'_>, handle: jlong) -> Result<()> {
    let removed = lock(&REGISTRY).remove(&handle);
    drop(removed);
    Ok(())
}

fn write<'l>(
    env: &mut Env<'l>,
    _: Cls<'l>,
    handle: jlong,
    data: JByteArray<'l>,
    offset: jint,
    len: jint,
) -> Result<()> {
    let total = data.len(env)?;
    let (Ok(offset), Ok(len)) = (usize::try_from(offset), usize::try_from(len)) else {
        return Err(Error::JniCall(jni::errors::JniError::InvalidArguments));
    };
    if offset.checked_add(len).is_none_or(|end| end > total) {
        env.throw_new(jni_str!("java/lang/IndexOutOfBoundsException"), jni_str!("write: offset/len out of range"))?;
        return Err(Error::JavaException);
    }
    if len == 0 {
        return Ok(());
    }
    let mut buf = vec![0i8; len];
    data.get_region(env, offset as jint, &mut buf)?;
    let bytes: Vec<u8> = buf.into_iter().map(|b| b as u8).collect();
    with_term(env, handle, |t| t.write(&bytes))
}

fn reset(env: &mut Env<'_>, _: Cls<'_>, handle: jlong) -> Result<()> {
    with_term(env, handle, Term::reset)
}

#[allow(clippy::too_many_arguments)]
fn resize(
    env: &mut Env<'_>,
    _: Cls<'_>,
    handle: jlong,
    cols: jint,
    rows: jint,
    cell_w: jint,
    cell_h: jint,
    pad_left: jint,
    pad_top: jint,
) -> Result<jboolean> {
    with_term(env, handle, |t| t.resize(cols, rows, cell_w, cell_h, pad_left, pad_top).is_ok())
}

#[allow(clippy::too_many_arguments)]
fn set_colors<'l>(
    env: &mut Env<'l>,
    _: Cls<'l>,
    handle: jlong,
    fg: jint,
    bg: jint,
    cursor: jint,
    palette: JIntArray<'l>,
    generate256: jboolean,
) -> Result<()> {
    let palette = if palette.is_null() {
        None
    } else {
        let mut buf = vec![0; palette.len(env)?.min(256)];
        palette.get_region(env, 0, &mut buf)?;
        Some(buf)
    };
    with_term(env, handle, |t| t.set_colors(color(fg), color(bg), color(cursor), palette.as_deref(), generate256))
}

fn set_option(env: &mut Env<'_>, _: Cls<'_>, handle: jlong, option: jint, value: jlong) -> Result<jboolean> {
    with_term(env, handle, |t| t.set_option(option, value))
}

fn query(env: &mut Env<'_>, _: Cls<'_>, handle: jlong, what: jint, arg: jint) -> Result<jlong> {
    with_term(env, handle, |t| t.query(what, arg))
}

/// what: 1 title, 2 working directory.
fn query_string<'l>(env: &mut Env<'l>, _: Cls<'l>, handle: jlong, what: jint) -> Result<JString<'l>> {
    let s = with_term(env, handle, |t| match what {
        1 => Some(t.title()),
        2 => Some(t.pwd()),
        _ => None,
    })?;
    new_string(env, s)
}

fn scroll(env: &mut Env<'_>, _: Cls<'_>, handle: jlong, kind: jint, value: jlong) -> Result<()> {
    with_term(env, handle, |t| t.scroll(kind, value))
}

fn invalidate(env: &mut Env<'_>, _: Cls<'_>, handle: jlong) -> Result<()> {
    with_term(env, handle, Term::invalidate)
}

/// The frame (see `frame.rs`), or null when nothing changed.
fn snapshot<'l>(env: &mut Env<'l>, _: Cls<'l>, handle: jlong) -> Result<JIntArray<'l>> {
    let frame = with_term(env, handle, |t| t.snapshot().map(<[i32]>::to_vec))?;
    let Some(frame) = frame else { return Ok(JIntArray::null()) };
    let array = JIntArray::new(env, frame.len())?;
    array.set_region(env, 0, &frame)?;
    Ok(array)
}

#[allow(clippy::too_many_arguments)]
fn encode_key<'l>(
    env: &mut Env<'l>,
    _: Cls<'l>,
    handle: jlong,
    action: jint,
    key: jint,
    mods_: jint,
    consumed: jint,
    unshifted: jint,
    text: JString<'l>,
) -> Result<JByteArray<'l>> {
    let text = opt_string(env, &text)?;
    let input = KeyInput {
        action,
        key,
        mods: mods(mods_),
        consumed_mods: mods(consumed),
        unshifted: unshifted.max(0) as u32,
        text: text.as_deref().filter(|s| !s.is_empty()),
    };
    let bytes = with_term(env, handle, |t| t.encode_key(&input))?;
    env.byte_array_from_slice(&bytes)
}

#[allow(clippy::too_many_arguments)]
fn encode_mouse<'l>(
    env: &mut Env<'l>,
    _: Cls<'l>,
    handle: jlong,
    action: jint,
    button: jint,
    mods_: jint,
    x: jfloat,
    y: jfloat,
    any_pressed: jboolean,
) -> Result<JByteArray<'l>> {
    let input = MouseInput { action, button, mods: mods(mods_), x, y, any_button_pressed: any_pressed };
    let bytes = with_term(env, handle, |t| t.encode_mouse(&input))?;
    env.byte_array_from_slice(&bytes)
}

fn encode_focus<'l>(env: &mut Env<'l>, _: Cls<'l>, handle: jlong, gained: jboolean) -> Result<JByteArray<'l>> {
    let bytes = with_term(env, handle, |t| t.encode_focus(gained))?;
    env.byte_array_from_slice(&bytes)
}

/// 0 ok (bytes arrive as a WRITE_PTY event), 1 rejected as unsafe, -1 error.
fn paste<'l>(env: &mut Env<'l>, _: Cls<'l>, handle: jlong, text: JString<'l>, allow_unsafe: jboolean) -> Result<jint> {
    let Some(text) = opt_string(env, &text)? else { return Ok(-1) };
    with_term(env, handle, |t| match t.paste(&text, allow_unsafe) {
        PasteResult::Ok => 0,
        PasteResult::Unsafe => 1,
        PasteResult::Error => -1,
    })
}

#[allow(clippy::too_many_arguments)]
fn select_event(
    env: &mut Env<'_>,
    _: Cls<'_>,
    handle: jlong,
    kind: jint,
    x: jdouble,
    y: jdouble,
    time_ns: jlong,
    rectangle: jboolean,
) -> Result<jint> {
    with_term(env, handle, |t| t.select_event(kind, x, y, time_ns, rectangle))
}

fn select_clear(env: &mut Env<'_>, _: Cls<'_>, handle: jlong) -> Result<()> {
    with_term(env, handle, Term::clear_selection)
}

fn select_all(env: &mut Env<'_>, _: Cls<'_>, handle: jlong) -> Result<()> {
    with_term(env, handle, Term::select_all)
}

fn selection_text<'l>(env: &mut Env<'l>, _: Cls<'l>, handle: jlong) -> Result<JString<'l>> {
    let s = with_term(env, handle, |t| t.selection_text())?;
    new_string(env, s)
}

fn screen_text<'l>(env: &mut Env<'l>, _: Cls<'l>, handle: jlong) -> Result<JString<'l>> {
    let s = with_term(env, handle, |t| t.screen_text())?;
    new_string(env, s)
}

fn hyperlink_at<'l>(env: &mut Env<'l>, _: Cls<'l>, handle: jlong, col: jint, row: jint) -> Result<JString<'l>> {
    let s = with_term(env, handle, |t| t.hyperlink_at(col, row))?;
    new_string(env, s)
}

fn search_set<'l>(env: &mut Env<'l>, _: Cls<'l>, handle: jlong, needle: JString<'l>) -> Result<jboolean> {
    let needle = opt_string(env, &needle)?;
    with_term(env, handle, |t| t.set_search(needle.as_deref()))
}

fn search_select(env: &mut Env<'_>, _: Cls<'_>, handle: jlong, dir: jint) -> Result<jint> {
    with_term(env, handle, |t| t.search_select(dir))
}
