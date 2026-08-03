package com.example.casiogate

import android.Manifest
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat

/**
 * CASIO GATE v3
 *
 * - Mode paysage forcé
 * - Nombre de steps réglable librement (2 à 32)
 * - Tempo (BPM) + subdivision + gate width (durée son/silence à
 *   chaque pulsation), identiques pour tous les steps
 * - Détection automatique de tempo à partir du signal entrant, avec
 *   tap tempo manuel en secours
 * - Patterns rythmiques prédéfinis façon drum and bass, calés sur le
 *   tempo détecté ou réglé manuellement
 */

class MainActivity : ComponentActivity() {

    private val gateEngine by lazy { GateEngine(applicationContext) }

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
 * Représente l'état d'un step : ouvert (son passe) ou fermé (silence).
 * La durée de tous les steps est réglée globalement (BPM + subdivision).
 */
data class StepConfig(
    val open: Boolean = false
)

/**
 * Bibliothèque de patterns rythmiques prédéfinis, pensés pour la drum
 * and bass. Chaque pattern est une liste de 16 booléens (steps ouverts/
 * fermés) — s'applique en boucle même si stepCount n'est pas 16 (le
 * moteur répète/tronque selon le nombre de steps réglé).
 */
object DnbPatterns {

    // "Amen simplifié" : squelette syncopé inspiré du break le plus
    // emblématique du genre — accents décalés, pas de grille régulière.
    val amenSimplified = listOf(
        true, false, false, true,
        false, true, false, false,
        false, false, true, false,
        true, false, true, false
    )

    // Two-step : kick/snare alternés avec de vrais silences, plus épuré.
    val twoStep = listOf(
        true, false, false, false,
        false, false, true, false,
        false, false, false, false,
        false, false, true, false
    )

    // Halftime : rythme perçu deux fois plus lent, plus lourd, très
    // utilisé en DnB moderne — peu de steps actifs, bien espacés.
    val halftime = listOf(
        true, false, false, false,
        false, false, false, false,
        false, false, false, false,
        true, false, false, false
    )

    // Rolling : pattern dense, sensation de continuité, beaucoup de
    // steps actifs mais avec un léger groove syncopé.
    val rolling = listOf(
        true, false, true, true,
        false, true, false, true,
        true, false, true, true,
        false, true, false, true
    )

    val all: Map<String, List<Boolean>> = linkedMapOf(
        "Amen simplifié" to amenSimplified,
        "Two-step" to twoStep,
        "Halftime" to halftime,
        "Rolling" to rolling
    )
}

class GateEngine(private val context: android.content.Context) {

    // Device de sortie forcé (ex: Bluetooth), indépendant de ce qui est
    // branché en entrée sur le jack. null = comportement par défaut du
    // système (généralement le jack s'il est branché).
    var preferredOutputDevice by mutableStateOf<AudioDeviceInfo?>(null)

    /** Liste les sorties audio actuellement disponibles (jack, Bluetooth, haut-parleur...). */
    fun availableOutputDevices(): List<AudioDeviceInfo> {
        val audioManager = context.getSystemService(android.content.Context.AUDIO_SERVICE) as AudioManager
        return audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            .filter { device ->
                device.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
                device.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                device.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES ||
                device.type == AudioDeviceInfo.TYPE_WIRED_HEADSET ||
                device.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
            }
            .distinctBy { it.type }
    }

    fun outputDeviceLabel(device: AudioDeviceInfo): String = when (device.type) {
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_BLUETOOTH_SCO ->
            "Bluetooth" + (device.productName?.let { " ($it)" } ?: "")
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_WIRED_HEADSET -> "Jack filaire"
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "Haut-parleur"
        else -> "Autre"
    }

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

    // BPM = tempo de référence, fixe la vitesse du rythme (durée totale
    // de chaque pulsation/step). Ne change jamais avec le gate width.
    var bpm by mutableStateOf(120)

    // Subdivision de la durée d'un step, en fraction de noire.
    // 4.0 = noire, 2.0 = croche, 1.0 = double-croche (défaut), 0.5 = triple-croche
    var stepSubdivision by mutableStateOf(1.0f)

    // Gate width (0.05 à 1.0) : proportion de chaque step pendant laquelle
    // le son reste audible avant la coupure. 1.0 = son continu sur tout le
    // step (pas de coupure), 0.5 = son audible sur la moitié du step puis
    // silence, 0.1 = très court "blip" suivi d'un long silence.
    // Ce réglage ne change JAMAIS la durée du step ni le tempo — seulement
    // le ratio son/silence à l'intérieur de chaque pulsation.
    var gateWidth by mutableStateOf(0.5f)

