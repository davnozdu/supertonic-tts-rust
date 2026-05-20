package com.davnozdu.supertonicrust

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * MVP UI for the Rust-heavy fork.
 *
 * Flow on first launch:
 *   1. Show progress card while AssetDownloader pulls the model bundle
 *      (~398 MB) and the .sacc accent dictionary.
 *   2. Once assets are on disk, initialise the engine (ORT sessions).
 *   3. Enable the synthesize button.
 *
 * The "Озвучить" button submits the text to the Rust pipeline + ORT,
 * then plays the resulting PCM via AudioTrack at the engine's reported
 * sample rate (typically 24 kHz).
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        SupertonicRust.nativeVersion()

        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                MainScreen()
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        SupertonicRust.shutdown()
    }
}

private sealed interface BootState {
    object Initial : BootState
    data class Downloading(val progress: AssetDownloader.Progress) : BootState
    object Initialising : BootState
    object Ready : BootState
    data class Failed(val message: String) : BootState
}

@Composable
private fun MainScreen() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    val defaultText = stringResource(R.string.default_input_text)

    var bootState by remember { mutableStateOf<BootState>(BootState.Initial) }
    var text by remember { mutableStateOf(defaultText) }
    var speed by remember { mutableFloatStateOf(1.0f) }
    var quality by remember { mutableIntStateOf(5) }
    var synthesising by remember { mutableStateOf(false) }
    var lastError by remember { mutableStateOf("") }
    val nativeVersion = remember { SupertonicRust.nativeVersion() }

    LaunchedEffect(Unit) {
        bootAndInit(
            context = context,
            onState = { bootState = it }
        )
    }

    Scaffold { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .padding(16.dp)
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                "Supertonic Rust  ·  native v$nativeVersion",
                style = MaterialTheme.typography.titleMedium
            )

            BootStatusCard(bootState)

            Text(stringResource(R.string.input_label), style = MaterialTheme.typography.labelLarge)
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                modifier = Modifier.fillMaxWidth(),
                minLines = 3
            )

            Text("${stringResource(R.string.speed_label)}: ${"%.2fx".format(speed)}")
            Slider(value = speed, onValueChange = { speed = it }, valueRange = 0.7f..1.5f)

            Text("${stringResource(R.string.quality_label)}: $quality")
            Slider(
                value = quality.toFloat(),
                onValueChange = { quality = it.toInt() },
                valueRange = 1f..7f,
                steps = 5
            )
            Text(
                stringResource(R.string.quality_hint),
                style = MaterialTheme.typography.bodySmall
            )

            val ready = bootState is BootState.Ready
            Button(
                onClick = {
                    if (!ready || synthesising) return@Button
                    synthesising = true
                    lastError = ""
                    scope.launch {
                        try {
                            withContext(Dispatchers.IO) {
                                val processed = SupertonicRust.processText(text)
                                val voicePath = File(
                                    context.filesDir,
                                    "${AssetDownloader.MODEL_DIR}/voice_styles/F3.json"
                                ).absolutePath
                                val pcm = SupertonicSynthesisCallback().runSynthesis(
                                    processed = processed,
                                    voicePath = voicePath,
                                    speed = speed,
                                    steps = quality,
                                )
                                if (pcm.isNotEmpty()) {
                                    val sr = SupertonicRust.getSampleRate(SupertonicRust.handle())
                                    playPcm(pcm, sr)
                                } else {
                                    withContext(Dispatchers.Main) {
                                        lastError = "Движок вернул пустой PCM"
                                    }
                                }
                            }
                        } catch (t: Throwable) {
                            lastError = t.message ?: t.javaClass.simpleName
                        } finally {
                            synthesising = false
                        }
                    }
                },
                enabled = ready && !synthesising,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    if (synthesising) "…" else stringResource(R.string.synthesize_button)
                )
            }

            if (lastError.isNotEmpty()) {
                Surface(
                    tonalElevation = 4.dp,
                    color = MaterialTheme.colorScheme.errorContainer,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        lastError,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(12.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun BootStatusCard(state: BootState) {
    when (state) {
        BootState.Initial -> {
            Surface(tonalElevation = 2.dp, modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.size(8.dp))
                    Text("Проверка ресурсов…", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        is BootState.Downloading -> {
            val p = state.progress
            val mbDone = p.bytesSoFar / 1_048_576f
            val mbTotal = if (p.bytesTotal > 0) p.bytesTotal / 1_048_576f else 0f
            val fraction = if (p.bytesTotal > 0) (p.bytesSoFar.toFloat() / p.bytesTotal).coerceIn(0f, 1f) else 0f
            Surface(tonalElevation = 2.dp, modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(
                        stringResource(R.string.assets_downloading, p.currentFile),
                        style = MaterialTheme.typography.bodySmall
                    )
                    Spacer(Modifier.size(4.dp))
                    if (p.bytesTotal > 0) {
                        LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
                        Text(
                            stringResource(R.string.assets_progress_fmt, mbDone, mbTotal) +
                                "  ·  ${p.fileIndex + 1}/${p.totalFiles}",
                            style = MaterialTheme.typography.bodySmall
                        )
                    } else {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                }
            }
        }
        BootState.Initialising -> {
            Surface(tonalElevation = 2.dp, modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.size(8.dp))
                    Text(
                        stringResource(R.string.engine_loading),
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }
        BootState.Ready -> {
            Surface(
                tonalElevation = 2.dp,
                color = MaterialTheme.colorScheme.primaryContainer,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    stringResource(R.string.engine_ready),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(12.dp)
                )
            }
        }
        is BootState.Failed -> {
            Surface(
                tonalElevation = 2.dp,
                color = MaterialTheme.colorScheme.errorContainer,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    stringResource(R.string.assets_failed, state.message),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(12.dp)
                )
            }
        }
    }
}

private suspend fun bootAndInit(
    context: android.content.Context,
    onState: (BootState) -> Unit
) {
    if (!AssetDownloader.isReady(context)) {
        val ok = AssetDownloader.downloadAll(context) { p ->
            onState(BootState.Downloading(p))
        }
        if (!ok) {
            onState(BootState.Failed("download"))
            return
        }
    }
    onState(BootState.Initialising)
    val modelDir = File(context.filesDir, "${AssetDownloader.MODEL_DIR}/onnx").absolutePath
    val libDir = context.applicationInfo.nativeLibraryDir
    val ok = withContext(Dispatchers.IO) {
        SupertonicRust.ensureEngineInitialised(modelDir, libDir, ortThreads = 2, xnnThreads = 2)
    }
    if (!ok) {
        onState(BootState.Failed("engine init"))
        return
    }

    val dictPath = File(context.filesDir, AssetDownloader.ACCENT_DICT_NAME).absolutePath
    SupertonicRust.loadAccentDictionary(dictPath)

    onState(BootState.Ready)
}

/**
 * Callback shim invoked by Rust during synthesize(). Rust reaches it via
 * JNI: `notifyAudioChunk([B)V`, `notifyProgress(II)V`, `isCancelled()Z`.
 * Public so JNI's access checks on newer Android don't reject the call.
 */
class SupertonicSynthesisCallback {
    @Volatile var cancelled: Boolean = false

    fun isCancelled(): Boolean = cancelled

    fun notifyAudioChunk(pcm: ByteArray) {
        // Streaming not wired to AudioTrack yet — playback uses the bulk
        // PCM returned by synthesize() below. Hook for future incremental
        // playback that doesn't wait for the whole sentence to finish.
        if (pcm.isEmpty()) return
    }

    fun notifyProgress(current: Int, total: Int) {
        // Future: surface this into Compose state.
        if (current > total) return
    }

    fun runSynthesis(processed: String, voicePath: String, speed: Float, steps: Int): ByteArray {
        return SupertonicRust.synthesize(
            callback = this,
            engine = SupertonicRust.handle(),
            text = processed,
            lang = "ru",
            stylePath = voicePath,
            speed = speed,
            bufferSeconds = 0f,
            steps = steps,
            gain = 1.0f
        )
    }
}

private fun playPcm(pcm: ByteArray, sampleRate: Int) {
    val minBuf = AudioTrack.getMinBufferSize(
        sampleRate,
        AudioFormat.CHANNEL_OUT_MONO,
        AudioFormat.ENCODING_PCM_16BIT
    ).coerceAtLeast(8192)

    val track = AudioTrack.Builder()
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
        )
        .setAudioFormat(
            AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(sampleRate)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .build()
        )
        .setBufferSizeInBytes(minBuf)
        .setTransferMode(AudioTrack.MODE_STREAM)
        .build()
    track.play()
    track.write(pcm, 0, pcm.size, AudioTrack.WRITE_BLOCKING)
    // Wait for the queued samples to actually finish playing — write()
    // returns once the buffer is queued, not once it's played out.
    val frames = pcm.size / 2
    val durationMs = (frames.toLong() * 1000L) / sampleRate
    try {
        Thread.sleep(durationMs)
    } catch (_: InterruptedException) {
    }
    track.stop()
    track.release()
}

@Suppress("unused")
private fun audioManager(context: android.content.Context): AudioManager =
    context.getSystemService(android.content.Context.AUDIO_SERVICE) as AudioManager
