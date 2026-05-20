//! ё-restoration using the curated yoficator safe-list.
//!
//! MVP stub: just a hand-written sample of common words to demonstrate the
//! API. The full 58K-entry table from `unabashed/yoficator` will be loaded
//! either as a compile-time PHF map (fast lookup, no runtime cost) or as a
//! .sacc-style mmap file alongside the accent dict.
//!
//! Strategy when ON (default):
//!   * tokenise input into "word" / "non-word" runs
//!   * for each word: look up its lowercase form in the table
//!   * if found, replace with the ё-form preserving the original casing
//!
//! Conservative by design — the yoficator table only contains words with
//! no e-form homograph, so blind replacement is always safe.

use std::collections::HashMap;
use once_cell::sync::Lazy;

/// Hand-curated starter set covering the words users most often hit during
/// testing. Replaced by the full table in the next iteration; kept here so
/// the JNI bridge has something to do even before the data file lands.
static SAFE_TABLE: Lazy<HashMap<&'static str, &'static str>> = Lazy::new(|| {
    let mut m = HashMap::new();
    let pairs = [
        ("елка", "ёлка"), ("еж", "ёж"), ("еще", "ещё"),
        ("ее", "её"), ("чо", "чё"),
        ("самолет", "самолёт"), ("самолета", "самолёта"),
        ("веревка", "верёвка"), ("веревки", "верёвки"), ("веревку", "верёвку"),
        ("мед", "мёд"), ("лед", "лёд"), ("клен", "клён"),
        ("аксенов", "аксёнов"), ("алферов", "алфёров"),
        ("платежка", "платёжка"), ("платеж", "платёж"),
        ("удивлен", "удивлён"), ("влюблен", "влюблён"),
        ("прочтен", "прочтён"), ("привлечен", "привлечён"),
        ("трехлетний", "трёхлетний"),
        ("надежный", "надёжный"), ("надежно", "надёжно"),
        ("идем", "идём"), ("живем", "живём"), ("ведем", "ведём"),
    ];
    for &(plain, with_yo) in &pairs {
        m.insert(plain, with_yo);
    }
    m
});

/// Run yofication over the whole text. Splits on non-letter boundaries,
/// keeps the original casing of the first character.
pub fn restore_yo(text: &str) -> String {
    let mut out = String::with_capacity(text.len() + 16);
    let mut buf = String::new();

    for ch in text.chars() {
        if is_cyrillic_letter(ch) {
            buf.push(ch);
        } else {
            if !buf.is_empty() {
                emit_word(&buf, &mut out);
                buf.clear();
            }
            out.push(ch);
        }
    }
    if !buf.is_empty() {
        emit_word(&buf, &mut out);
    }
    out
}

fn emit_word(word: &str, out: &mut String) {
    let lower: String = word.chars().flat_map(char::to_lowercase).collect();
    match SAFE_TABLE.get(lower.as_str()) {
        Some(&yo_form) => out.push_str(&apply_casing(word, yo_form)),
        None => out.push_str(word),
    }
}

/// Mirror the original word's first-letter casing onto the ё-restored form.
/// (Full title-case / all-caps handling is the same shape as the Kotlin
/// `applyCasing` helper — keeping the behaviour identical for now.)
fn apply_casing(original: &str, replacement: &str) -> String {
    let first_orig = original.chars().next();
    let first_repl = replacement.chars().next();
    match (first_orig, first_repl) {
        (Some(o), Some(r)) if o.is_uppercase() && r.is_lowercase() => {
            let mut s = String::with_capacity(replacement.len());
            for c in r.to_uppercase() { s.push(c); }
            s.push_str(&replacement[r.len_utf8()..]);
            s
        }
        _ => replacement.to_string(),
    }
}

fn is_cyrillic_letter(ch: char) -> bool {
    matches!(ch, 'а'..='я' | 'А'..='Я' | 'ё' | 'Ё')
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn restores_basic_words() {
        assert_eq!(restore_yo("елка"), "ёлка");
        assert_eq!(restore_yo("Елка"), "Ёлка");
        assert_eq!(restore_yo("веревка"), "верёвка");
    }

    #[test]
    fn preserves_punctuation() {
        assert_eq!(restore_yo("Он удивлен, и рад."), "Он удивлён, и рад.");
    }

    #[test]
    fn leaves_unknown_words_alone() {
        assert_eq!(restore_yo("привет"), "привет");
    }

    #[test]
    fn keeps_explicit_yo_intact() {
        assert_eq!(restore_yo("ёлка"), "ёлка");
    }
}