    var inputSource by mutableStateOf(InputSource.MIC)

    // Device d'entrée forcé (ex: jack physique), indépendant du choix
    // MIC/USB_LINE générique. Prioritaire s'il est renseigné.
    var preferredInputDevice by mutableStateOf<AudioDeviceInfo?>(null)

    /** Liste les entrées audio actuellement disponibles (jack, micro intégré, USB...). */
    fun availableInputDevices(): List<AudioDeviceInfo> {
        val audioManager = context.getSystemService(android.content.Context.AUDIO_SERVICE) as AudioManager
        return audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)
            .filter { device ->
                device.type == AudioDeviceInfo.TYPE_WIRED_HEADSET ||
                device.type == AudioDeviceInfo.TYPE_USB_DEVICE ||
                device.type == AudioDeviceInfo.TYPE_USB_HEADSET ||
                device.type == AudioDeviceInfo.TYPE_BUILTIN_MIC ||
                device.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO
            }
            // Android expose parfois le même device physique deux fois
            // (ex: deux entrées TYPE_BUILTIN_MIC). On ne garde qu'une
            // entrée par type pour un affichage propre.
            .distinctBy { it.type }
    }

    fun inputDeviceLabel(device: AudioDeviceInfo): String = when (device.type) {
        AudioDeviceInfo.TYPE_WIRED_HEADSET -> "Jack (entrée filaire)"
        AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_HEADSET -> "USB-C"
        AudioDeviceInfo.TYPE_BUILTIN_MIC -> "Micro intégré"
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "Bluetooth"
        else -> "Autre"
    }

    // --- Tap tempo (secours manuel) ---
    private val tapTimestamps = ArrayDeque<Long>()
    private val maxTapHistory = 6

    /** À appeler à chaque tap du bouton "Tap tempo". */
    fun registerTap() {
        val now = System.currentTimeMillis()
        tapTimestamps.addLast(now)
        if (tapTimestamps.size > maxTapHistory) {
            tapTimestamps.removeFirst()
        }
        if (tapTimestamps.size >= 2) {
            val intervals = tapTimestamps.zipWithNext { a, b -> b - a }
            val avgMs = intervals.average()
            if (avgMs > 0) {
                val newBpm = (60000.0 / avgMs).toInt().coerceIn(40, 220)
                bpm = newBpm
            }
        }
    }

    // --- Détection automatique de tempo à partir du signal entrant ---
    var autoDetectEnabled by mutableStateOf(false)
    var detectedBpm by mutableStateOf<Int?>(null)
        private set

    // Historique des intervalles entre onsets détectés, pour estimer le
    // BPM par la valeur la plus fréquente plutôt qu'une simple moyenne
    // (plus robuste aux détections ratées ou aux double-déclenchements).
    private val onsetIntervalHistoryMs = ArrayDeque<Long>()
    private val maxOnsetHistory = 20
    private var lastOnsetTimeMs = 0L
    private var runningEnergy = 0.0
    private val energySmoothing = 0.9

    /**
     * Analyse un buffer audio pour détecter un onset (attaque soudaine),
     * et met à jour l'estimation de BPM si assez d'intervalles ont été
     * capturés. Appelé depuis la boucle audio, sur le signal brut
     * (avant gating), pour ne réagir qu'au vrai jeu du PT-20.
     */
    private fun analyzeForTempo(buffer: ShortArray, len: Int, sampleRate: Int) {
        if (!autoDetectEnabled) return

        // Énergie RMS de ce buffer
        var sumSquares = 0.0
        for (i in 0 until len) {
            val s = buffer[i].toDouble()
            sumSquares += s * s
        }
        val rms = kotlin.math.sqrt(sumSquares / len.coerceAtLeast(1))

        // Moyenne mobile de l'énergie pour détecter les pics soudains
        // (onset = énergie qui dépasse nettement la moyenne récente)
        val previousRunning = runningEnergy
        runningEnergy = energySmoothing * runningEnergy + (1 - energySmoothing) * rms

        val threshold = previousRunning * 1.5 + 200.0 // marge pour éviter le bruit de fond
        val now = System.currentTimeMillis()

        if (rms > threshold && (now - lastOnsetTimeMs) > 120) {
            // Onset détecté (avec un verrou de 120ms pour éviter les
            // multiples déclenchements sur une même attaque)
            if (lastOnsetTimeMs != 0L) {
                val interval = now - lastOnsetTimeMs
                if (interval in 150..2000) { // correspond à 30-400 BPM, filtre le bruit
                    onsetIntervalHistoryMs.addLast(interval)
                    if (onsetIntervalHistoryMs.size > maxOnsetHistory) {
                        onsetIntervalHistoryMs.removeFirst()
                    }
                }
            }
            lastOnsetTimeMs = now

            if (onsetIntervalHistoryMs.size >= 4) {
                // BPM = valeur la plus fréquente (arrondie à 2 BPM près)
                // parmi les intervalles récents, plutôt qu'une moyenne
                // brute sensible aux valeurs aberrantes.
                val bpmCandidates = onsetIntervalHistoryMs.map {
                    (60000.0 / it).toInt() / 2 * 2
                }
                val mostCommon = bpmCandidates
                    .groupingBy { it }
                    .eachCount()
                    .maxByOrNull { it.value }
                    ?.key
                if (mostCommon != null && mostCommon in 40..220) {
                    detectedBpm = mostCommon
                    bpm = mostCommon
                }
            }
        }
    }

