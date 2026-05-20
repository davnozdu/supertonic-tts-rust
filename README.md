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

## Status: MVP playback

What works right now:

* App compiles (CI green).
* On first launch the app downloads the Supertonic 3 model bundle
  (~398 MB) from Hugging Face and the Russian accent dictionary from
  `davnozdu/supertonic-dictionaries` releases.
* ORT engine (4 ONNX sessions, XNNPACK execution provider) loads on
  boot, then synthesises Russian via the `synthesize` JNI call.
* Returned PCM (16-bit LE mono, model's native sample rate) is played
  through `AudioTrack` (MODE_STREAM).
* Text pipeline runs a built-in ё-restoration starter table before
  text reaches the model.

What's *not* there yet:

* Full ~58K-entry yoficator table (currently only the starter set).
* Memory-mapped accent dictionary reader (.sacc).
* Russian number normaliser.
* Streaming playback (today the UI waits for the full sentence).
* TTS-Service integration (system Settings → TTS engine is registered
  but the service still calls into the pipeline-only path).
* Voice picker UI (we hard-code F3 for now).
* Cancellation / progress surface in the UI.

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
