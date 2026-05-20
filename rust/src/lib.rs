//! Supertonic Rust — JNI surface for the rewritten Android TTS engine.
//!
//! Architecture (different from the upstream fork):
//!   * Kotlin layer stays thin — UI + AudioTrack + Android service shims
//!   * Rust owns text normalisation, ё-restoration, lexicon, accent
//!     dictionary lookup, number normalisation, AND model inference.
//!
//! All real work lives in submodules:
//!   * `pipeline::*` — text → normalised text pipeline (ё, dict, numbers)
//!   * `engine::*`  — ORT session, voice style cache, PCM streaming
//!
//! Each JNI symbol below matches the Kotlin declarations in
//! `app/.../SupertonicRust.kt`. JNI signatures are mangled from the
//! fully-qualified Java class name, so the namespace must stay in sync
//! with `com.davnozdu.supertonicrust.SupertonicRust`.

mod engine;
mod pipeline;

use android_logger::Config;
use jni::objects::{JClass, JObject, JString, JValue};
use jni::sys::{jboolean, jbyteArray, jfloat, jint, jlong, JNI_FALSE, JNI_TRUE};
use jni::JNIEnv;
use log::LevelFilter;
use std::panic;
use std::time::Instant;

use engine::{
    load_and_mix_voice_styles, load_text_to_speech, load_voice_style, SupertonicEngine,
    UnifiedThermalManager,
};

/// Initialise Android logger so `log::info!` etc. land in logcat under
/// the `SupertonicRust` tag. Safe to call multiple times.
#[no_mangle]
pub extern "system" fn Java_com_davnozdu_supertonicrust_SupertonicRust_initLogger(
    _env: JNIEnv,
    _class: JClass,
) {
    android_logger::init_once(
        Config::default()
            .with_max_level(LevelFilter::Info)
            .with_tag("SupertonicRust"),
    );

    // Don't log panic payload (may contain user text/PII).
    panic::set_hook(Box::new(|panic_info| {
        let location = panic_info
            .location()
            .map(|l| format!("{}:{}:{}", l.file(), l.line(), l.column()))
            .unwrap_or_else(|| "unknown location".to_string());
        log::error!("RUST PANIC at {}", location);
    }));

    log::info!("Logger initialised");
}

/// Returns the crate version so the Kotlin side can surface it.
#[no_mangle]
pub extern "system" fn Java_com_davnozdu_supertonicrust_SupertonicRust_nativeVersion<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
) -> jni::objects::JString<'local> {
    let v = env!("CARGO_PKG_VERSION");
    env.new_string(v).expect("new_string failed")
}

/// Apply the text pipeline (currently just ё-restoration) and return
/// the result. Pure CPU, no model required. Useful for unit-testing
/// the pipeline from Kotlin.
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
    let yo = pipeline::yofication::restore_yo(&input);
    env.new_string(&yo).expect("new_string failed")
}

/// Load the accent dictionary from disk. MVP stub: returns false until
/// the .sacc reader is ported. Kept in the JNI surface so the Kotlin
/// side can be wired up now.
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
    log::info!("loadAccentDictionary stub: {}", path);
    JNI_FALSE
}

/// Initialise the ORT engine. `model_path` is the directory holding
/// the four ONNX files plus `tts.json`/`unicode_indexer.json`. The
/// Kotlin side resolves the absolute path inside `filesDir`.
///
/// Returns a handle (raw pointer cast to long); 0 means failure.
#[no_mangle]
pub extern "system" fn Java_com_davnozdu_supertonicrust_SupertonicRust_initEngine(
    mut env: JNIEnv,
    _class: JClass,
    model_path: JString,
    _lib_path: JString,
    ort_threads: jint,
    xnn_threads: jint,
) -> jlong {
    let model_path: String = match env.get_string(&model_path) {
        Ok(s) => s.into(),
        Err(_) => return 0,
    };

    log::info!(
        "initEngine: model={}, ort_threads={}, xnn_threads={}",
        model_path,
        ort_threads,
        xnn_threads
    );

    if !ort::init().with_name("supertonic-rust").commit() {
        log::warn!("ORT environment already initialised");
    }

    let tts = match load_text_to_speech(
        &model_path,
        false,
        true,
        ort_threads as usize,
        xnn_threads as usize,
    ) {
        Ok(t) => t,
        Err(e) => {
            log::error!("Failed to load TTS: {:?}", e);
            return 0;
        }
    };

    let engine = SupertonicEngine {
        tts,
        thermal: UnifiedThermalManager::new(),
        last_rtf: 1.0,
    };

    Box::into_raw(Box::new(engine)) as jlong
}

