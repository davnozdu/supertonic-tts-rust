//! User-curated word overrides — highest priority in the pipeline.
//! Stub for now; real implementation lands once the JNI surface for
//! `setLexicon(json)` is wired up from Kotlin.

#![allow(dead_code)]

use std::collections::HashMap;

/// Replace every occurrence of `term` with its `replacement`. Case-sensitive
/// for now; case-insensitive matching arrives with the real implementation.
pub fn apply(text: &str, rules: &HashMap<String, String>) -> String {
    if rules.is_empty() {
        return text.to_string();
    }
    let mut out = text.to_string();
    for (term, replacement) in rules {
        out = out.replace(term, replacement);
    }
    out
}