    /** Applique un pattern prédéfini (ex: DnB), adapté au nombre de steps actuel. */
    fun applyPattern(source: List<Boolean>) {
        pushHistory()
        for (i in pattern.indices) {
            val value = source.getOrElse(i % source.size) { false }
            pattern[i] = StepConfig(open = value)
        }
    }

    private val fadeMs = 3

    @Volatile
    private var running = false
    private var audioThread: Thread? = null

    fun changeStepCount(newCount: Int) {
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

        // Si un device d'entrée précis est choisi (ex: jack physique),
        // UNPROCESSED est la source la plus neutre — on force ensuite le
        // device exact avec setPreferredDevice, qui prime sur le routage
        // automatique du système.
        val audioSource = if (preferredInputDevice != null) {
            MediaRecorder.AudioSource.UNPROCESSED
        } else {
            when (inputSource) {
                InputSource.MIC -> MediaRecorder.AudioSource.VOICE_RECOGNITION
                InputSource.USB_LINE -> MediaRecorder.AudioSource.UNPROCESSED
            }
        }

        val record = AudioRecord(
            audioSource,
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            minRecordBuf * 2
        )

        preferredInputDevice?.let { device ->
            record.preferredDevice = device
        }

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

        // Force la sortie vers le device choisi (ex: Bluetooth), même si
        // un jack est branché en entrée — sans ça, Android route la
        // sortie automatiquement vers le jack dès qu'il est inséré.
        preferredOutputDevice?.let { device ->
            track.preferredDevice = device
        }

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

            // Analyse le signal brut (avant gating) pour la détection de
            // tempo, si activée — ne modifie pas le buffer.
            analyzeForTempo(buffer, read, sampleRate)

            // Durée d'un step en échantillons, identique pour tous les
            // steps, dérivée du BPM et de la subdivision choisie.
            val stepSamples = (sampleRate * 60.0 / bpm * stepSubdivision)
                .toLong().coerceAtLeast(1)

            val patternLen = pattern.size.coerceAtLeast(1)

            for (i in 0 until read) {

                // Sécurité si le pattern a été redimensionné pendant la lecture
                if (stepIdx >= patternLen) stepIdx = 0

                val step = pattern.getOrElse(stepIdx) { StepConfig(open = true) }

                // Durée pendant laquelle le son reste audible à l'intérieur
                // de ce step, déterminée par gateWidth. Le step dans son
                // ensemble (stepSamples) ne change jamais : seul ce sous-
                // segment interne bouge, donc le tempo reste intact.
                val audibleSamples = (stepSamples * gateWidth)
                    .toLong().coerceIn(1, stepSamples)

                val isInAudiblePortion = posInCurrentStep < audibleSamples

                // Fondu anti-clic : au début du step (ouverture) et à la
                // fin de la portion audible (fermeture avant le silence).
                val distFromStart = posInCurrentStep
                val distFromAudibleEnd = audibleSamples - posInCurrentStep
                val distFromEdge = if (isInAudiblePortion) {
                    minOf(distFromStart, distFromAudibleEnd)
                } else {
                    0L
                }
                val fadeGain = if (distFromEdge < fadeSamples) {
                    (distFromEdge.toDouble() / fadeSamples).coerceIn(0.0, 1.0)
                } else 1.0

                val gain = if (step.open && isInAudiblePortion) fadeGain else 0.0

                buffer[i] = (buffer[i] * gain).toInt().toShort()

                posInCurrentStep++
                if (posInCurrentStep >= stepSamples) {
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

    Row(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .padding(12.dp)
    ) {

        // Colonne de gauche : contrôles (scrollable pour rester accessible
        // même en paysage sur un écran bas)
        Column(
            modifier = Modifier
                .width(220.dp)
                .fillMaxHeight()
                .verticalScroll(rememberScrollState())
        ) {

            Text("CASIO GATE", color = Color.Red, style = MaterialTheme.typography.titleMedium)

            Spacer(Modifier.height(12.dp))

            Text("Entrée :", color = Color.White, fontSize = 13.sp)
            val inputs = remember { engine.availableInputDevices() }
            Column {
                FilterChip(
                    selected = engine.preferredInputDevice == null,
                    onClick = { engine.preferredInputDevice = null },
                    label = { Text("Auto (système)", fontSize = 11.sp) },
                    modifier = Modifier.padding(vertical = 2.dp)
                )
                inputs.forEach { device ->
                    FilterChip(
                        selected = engine.preferredInputDevice?.id == device.id,
                        onClick = { engine.preferredInputDevice = device },
                        label = { Text(engine.inputDeviceLabel(device), fontSize = 11.sp) },
                        modifier = Modifier.padding(vertical = 2.dp)
                    )
                }
            }

            Spacer(Modifier.height(12.dp))

            Text("Sortie :", color = Color.White, fontSize = 13.sp)
            val outputs = remember { engine.availableOutputDevices() }
            Column {
                FilterChip(
                    selected = engine.preferredOutputDevice == null,
                    onClick = { engine.preferredOutputDevice = null },
                    label = { Text("Auto (système)", fontSize = 11.sp) },
                    modifier = Modifier.padding(vertical = 2.dp)
                )
                outputs.forEach { device ->
                    FilterChip(
                        selected = engine.preferredOutputDevice?.id == device.id,
                        onClick = { engine.preferredOutputDevice = device },
                        label = { Text(engine.outputDeviceLabel(device), fontSize = 11.sp) },
                        modifier = Modifier.padding(vertical = 2.dp)
                    )
                }
            }

            Spacer(Modifier.height(16.dp))

            Text("BPM : ${engine.bpm}", color = Color.White, fontSize = 13.sp)
            Slider(
                value = engine.bpm.toFloat(),
                onValueChange = { engine.bpm = it.toInt() },
                valueRange = 40f..220f
            )

            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(
                    onClick = { engine.registerTap() },
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                ) {
                    Text("Tap tempo", fontSize = 11.sp)
                }
                Spacer(Modifier.width(8.dp))
                FilterChip(
                    selected = engine.autoDetectEnabled,
                    onClick = { engine.autoDetectEnabled = !engine.autoDetectEnabled },
                    label = { Text("Auto BPM", fontSize = 11.sp) }
                )
            }
            if (engine.autoDetectEnabled) {
                Text(
                    engine.detectedBpm?.let { "Détecté : $it BPM" } ?: "Détection en cours…",
                    color = Color.Cyan,
                    fontSize = 11.sp
                )
            }

            Spacer(Modifier.height(12.dp))

            Text(
                "Subdivision : ${"%.2f".format(engine.stepSubdivision)}x noire",
                color = Color.White,
                fontSize = 13.sp
            )
            Slider(
                value = engine.stepSubdivision,
                onValueChange = { engine.stepSubdivision = it },
                valueRange = 0.125f..2.0f
            )

            Spacer(Modifier.height(12.dp))

            Text(
                "Gate width : ${(engine.gateWidth * 100).toInt()}%",
                color = Color.White,
                fontSize = 13.sp
            )
            Slider(
                value = engine.gateWidth,
                onValueChange = { engine.gateWidth = it },
                valueRange = 0.05f..1.0f
            )

            Spacer(Modifier.height(12.dp))

            Text("Nombre de steps : ${engine.stepCount}", color = Color.White, fontSize = 13.sp)
            Slider(
                value = engine.stepCount.toFloat(),
                onValueChange = { engine.changeStepCount(it.toInt()) },
                valueRange = 2f..32f,
                steps = 29
            )

            Spacer(Modifier.height(16.dp))

            Text("Patterns DnB", color = Color.White, fontSize = 13.sp)
            Spacer(Modifier.height(4.dp))
            DnbPatterns.all.forEach { (name, stepsPattern) ->
                OutlinedButton(
                    onClick = { engine.applyPattern(stepsPattern) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 2.dp),
                    contentPadding = PaddingValues(vertical = 4.dp)
                ) {
                    Text(name, fontSize = 11.sp)
                }
            }

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
                        .clickable { engine.toggleStep(i) }
                )
            }
        }
    }
}
