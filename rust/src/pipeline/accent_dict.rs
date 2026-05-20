//! Memory-mapped `.sacc` accent dictionary reader.
//!
//! Same on-disk format as the upstream fork's BinaryAccentDictionary, so we
//! can keep using the existing `davnozdu/supertonic-dictionaries` releases
//! without rebuilding. The bin layout:
//!
//! ```text
//! Header (28 bytes, little-endian):
//!   0:  magic "SACC"
//!   4:  version u32 = 1
//!   8:  entry_count u32
//!   12: offsets_offset u64 (= 28)
//!   20: data_offset u64
//!
//! Offsets[entry_count] u32 — each is an offset relative to data_offset
//!
//! Data: u16 key_len, u16 val_len, key_bytes, val_bytes (per entry, sorted
//! by lowercased UTF-8 byte order)
//! ```
//!
//! Stub for now — full mmap reader lands once we have a working JNI flow.

#![allow(dead_code)]

use std::path::Path;

pub struct AccentDictionary {
    // mmap handle goes here
}

impl AccentDictionary {
    /// Open the file at `path` as a mmap'd .sacc dictionary. Returns None
    /// on any I/O / format error so the caller can fall back gracefully.
    pub fn open(_path: &Path) -> Option<Self> {
        // TODO: memmap2, validate header, store offsets+data offsets
        None
    }

    /// Look up `lower_key` (lowercased Cyrillic word). Returns the stressed
    /// form, or None if not in the dict. Sub-microsecond per call.
    pub fn lookup(&self, _lower_key: &[u8]) -> Option<&str> {
        None
    }
}
