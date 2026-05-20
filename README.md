# Supertonic Rust

A from-scratch rewrite of the Supertonic TTS Android engine, focused on
**speed**, **ё-restoration**, and **stress marks for Russian**.

> This is *not* the same app as
> [`davnozdu/supertonic-android`](https://github.com/davnozdu/supertonic-android).
> Different `applicationId` (`com.davnozdu.supertonicrust`), different
> launcher icon — installs side-by-side with the original.

## Why a rewrite

The upstream fork has the bulk of its text processing in Kotlin (regex,
Pattern.compile, ByteBuffer reads through JNI). That means hundreds of
JNI round-trips per sentence and noticeable GC pressure on the audio
thread on weak SoCs. This rewrite flips it:

* **Kotlin** owns Android lifecycle, AudioTrack, and the UI — nothing
  more.
* **Rust** does ё-restoration, lexicon overrides, number spelling,
  accent dictionary mmap lookups, **and** ONNX inference.
* One JNI call per sentence (`synthesize`), one callback per audio chunk.

See `rust/src/lib.rs` for the JNI surface and `rust/src/pipeline/*.rs`
for the text pipeline modules.

## Status: MVP scaffold

What works right now:

* App compiles (CI green).
* Native library loads from `libsupertonic_rust.so`.
* Text pipeline runs a small built-in ё-restoration table (28 starter
  pairs covering common test words: елка, веревка, мед, удивлен,
  Аксёнов, ...).
* JNI bridge surfaces `processText(text)` — the new diagnostic API
  showing exactly what reaches the engine.

What's *not* there yet:

* ORT engine integration (synthesise returns empty PCM).
* Full ~58K-entry yoficator table (currently only the starter set).
* Memory-mapped accent dictionary reader.
* Russian number normaliser.
* Voice style loading + cache.
* In-app playback UI (the screen has the controls; pressing
  Synthesize today only runs the text pipeline).
* Asset downloader actually populating `filesDir`.

These ship in subsequent iterations.

## Architecture

```
┌────────────────────────────────────────────────────────────────┐
│ Kotlin                                                         │
│   MainActivity (Compose UI)                                    │
│   SupertonicRustService (system TTS endpoint)                  │
│   AssetDownloader (pulls model + dict from GitHub releases)    │
│   SupertonicRust (JNI facade — one `external fun` per symbol)  │
└────────────────────────────────────────────────────────────────┘
                                ↕  JNI
┌────────────────────────────────────────────────────────────────┐
│ Rust (libsupertonic_rust.so)                                   │
│   lib.rs           — JNI surface                               │
│   pipeline/                                                    │
│     yofication.rs  — е→ё safe-list (yoficator)                 │
│     lexicon.rs     — user overrides                            │
│     numbers_ru.rs  — digits → words                            │
│     accent_dict.rs — mmap .sacc reader                         │
│   engine/                                                      │
│     mod.rs         — ORT session, voice style cache, streaming │
└────────────────────────────────────────────────────────────────┘
```

## Asset sources

| What       | Where                                                   | When |
|------------|---------------------------------------------------------|------|
| Models     | `davnozdu/supertonic-models` (placeholder — TBD)        | First launch |
| Russian accent dict | `davnozdu/supertonic-dictionaries` release `russian-v1.1` | First launch |

The asset downloader (`AssetDownloader.kt`) is configurable so the URLs
can be repointed without rebuilding the APK.

## Building

```bash
git clone https://github.com/davnozdu/supertonic-tts-rust
cd supertonic-tts-rust
./gradlew assembleDebug
```

Requirements: JDK 17, Android SDK with NDK r29, Rust stable with the four
Android targets installed (`aarch64-linux-android`,
`armv7-linux-androideabi`, `i686-linux-android`, `x86_64-linux-android`).

CI builds debug APKs on every push — see `.github/workflows/ci.yml`.

## License

Apache 2.0, matching the upstream `DevGitPit/supertonic-android`.
