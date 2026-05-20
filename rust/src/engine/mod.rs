//! ORT inference engine — Supertonic v3 pipeline.
//!
//! Ported from the upstream fork (supertonic-android/rust). The actual
//! per-stage inference (text encoder → duration predictor → diffusion →
//! vocoder) lives in `inference.rs`; this file is the public face of the
//! engine to JNI and bundles thermal-aware scheduling.

pub mod inference;
pub mod thermal;

pub use inference::{
    load_and_mix_voice_styles, load_text_to_speech, load_voice_style, Style, TextToSpeech,
};
pub use thermal::{SocClass, UnifiedThermalManager};

/// Top-level handle held by the JNI bridge. Boxing this and returning
/// the raw pointer as a `jlong` lets us hold the (large) ORT sessions
/// alive across multiple `synthesize` calls.
pub struct SupertonicEngine {
    pub tts: TextToSpeech,
    pub thermal: UnifiedThermalManager,
    pub last_rtf: f32,
}