/// Streaming synthesis. The audio is delivered chunk-by-chunk via
/// `notifyAudioChunk([B)V` on the calling Kotlin object; progress goes
/// through `notifyProgress(II)V`; cancellation is polled via
/// `isCancelled()Z`. Returns the final PCM (16-bit LE mono) as a single
/// `byte[]` matching the upstream behaviour.
#[no_mangle]
pub extern "system" fn Java_com_davnozdu_supertonicrust_SupertonicRust_synthesize(
    mut env: JNIEnv,
    instance: JObject,
    ptr: jlong,
    text: JString,
    lang: JString,
    style_path: JString,
    speed: jfloat,
    buffer_seconds: jfloat,
    steps: jint,
    gain: jfloat,
) -> jbyteArray {
    if ptr == 0 {
        log::error!("synthesize called with null engine handle");
        return env.new_byte_array(0).unwrap().into_raw();
    }
    let engine = unsafe { &mut *(ptr as *mut SupertonicEngine) };

    let text: String = env.get_string(&text).expect("text").into();
    let lang: String = env.get_string(&lang).expect("lang").into();
    let style_path: String = env.get_string(&style_path).expect("style_path").into();

    engine.thermal.update(buffer_seconds, engine.last_rtf);

    let style = if style_path.contains(';') {
        let parts: Vec<&str> = style_path.split(';').collect();
        if parts.len() == 3 {
            let alpha = parts[2].parse::<f32>().unwrap_or(0.5);
            match load_and_mix_voice_styles(parts[0], parts[1], alpha) {
                Ok(s) => s,
                Err(e) => {
                    log::error!("Failed to mix voice styles: {:?}", e);
                    return env.new_byte_array(0).unwrap().into_raw();
                }
            }
        } else {
            log::error!("Invalid mix format. Expected: path1;path2;alpha");
            return env.new_byte_array(0).unwrap().into_raw();
        }
    } else {
        match load_voice_style(&[style_path], false) {
            Ok(s) => s,
            Err(e) => {
                log::error!("Failed to load voice style: {:?}", e);
                return env.new_byte_array(0).unwrap().into_raw();
            }
        }
    };

    let start = Instant::now();
    let mut last_progress_call = Instant::now();

    let result = engine.tts.call(
        &text,
        &lang,
        &style,
        steps as usize,
        speed,
        0.02,
        |curr, total, audio_chunk| {
            // Check for cancellation via the Kotlin callback object.
            let is_cancelled = env
                .call_method(&instance, "isCancelled", "()Z", &[])
                .ok()
                .and_then(|v| v.z().ok())
                .unwrap_or(false);
            if is_cancelled {
                return false;
            }

            if let Some(audio) = audio_chunk {
                let mut pcm = Vec::with_capacity(audio.len() * 2);
                for &sample in audio {
                    let clamped = (sample * gain).clamp(-1.0, 1.0);
                    let val = (clamped * 32767.0) as i16;
                    pcm.extend_from_slice(&val.to_le_bytes());
                }
                let output = env.new_byte_array(pcm.len() as i32).unwrap();
                env.set_byte_array_region(
                    &output,
                    0,
                    pcm.iter().map(|&b| b as i8).collect::<Vec<_>>().as_slice(),
                )
                .unwrap();

                let _ = env.call_method(
                    &instance,
                    "notifyAudioChunk",
                    "([B)V",
                    &[JValue::Object(&output)],
                );
            }

            if curr == 0
                || curr == total
                || audio_chunk.is_some()
                || last_progress_call.elapsed().as_millis() > 100
            {
                let _ = env.call_method(
                    &instance,
                    "notifyProgress",
                    "(II)V",
                    &[JValue::Int(curr as i32), JValue::Int(total as i32)],
                );
                last_progress_call = Instant::now();
            }
            true
        },
    );

    match result {
        Ok((wav, duration)) => {
            let elapsed = start.elapsed().as_secs_f32();
            if duration > 0.0 {
                engine.last_rtf = duration / elapsed;
                log::info!(
                    "Inference RTF: {:.2}x ({:.2}s audio in {:.2}s)",
                    engine.last_rtf,
                    duration,
                    elapsed
                );
            }
            let mut pcm = Vec::with_capacity(wav.len() * 2);
            for &sample in &wav {
                let clamped = (sample * gain).clamp(-1.0, 1.0);
                let val = (clamped * 32767.0) as i16;
                pcm.extend_from_slice(&val.to_le_bytes());
            }
            let output = env.new_byte_array(pcm.len() as i32).unwrap();
            env.set_byte_array_region(
                &output,
                0,
                pcm.iter().map(|&b| b as i8).collect::<Vec<_>>().as_slice(),
            )
            .unwrap();
            output.into_raw()
        }
        Err(e) => {
            log::error!("Synthesis failed: {:?}", e);
            env.new_byte_array(0).unwrap().into_raw()
        }
    }
}

