//! Text pipeline modules. Each step is a function `&str -> String` so they
//! can be chained or skipped independently.
//!
//! Intended order at synthesis time:
//!   1. lexicon — user-curated overrides (highest priority)
//!   2. yofication — restore ё in unambiguous safe contexts
//!   3. numbers_ru — spell out Russian digits as words
//!   4. accent_dict — per-word stress mark insertion (via mmap)
//!
//! See `lib.rs::processText` for how Kotlin reaches these.

pub mod yofication;
pub mod lexicon;
pub mod numbers_ru;
pub mod accent_dict;
