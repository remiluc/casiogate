package com.example.casiogate

import android.Manifest
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat

class MainActivity : ComponentActivity() {

    private val gateEngine = GateEngine()

    private val requestMicPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        gateEngine.micPermissionGranted = granted
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            requestMicPermission.launch(Manifest.permission.RECORD_AUDIO)
        } else {
            gateEngine.micPermissionGranted = true
        }

        setContent {
            CasioGateScreen(gateEngine)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        gateEngine.stop()
    }
}

enum class InputSource {
    MIC,
    USB_LINE
}

class GateEngine {

    var micPermissionGranted = false

    val pattern = mutableStateListOf(*BooleanArray(16).toTypedArray())

    var currentStep by mutableStateOf(0)
        private set

    var bpm by mutableStateOf(120)

    var inputSource by mutableStateOf(InputSource.MIC)

    private val fadeMs = 3

    @Volatile
    private var running = false
    private var audioThread: Thread? = null

    fun start() {
        if (running) return
        if (!micPermissionGranted) return
        running = true
        audioThread = Thread { runAudioLoop() }
        audioThread?.start()
    }

    fun stop() {
        running = false
        audioThread?.join(500)
        audioThread = null
    }

    @Suppress("MissingPermission")
    private fun runAudioLoop() {

        val sampleRate = 44100

        val minRecordBuf = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )

        val minTrackBuf = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )

        if (minRecordBuf <= 0 || minTrackBuf <= 0) {
            running = false
            return
        }

        val audioSource = when (inputSource) {
            InputSource.MIC -> MediaRecorder.AudioSource.VOICE_RECOGNITION
            InputSource.USB_LINE -> MediaRecorder.AudioSource.UNPROCESSED
        }

        val record = AudioRecord(
            audioSource,
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            minRecordBuf * 2
        )

        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .build()
            )
            .setBufferSizeInBytes(minTrackBuf * 2)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()

        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            track.release()
            running = false
            return
        }

        val bufferFrames = 256
        val buffer = ShortArray(bufferFrames)

        var samplePos = 0L

        record.startRecording()
        track.play()

        val fadeSamples = (sampleRate * fadeMs / 1000.0).toInt().coerceAtLeast(1)

        while (running) {

            val read = record.read(buffer, 0, buffer.size)
            if (read <= 0) continue

            val samplesPerStep = (sampleRate * 60.0 / bpm / 4.0)
            val patternLen = pattern.size

            for (i in 0 until read) {

                val stepIndex = ((samplePos / samplesPerStep).toLong() % patternLen).toInt()
                val stepOpen = pattern.getOrElse(stepIndex) { true }

                val posInStep = (samplePos % samplesPerStep.toLong())
                val distFromEdge = minOf(posInStep, (samplesPerStep - posInStep).toLong())
                val fadeGain = if (distFromEdge < fadeSamples) {
                    (distFromEdge.toDouble() / fadeSamples).coerceIn(0.0, 1.0)
                } else 1.0

                val gain = if (stepOpen) fadeGain else 0.0

                buffer[i] = (buffer[i] * gain).toInt().toShort()

                samplePos++
            }

            track.write(buffer, 0, read)

            val displayStep = ((samplePos / samplesPerStep).toLong() % patternLen).toInt()
            if (displayStep != currentStep) {
                currentStep = displayStep
            }
        }

        record.stop()
        record.release()
        track.stop()
        track.release()
    }

    fun toggleStep(index: Int) {
        pattern[index] = !pattern[index]
    }
}

@Composable
fun CasioGateScreen(engine: GateEngine) {

    var playing by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {

        Text("CASIO GATE", color = Color.Red, style = MaterialTheme.typography.headlineSmall)

        Spacer(Modifier.height(16.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Entrée : ", color = Color.White)
            Spacer(Modifier.width(8.dp))
            FilterChip(
                selected = engine.inputSource == InputSource.MIC,
                onClick = { engine.inputSource = InputSource.MIC },
                label = { Text("Micro") }
            )
            Spacer(Modifier.width(8.dp))
            FilterChip(
                selected = engine.inputSource == InputSource.USB_LINE,
                onClick = { engine.inputSource = InputSource.USB_LINE },
                label = { Text("USB-C") }
            )
        }

        Spacer(Modifier.height(20.dp))

        Row {
            for (i in 0 until 16) {
                Box(
                    modifier = Modifier
                        .size(24.dp)
                        .padding(2.dp)
                        .background(
                            when {
                                i == engine.currentStep && playing -> Color.White
                                engine.pattern[i] -> Color.Red
                                else -> Color.DarkGray
                            }
                        )
                        .clickable { engine.toggleStep(i) }
                )
            }
        }

        Spacer(Modifier.height(30.dp))

        Text("BPM : ${engine.bpm}", color = Color.White)
        Slider(
            value = engine.bpm.toFloat(),
            onValueChange = { engine.bpm = it.toInt() },
            valueRange = 40f..220f
        )

        Spacer(Modifier.height(30.dp))

        Button(
            onClick = {
                playing = !playing
                if (playing) engine.start() else engine.stop()
            }
        ) {
            Text(if (playing) "STOP" else "PLAY")
        }

        if (!engine.micPermissionGranted) {
            Spacer(Modifier.height(16.dp))
            Text(
                "Permission micro requise pour capter l'entrée audio.",
                color = Color.Yellow
            )
        }
    }
}
