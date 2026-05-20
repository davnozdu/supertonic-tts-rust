package com.davnozdu.supertonicrust

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp

/**
 * MVP UI for the Rust-heavy fork: text field, three sliders, synthesize
 * button, status line. No queue / history / ebook / saved-audio screens
 * yet — those come back once the engine is wired up.
 *
 * Most of the heavy lifting is intentionally invisible: SupertonicRust
 * already loaded the native libraries by the time onCreate runs (its
 * `init {}` block executes on first class touch).
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        // Touch the singleton so the static init runs synchronously now
        // rather than on the first JNI call from Compose.
        SupertonicRust.nativeVersion()

        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                MainScreen()
            }
        }
    }
}

@Composable
private fun MainScreen() {
    val ctx = LocalContext.current
    var text by remember { mutableStateOf(stringResource(R.string.default_input_text)) }
    var speed by remember { mutableFloatStateOf(1.0f) }
    var quality by remember { mutableIntStateOf(5) }
    var processed by remember { mutableStateOf("") }
    val nativeVersion = remember { SupertonicRust.nativeVersion() }

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

            Button(
                onClick = {
                    processed = SupertonicRust.processText(text)
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(stringResource(R.string.synthesize_button))
            }

            if (processed.isNotEmpty()) {
                Surface(tonalElevation = 4.dp, modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text("Pipeline output:", style = MaterialTheme.typography.labelMedium)
                        Text(processed, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }

            Text(
                "MVP scaffold — actual synthesis is wired in the next iteration. " +
                    "Right now this only demonstrates the text pipeline (ё-restoration).",
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}
