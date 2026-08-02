package com.example.casiogate

import android.Manifest
import android.content.pm.ActivityInfo
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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat

/**
 * CASIO GATE v2
 *
 * - Mode paysage forcé
 * - Nombre de steps réglable librement (2 à 32)
 * - Durée globale des steps (BPM + subdivision), avec possibilité de
 *   personnaliser la durée d'un step individuellement (multiplicateur
 *   par step, ex: 0.5x = deux fois plus court, 2x = deux fois plus long)
 */

class MainActivity : ComponentActivity() {

    private val gateEngine = GateEngine()

    private val requestMicPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        gateEngine.micPermissionGranted = granted
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Force le mode paysage pour toute l'activité
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE

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

/**
 * Représente l'état et le réglage de durée d'un step.
 * durationMultiplier : 1.0 = durée normale (celle du réglage global),
 * 0.25 à 4.0 = plus court/plus long que la normale.
 */
data class StepConfig(
    val open: Boolean = false,
    val durationMultiplier: Float = 1.0f
)

class GateEngine {

    var micPermissionGranted = false

    // Nombre de steps, réglable de 2 à 32
    var stepCount by mutableStateOf(16)
        private set

    // Pattern : liste de StepConfig, une par step
    val pattern = mutableStateListOf<StepConfig>().apply {
        repeat(16) { add(StepConfig()) }
    }

    // Historique pour Undo : jusqu'à 10 états précédents du pattern.
    // On stocke un instantané (liste immuable) avant chaque modification.
    private val history = ArrayDeque<List<StepConfig>>()
    private val maxHistory = 10

    private fun pushHistory() {
        history.addLast(pattern.toList())
        if (history.size > maxHistory) {
            history.removeFirst()
        }
    }

    fun undo() {
        if (history.isEmpty()) return
        val previous = history.removeLast()
        pattern.clear()
        pattern.addAll(previous)
        stepCount = previous.size
    }

    fun randomize() {
        pushHistory()
        for (i in pattern.indices) {
            val current = pattern[i]
            pattern[i] = current.copy(open = kotlin.random.Random.nextBoolean())
        }
    }

    fun reset() {
        pushHistory()
        for (i in pattern.indices) {
            pattern[i] = StepConfig()
        }
    }

    var currentStep by mutableStateOf(0)
        private set

    // BPM = tempo de référence pour la durée "normale" d'un step
    var bpm by mutableStateOf(120)

    // Subdivision de la durée normale d'un step, en fraction de noire.
    // 4.0 = noire, 2.0 = croche, 1.0 = double-croche (défaut), 0.5 = triple-croche
    var stepSubdivision by mutableStateOf(1.0f)

    var inputSource by mutableStateOf(InputSource.MIC)

    private val fadeMs = 3

    @Volatile
    private var running = false
    private var audioThread: Thread? = null

    fun setStepCount(newCount: Int) {
        val clamped = newCount.coerceIn(2, 32)
        stepCount = clamped
        when {
            pattern.size < clamped -> {
                repeat(clamped - pattern.size) { pattern.add(StepConfig()) }
            }
            pattern.size > clamped -> {
                repeat(pattern.size - clamped) { pattern.removeAt(pattern.size - 1) }
            }
        }
    }

    fun toggleStep(index: Int) {
        pushHistory()
        val current = pattern[index]
        pattern[index] = current.copy(open = !current.open)
    }

    fun setStepDuration(index: Int, multiplier: Float) {
        val current = pattern[index]
        pattern[index] = current.copy(durationMultiplier = multiplier.coerceIn(0.25f, 4.0f))
    }

    /** À appeler une seule fois, juste avant de commencer à glisser un slider de durée. */
    fun snapshotBeforeDurationEdit() {
        pushHistory()
    }

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

        record.startRecording()
        track.play()

        val fadeSamples = (sampleRate * fadeMs / 1000.0).toInt().coerceAtLeast(1)

        // Position continue en échantillons depuis le début de la lecture.
        // On garde aussi l'index du step courant et la position (en
        // échantillons) à l'intérieur de ce step, pour supporter des
        // durées de step différentes les unes des autres.
        var stepIdx = 0
        var posInCurrentStep = 0L

        while (running) {

            val read = record.read(buffer, 0, buffer.size)
            if (read <= 0) continue

            // Durée "normale" d'un step en échantillons, dérivée du BPM
            // et de la subdivision choisie.
            val baseStepSamples = sampleRate * 60.0 / bpm * stepSubdivision

            val patternLen = pattern.size.coerceAtLeast(1)

            for (i in 0 until read) {

                // Sécurité si le pattern a été redimensionné pendant la lecture
                if (stepIdx >= patternLen) stepIdx = 0

                val step = pattern.getOrElse(stepIdx) { StepConfig(open = true) }
                val thisStepSamples = (baseStepSamples * step.durationMultiplier)
                    .toLong().coerceAtLeast(1)

                val distFromEdge = minOf(
                    posInCurrentStep,
                    thisStepSamples - posInCurrentStep
                )
                val fadeGain = if (distFromEdge < fadeSamples) {
                    (distFromEdge.toDouble() / fadeSamples).coerceIn(0.0, 1.0)
                } else 1.0

                val gain = if (step.open) fadeGain else 0.0

                buffer[i] = (buffer[i] * gain).toInt().toShort()

                posInCurrentStep++
                if (posInCurrentStep >= thisStepSamples) {
                    posInCurrentStep = 0
                    stepIdx = (stepIdx + 1) % patternLen
                }
            }

            track.write(buffer, 0, read)

            if (stepIdx != currentStep) {
                currentStep = stepIdx
            }
        }