#[no_mangle]
pub extern "system" fn Java_com_davnozdu_supertonicrust_SupertonicRust_getSampleRate(
    _env: JNIEnv,
    _class: JClass,
    ptr: jlong,
) -> jint {
    if ptr == 0 {
        return 24000;
    }
    let engine = unsafe { &*(ptr as *mut SupertonicEngine) };
    engine.tts.sample_rate as jint
}

#[no_mangle]
pub extern "system" fn Java_com_davnozdu_supertonicrust_SupertonicRust_getSocClass(
    _env: JNIEnv,
    _class: JClass,
    ptr: jlong,
) -> jint {
    if ptr == 0 {
        return -1;
    }
    let engine = unsafe { &mut *(ptr as *mut SupertonicEngine) };
    match engine.thermal.get_soc_class() {
        engine::SocClass::Flagship => 3,
        engine::SocClass::HighEnd => 2,
        engine::SocClass::MidRange => 1,
        engine::SocClass::LowEnd => 0,
    }
}

#[no_mangle]
pub extern "system" fn Java_com_davnozdu_supertonicrust_SupertonicRust_reset(
    _env: JNIEnv,
    _class: JClass,
    ptr: jlong,
) {
    if ptr != 0 {
        let engine = unsafe { &mut *(ptr as *mut SupertonicEngine) };
        engine.last_rtf = 1.0;
        log::info!("Engine state reset");
    }
}

#[no_mangle]
pub extern "system" fn Java_com_davnozdu_supertonicrust_SupertonicRust_isXnnpackEnabled(
    _env: JNIEnv,
    _class: JClass,
) -> jboolean {
    #[cfg(feature = "xnnpack")]
    {
        JNI_TRUE
    }
    #[cfg(not(feature = "xnnpack"))]
    {
        JNI_FALSE
    }
}

#[no_mangle]
pub extern "system" fn Java_com_davnozdu_supertonicrust_SupertonicRust_close(
    _env: JNIEnv,
    _class: JClass,
    ptr: jlong,
) {
    if ptr != 0 {
        unsafe {
            let _ = Box::from_raw(ptr as *mut SupertonicEngine);
        }
        log::info!("Engine closed");
    }
}
