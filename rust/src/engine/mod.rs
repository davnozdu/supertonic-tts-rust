//! ORT inference engine — stub for MVP.
//!
//! The full Supertonic v3 inference logic (Encoder / DurationPredictor /
//! Decoder / Diffusion) lives upstream in supertonic-android/rust/src/.
//! We'll port + adapt it in the next step once the scaffold is verified
//! to compile and CI is green.

#![allow(dead_code)]

pub struct Engine {
    // ORT session, voice style cache, sample rate, ...
}

impl Engine {
    pub fn init(_model_path: &str, _lib_path: &str) -> anyhow::Result<Self> {
        Err(anyhow::anyhow!("engine not yet implemented in MVP scaffold"))
    }
}