        record.stop()
        record.release()
        track.stop()
        track.release()
    }
}

@Composable
fun CasioGateScreen(engine: GateEngine) {

    var playing by remember { mutableStateOf(false) }
    var editingStepIndex by remember { mutableStateOf<Int?>(null) }

    Row(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .padding(12.dp)
    ) {

        // Colonne de gauche : contrôles
        Column(
            modifier = Modifier
                .width(220.dp)
                .fillMaxHeight()
        ) {

            Text("CASIO GATE", color = Color.Red, style = MaterialTheme.typography.titleMedium)

            Spacer(Modifier.height(12.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                FilterChip(
                    selected = engine.inputSource == InputSource.MIC,
                    onClick = { engine.inputSource = InputSource.MIC },
                    label = { Text("Micro", fontSize = 12.sp) }
                )
                Spacer(Modifier.width(6.dp))
                FilterChip(
                    selected = engine.inputSource == InputSource.USB_LINE,
                    onClick = { engine.inputSource = InputSource.USB_LINE },
                    label = { Text("USB-C", fontSize = 12.sp) }
                )
            }

            Spacer(Modifier.height(16.dp))

            Text("BPM : ${engine.bpm}", color = Color.White, fontSize = 13.sp)
            Slider(
                value = engine.bpm.toFloat(),
                onValueChange = { engine.bpm = it.toInt() },
                valueRange = 40f..220f
            )

            Spacer(Modifier.height(12.dp))

            Text(
                "Durée de base : ${"%.2f".format(engine.stepSubdivision)}x noire",
                color = Color.White,
                fontSize = 13.sp
            )
            Slider(
                value = engine.stepSubdivision,
                onValueChange = { engine.stepSubdivision = it },
                valueRange = 0.125f..2.0f
            )

            Spacer(Modifier.height(12.dp))

            Text("Nombre de steps : ${engine.stepCount}", color = Color.White, fontSize = 13.sp)
            Slider(
                value = engine.stepCount.toFloat(),
                onValueChange = { engine.setStepCount(it.toInt()) },
                valueRange = 2f..32f,
                steps = 29
            )

            Spacer(Modifier.height(16.dp))

            Button(
                onClick = {
                    playing = !playing
                    if (playing) engine.start() else engine.stop()
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(if (playing) "STOP" else "PLAY")
            }

            Spacer(Modifier.height(10.dp))

            Row(modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(
                    onClick = { engine.randomize() },
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(4.dp)
                ) {
                    Text("Random", fontSize = 11.sp)
                }
                Spacer(Modifier.width(4.dp))
                OutlinedButton(
                    onClick = { engine.reset() },
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(4.dp)
                ) {
                    Text("Reset", fontSize = 11.sp)
                }
                Spacer(Modifier.width(4.dp))
                OutlinedButton(
                    onClick = { engine.undo() },
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(4.dp)
                ) {
                    Text("Undo", fontSize = 11.sp)
                }
            }

            if (!engine.micPermissionGranted) {
                Spacer(Modifier.height(12.dp))
                Text(
                    "Permission micro requise.",
                    color = Color.Yellow,
                    fontSize = 12.sp
                )
            }

            // Panneau d'édition de la durée d'un step, si un step est sélectionné
            editingStepIndex?.let { idx ->
                if (idx < engine.pattern.size) {
                    Spacer(Modifier.height(16.dp))
                    Text("Step ${idx + 1} — durée", color = Color.Cyan, fontSize = 12.sp)
                    Slider(
                        value = engine.pattern[idx].durationMultiplier,
                        onValueChange = { engine.setStepDuration(idx, it) },
                        valueRange = 0.25f..4.0f
                    )
                    Text(
                        "${"%.2f".format(engine.pattern[idx].durationMultiplier)}x",
                        color = Color.White,
                        fontSize = 11.sp
                    )
                    TextButton(onClick = { editingStepIndex = null }) {
                        Text("Fermer", fontSize = 11.sp)
                    }
                }
            }
        }

        Spacer(Modifier.width(16.dp))

        // Grille de steps à droite, s'adapte au nombre de steps
        LazyVerticalGrid(
            columns = GridCells.Fixed(8),
            modifier = Modifier.fillMaxHeight(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            items(engine.pattern.size) { i ->
                val step = engine.pattern[i]
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .background(
                            when {
                                i == engine.currentStep && playing -> Color.White
                                step.open -> Color.Red
                                else -> Color.DarkGray
                            }
                        )
                        .clickable { engine.toggleStep(i) },
                    contentAlignment = Alignment.BottomEnd
                ) {
                    // Petit bouton pour ouvrir le réglage de durée du step
                    Box(
                        modifier = Modifier
                            .size(16.dp)
                            .background(Color.Cyan.copy(alpha = 0.6f))
                            .clickable {
                                if (editingStepIndex != i) {
                                    engine.snapshotBeforeDurationEdit()
                                }
                                editingStepIndex = i
                            }
                    )
                    if (step.durationMultiplier != 1.0f) {
                        Text(
                            "${"%.1f".format(step.durationMultiplier)}x",
                            color = Color.Yellow,
                            fontSize = 8.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.align(Alignment.TopStart)
                        )
                    }
                }
            }
        }
    }
}
