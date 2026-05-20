//! Supertonic Rust — JNI surface for the rewritten Android TTS engine.
//!
//! Architecture (different from the upstream fork):
//!   * Kotlin layer stays thin — UI + AudioTrack + Android service shims
//!   * Rust owns text normalization, ё-restoration, lexicon, accent
//!     dictionary lookup, number normalization, AND model inference.
//!
//! That means one JNI call per sentence (`synthesize_sentence`) instead
//! of the hundreds of round-trips the upstream did to read dictionary
//! entries from Kotlin into Rust per word.
//!
//! This file is the FFI surface only. All real work lives in submodules:
//!   * `pipeline::*` — text → normalised text pipeline (ё, dict, numbers)
//!   * `engine::*`  — ORT session, voice style cache, PCM streaming
//!
//! Each JNI symbol below matches the Kotlin declarations in
//! `app/.../SupertonicRust.kt`. JNI signatures are mangled from the
//! fully-qualified Java class name, so the namespace must stay in sync.

mod pipeline;
mod engine;

use jni::JNIEnv;
use jni::objects::{JClass, JString};
use jni::sys::{jboolean, jint, jlong, JNI_FALSE, JNI_TRUE};
use log::{info, warn};

/// Initialise Android logger so `log::info!` etc. land in logcat under
/// the `SupertonicRust` tag. Called once from Kotlin at process start.
#[no_mangle]
pub extern "system" fn Java_com_davnozdu_supertonicrust_SupertonicRust_initLogger(
    _env: JNIEnv,
    _class: JClass,
) {
    android_logger::init_once(
        android_logger::Config::default()
            .with_max_level(log::LevelFilter::Info)
            .with_tag("SupertonicRust"),
    );
    info!("Logger initialised");
}

/// Returns the build version of the native library so the Kotlin side can
/// surface it in About / settings. Cheap smoke test the JNI bridge works.
#[no_mangle]
pub extern "system" fn Java_com_davnozdu_supertonicrust_SupertonicRust_nativeVersion<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
) -> jni::objects::JString<'local> {
    let v = env!("CARGO_PKG_VERSION");
    env.new_string(v).expect("new_string failed")
}

/// Load the accent dictionary from disk (mmap). Returns 1 on success, 0 on
/// failure (file missing, bad header, etc.). MVP stub: just confirms the
/// JNI bridge accepts a path. Real mmap landing in the next commit.
#[no_mangle]
pub extern "system" fn Java_com_davnozdu_supertonicrust_SupertonicRust_loadAccentDictionary(
    mut env: JNIEnv,
    _class: JClass,
    path: JString,
) -> jboolean {
    let path: String = match env.get_string(&path) {
        Ok(s) => s.into(),
        Err(_) => return JNI_FALSE,
    };
    info!("loadAccentDictionary stub: {}", path);
    // TODO: open as memmap, validate header, store in global state.
    JNI_FALSE
}

/// Initialise the inference engine. `model_path` is the directory holding
/// the four ONNX files; `lib_path` is the absolute path to
/// libonnxruntime.so on the device. Returns a handle (pointer cast to
/// long) — 0 on failure.
#[no_mangle]
pub extern "system" fn Java_com_davnozdu_supertonicrust_SupertonicRust_initEngine(
    mut env: JNIEnv,
    _class: JClass,
    model_path: JString,
    lib_path: JString,
) -> jlong {
    let model_path: String = match env.get_string(&model_path) {
        Ok(s) => s.into(),
        Err(_) => return 0,
    };
    let lib_path: String = match env.get_string(&lib_path) {
        Ok(s) => s.into(),
        Err(_) => return 0,
    };
    info!("initEngine stub: model={}, lib={}", model_path, lib_path);
    // TODO: build SupertonicEngine, box it, return the raw pointer as long.
    // For now return a non-zero sentinel so the Kotlin side believes init
    // succeeded — actual synthesis still returns silence.
    1_i64
}

/// Apply the text pipeline to `text` and return the result. Pure CPU work,
/// no audio yet — used for unit-testing the pipeline from Kotlin (and for
/// the planned in-app debug view that shows users exactly what the engine
/// will receive).
#[no_mangle]
pub extern "system" fn Java_com_davnozdu_supertonicrust_SupertonicRust_processText<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    text: JString<'local>,
) -> jni::objects::JString<'local> {
    let input: String = match env.get_string(&text) {
        Ok(s) => s.into(),
        Err(_) => return env.new_string("").expect("new_string"),
    };
    // For now just yofication is wired up; lexicon/numbers/dict pending.
    let yo = pipeline::yofication::restore_yo(&input);
    env.new_string(&yo).expect("new_string failed")
}

/// Placeholder for the streaming synthesis entry point. Returns an empty
/// byte array until the engine is wired up.
#[no_mangle]
pub extern "system" fn Java_com_davnozdu_supertonicrust_SupertonicRust_synthesize<'local>(
    env: JNIEnv<'local>,
    _class: JClass<'local>,
    _engine: jlong,
    _text: JString<'local>,
    _voice_path: JString<'local>,
    _speed: jni::sys::jfloat,
    _steps: jint,
) -> jni::objects::JByteArray<'local> {
    warn!("synthesize stub: returning empty PCM (engine integration pending)");
    env.new_byte_array(0).expect("new_byte_array failed")
}
