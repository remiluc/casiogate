package com.example.casiogate

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.PowerManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationCompat
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

/**
 * Service qui héberge le GateEngine et tourne en foreground, avec un
 * WakeLock partiel — permet au traitement audio de continuer même
 * lorsque l'écran s'éteint ou que le téléphone est verrouillé. Sans ça,
 * Android suspend le thread audio après quelques secondes d'écran
 * éteint (Doze mode / App Standby), ce qui couperait le son en plein
 * live.
 */
class GateForegroundService : Service() {

    private val binder = LocalBinder()
    lateinit var engine: GateEngine
        private set
    private var wakeLock: PowerManager.WakeLock? = null

    inner class LocalBinder : android.os.Binder() {
        fun getService(): GateForegroundService = this@GateForegroundService
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        engine = GateEngine(applicationContext)
        engine.onStartPlayback = { acquireWakeLock() }
        engine.onStopPlayback = { releaseWakeLock() }

        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "CasioGate::AudioWakeLock"
        )

        startForeground(NOTIFICATION_ID, buildNotification())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // START_STICKY : si le système tue le service pour libérer de la
        // mémoire, il tentera de le relancer — utile pour un usage live
        // où on ne veut pas perdre le son en cours de route.
        return START_STICKY
    }

    fun acquireWakeLock() {
        wakeLock?.let { if (!it.isHeld) it.acquire(4 * 60 * 60 * 1000L) } // max 4h de sécurité
    }

    fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release() }
    }

    private fun buildNotification(): Notification {
        val channelId = "casio_gate_channel"

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "Casio Gate — traitement audio",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }

        val pendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, channelId)
            .setContentTitle("Casio Gate actif")
            .setContentText("Le traitement audio continue en arrière-plan")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        releaseWakeLock()
        engine.stop()
        super.onDestroy()
    }

    companion object {
        private const val NOTIFICATION_ID = 1
    }
}

class MainActivity : ComponentActivity() {

    private var boundService: GateForegroundService? = null
    private var isBound by mutableStateOf(false)

    private val connection = object : android.content.ServiceConnection {
        override fun onServiceConnected(name: android.content.ComponentName?, service: IBinder?) {
            val binder = service as GateForegroundService.LocalBinder
            boundService = binder.getService()
            boundService?.engine?.micPermissionGranted =
                ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
            isBound = true
        }

        override fun onServiceDisconnected(name: android.content.ComponentName?) {
            isBound = false
            boundService = null
        }
    }

    private val requestMicPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        boundService?.engine?.micPermissionGranted = granted
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Force le mode paysage pour toute l'activité
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            requestMicPermission.launch(Manifest.permission.RECORD_AUDIO)
        }

        val serviceIntent = Intent(this, GateForegroundService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }
        bindService(serviceIntent, connection, Context.BIND_AUTO_CREATE)

        setContent {
            if (isBound) {
                boundService?.engine?.let { engine ->
                    CasioGateScreen(engine)
                }
            } else {
                // Écran de chargement minimal pendant la connexion au service
                Box(
                    modifier = Modifier.fillMaxSize().background(Color.Black),
                    contentAlignment = Alignment.Center
                ) {
                    Text("Chargement…", color = Color.White)
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // Le service continue de tourner (foreground + START_STICKY) même
        // après unbind, tant que l'utilisateur ne l'arrête pas
        // explicitement — c'est ce qui permet au son de continuer écran
        // éteint. On délie juste la connexion UI.
        if (isBound) {
            unbindService(connection)
            isBound = false
        }
    }
}
enum class InputSource {
    MIC,
    USB_LINE
}

enum class FilterType {
    LOWPASS,
    HIGHPASS
}

/**
 * Représente l'état d'un step, avec 3 possibilités :
 * - fermé (silence)
 * - ouvert normal (son passe, gaté selon gateWidth)
 * - stutter (répète en boucle un court fragment capturé au début du
 *   step, façon glitch/beat-repeat)
 * La durée de tous les steps est réglée globalement (BPM + subdivision).
 */
enum class StepMode {
    CLOSED,
    OPEN,
    STUTTER
}

data class StepConfig(
    val mode: StepMode = StepMode.CLOSED
) {
    // Compat : ancien champ "open" utilisé ailleurs dans le code
    val open: Boolean get() = mode == StepMode.OPEN || mode == StepMode.STUTTER
}

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
            val newMode = if (kotlin.random.Random.nextBoolean()) StepMode.OPEN else StepMode.CLOSED
            pattern[i] = StepConfig(mode = newMode)
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
        // Liste noire plutôt que blanche : on exclut seulement ce qui
        // n'a clairement aucun sens comme source audio (télé, remote
        // submix réservé au système, FM tuner...), pour être sûr de ne
        // jamais filtrer par erreur un device USB au type inattendu
        // (ex: TYPE_USB_ACCESSORY, TYPE_DOCK, etc.).
        val excludedTypes = setOf(
            AudioDeviceInfo.TYPE_TELEPHONY,
            AudioDeviceInfo.TYPE_REMOTE_SUBMIX,
            AudioDeviceInfo.TYPE_FM_TUNER,
            AudioDeviceInfo.TYPE_TV_TUNER
        )
        return audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)
            .filter { it.type !in excludedTypes }
            // Android expose parfois le même device physique deux fois
            // (ex: deux entrées TYPE_BUILTIN_MIC). On ne garde qu'une
            // entrée par type pour un affichage propre.
            .distinctBy { it.type }
    }

    fun inputDeviceLabel(device: AudioDeviceInfo): String = when (device.type) {
        AudioDeviceInfo.TYPE_WIRED_HEADSET -> "Jack (entrée filaire)"
        AudioDeviceInfo.TYPE_USB_DEVICE -> "USB-C"
        AudioDeviceInfo.TYPE_USB_HEADSET -> "USB-C (headset)"
        AudioDeviceInfo.TYPE_USB_ACCESSORY -> "USB-C (accessoire)"
        AudioDeviceInfo.TYPE_BUILTIN_MIC -> "Micro intégré"
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "Bluetooth"
        AudioDeviceInfo.TYPE_DOCK -> "Dock"
        // Fallback : affiche le code type brut plutôt qu'"Autre" muet,
        // pour pouvoir identifier un type de device non prévu.
        else -> "Autre (type ${device.type})"
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
            pattern[i] = StepConfig(mode = if (value) StepMode.OPEN else StepMode.CLOSED)
        }
    }

    // Durée du fondu anti-clic à chaque transition son/silence, en ms.
    // Plus court = coupure plus sèche/numérique, plus long = plus doux/
    // analogique. 0.5ms mini (toujours nécessaire pour éviter le clic),
    // 30ms max (au-delà ça devient audible comme un vrai fade, pas juste
    // anti-clic).
    var fadeMs by mutableStateOf(3f)

    // Niveau de sortie général (0.0 à 2.0). 1.0 = signal inchangé,
    // au-delà = amplification (utile si le PT-20 sort faible), en
    // dessous = atténuation (utile pour éviter la saturation via un
    // adaptateur USB-C mic-in comme discuté).
    var outputGain by mutableStateOf(1.0f)

    // Filtre simple à un pôle (passe-bas ou passe-haut), appliqué juste
    // avant la sortie. cutoffHz : fréquence de coupure. filterType :
    // choix entre passe-bas, passe-haut, ou désactivé.
    var filterEnabled by mutableStateOf(false)
    var filterType by mutableStateOf(FilterType.LOWPASS)
    var filterCutoffHz by mutableStateOf(2000f)
    private var filterState = 0.0 // état interne du filtre (mémoire d'1 échantillon)

    // Wet/dry mix : proportion de signal traité (gate+filtre) vs signal
    // brut non gaté, mélangés ensemble en sortie. 1.0 = 100% traité
    // (comportement actuel), 0.0 = 100% brut (le gate n'a plus d'effet
    // audible, mais reste visible sur la grille).
    var wetDryMix by mutableStateOf(1.0f)

    // --- Bitcrush ---
    // Bit depth réduit (1 à 16 bits). 16 = pas de réduction (qualité
    // normale). Plus bas = moins de niveaux d'amplitude = son granuleux/
    // saturé, typique d'un bitcrusher.
    var crushBitDepthEnabled by mutableStateOf(false)
    var crushBitDepth by mutableStateOf(16f)

    // Sample rate réduit, exprimé comme un facteur de division du sample
    // rate réel (1 = pas de réduction, 8 = un échantillon sur 8 est
    // retenu, les autres répètent la dernière valeur retenue). Ça crée
    // l'effet de repliement/aliasing métallique typique.
    var crushSampleRateEnabled by mutableStateOf(false)
    var crushSampleRateDivider by mutableStateOf(1f)
    private var crushHoldSample: Short = 0
    private var crushHoldCounter = 0

    // --- Stutter ---
    // Longueur du fragment répété en boucle sur les steps en mode
    // STUTTER, exprimée en millisecondes. Court = glitch rapide/aigu,
    // long = répétition plus lente et reconnaissable.
    var stutterFragmentMs by mutableStateOf(40f)
    // Buffer circulaire qui capture le fragment à répéter, rempli au
    // début de chaque step en mode STUTTER et rejoué en boucle ensuite.
    private var stutterCaptureBuffer = ShortArray(0)
    private var stutterCaptured = false
    private var stutterReadPos = 0
    private var lastStutterStepIdx = -1

    // --- Presets (3 mémoires, pour rappel rapide en live) ---
    // Capture tous les paramètres modifiables de l'appli en un seul
    // instantané immuable.
    private data class PresetSnapshot(
        val pattern: List<StepConfig>,
        val stepCount: Int,
        val bpm: Int,
        val stepSubdivision: Float,
        val gateWidth: Float,
        val fadeMs: Float,
        val outputGain: Float,
        val filterEnabled: Boolean,
        val filterType: FilterType,
        val filterCutoffHz: Float,
        val wetDryMix: Float,
        val crushBitDepthEnabled: Boolean,
        val crushBitDepth: Float,
        val crushSampleRateEnabled: Boolean,
        val crushSampleRateDivider: Float,
        val stutterFragmentMs: Float
    )

    // 3 emplacements, null tant qu'aucun preset n'a été sauvegardé.
    private val presetSlots = arrayOfNulls<PresetSnapshot>(3)
    // Expose à l'UI quels slots sont occupés (pour un indicateur visuel).
    val presetOccupied = mutableStateListOf(false, false, false)

    /** Sauvegarde l'état actuel complet dans le slot indiqué (0, 1 ou 2). */
    fun savePreset(slot: Int) {
        if (slot !in 0..2) return
        presetSlots[slot] = PresetSnapshot(
            pattern = pattern.toList(),
            stepCount = stepCount,
            bpm = bpm,
            stepSubdivision = stepSubdivision,
            gateWidth = gateWidth,
            fadeMs = fadeMs,
            outputGain = outputGain,
            filterEnabled = filterEnabled,
            filterType = filterType,
            filterCutoffHz = filterCutoffHz,
            wetDryMix = wetDryMix,
            crushBitDepthEnabled = crushBitDepthEnabled,
            crushBitDepth = crushBitDepth,
            crushSampleRateEnabled = crushSampleRateEnabled,
            crushSampleRateDivider = crushSampleRateDivider,
            stutterFragmentMs = stutterFragmentMs
        )
        presetOccupied[slot] = true
    }

    /** Recharge l'état complet depuis le slot indiqué, si occupé. */
    fun loadPreset(slot: Int) {
        if (slot !in 0..2) return
        val snap = presetSlots[slot] ?: return
        pushHistory()

        stepCount = snap.stepCount
        pattern.clear()
        pattern.addAll(snap.pattern)

        bpm = snap.bpm
        stepSubdivision = snap.stepSubdivision
        gateWidth = snap.gateWidth
        fadeMs = snap.fadeMs
        outputGain = snap.outputGain
        filterEnabled = snap.filterEnabled
        filterType = snap.filterType
        filterCutoffHz = snap.filterCutoffHz
        wetDryMix = snap.wetDryMix
        crushBitDepthEnabled = snap.crushBitDepthEnabled
        crushBitDepth = snap.crushBitDepth
        crushSampleRateEnabled = snap.crushSampleRateEnabled
        crushSampleRateDivider = snap.crushSampleRateDivider
        stutterFragmentMs = snap.stutterFragmentMs
    }

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

    /** Cycle un step à travers les 3 états : fermé -> ouvert -> stutter -> fermé. */
    fun toggleStep(index: Int) {
        pushHistory()
        val current = pattern[index]
        val nextMode = when (current.mode) {
            StepMode.CLOSED -> StepMode.OPEN
            StepMode.OPEN -> StepMode.STUTTER
            StepMode.STUTTER -> StepMode.CLOSED
        }
        pattern[index] = current.copy(mode = nextMode)
    }

    // Callbacks optionnels vers le Service hébergeant ce moteur, pour
    // acquérir/libérer le WakeLock au bon moment (pendant la lecture
    // seulement, pour ne pas garder le CPU éveillé inutilement).
    var onStartPlayback: (() -> Unit)? = null
    var onStopPlayback: (() -> Unit)? = null

    fun start() {
        if (running) return
        if (!micPermissionGranted) return
        running = true
        onStartPlayback?.invoke()
        audioThread = Thread { runAudioLoop() }
        audioThread?.start()
    }

    fun stop() {
        running = false
        audioThread?.join(500)
        audioThread = null
        onStopPlayback?.invoke()
    }

    @Suppress("MissingPermission")
    private fun runAudioLoop() {

        val sampleRate = 44100

        // Capture en STÉRÉO plutôt que mono : certains appareils (comme
        // le PT-20) envoient un signal mono sur un seul canal (gauche OU
        // droit) d'un jack stéréo. Capter en CHANNEL_IN_MONO ne lit que
        // le canal gauche par défaut sur Android, ce qui donne un signal
        // silencieux si la source utilise l'autre canal. En stéréo, on
        // capte les deux canaux et on les fusionne nous-mêmes plus bas.
        val minRecordBuf = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_STEREO,
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
            AudioFormat.CHANNEL_IN_STEREO,
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

        // Buffer d'entrée en stéréo (entrelacé L,R,L,R...), donc deux fois
        // plus d'échantillons que de frames.
        val stereoFrames = 256
        val stereoBuffer = ShortArray(stereoFrames * 2)

        // Buffer de sortie mono, un échantillon par frame — c'est sur
        // celui-ci que le gate est appliqué avant écriture.
        val monoBuffer = ShortArray(stereoFrames)

        record.startRecording()
        track.play()

        // Position continue en échantillons depuis le début de la lecture.
        // On garde aussi l'index du step courant et la position (en
        // échantillons) à l'intérieur de ce step, pour supporter des
        // durées de step différentes les unes des autres.
        var stepIdx = 0
        var posInCurrentStep = 0L

        // Buffer temporaire pour garder le signal brut (avant gate),
        // nécessaire pour le wet/dry mix plus bas.
        val dryBuffer = ShortArray(stereoFrames)

        while (running) {

            val stereoRead = record.read(stereoBuffer, 0, stereoBuffer.size)
            if (stereoRead <= 0) continue

            // Fusionne les deux canaux stéréo en un seul flux mono, en
            // sommant gauche+droite (clampé pour éviter le dépassement).
            // Ça capte le signal peu importe sur quel canal le PT-20
            // envoie réellement son mono.
            val frameCount = stereoRead / 2
            for (f in 0 until frameCount) {
                val left = stereoBuffer[f * 2].toInt()
                val right = stereoBuffer[f * 2 + 1].toInt()
                val summed = (left + right).coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                monoBuffer[f] = summed.toShort()
                dryBuffer[f] = summed.toShort() // copie brute, avant tout traitement
            }
            val read = frameCount

            // Analyse le signal brut (avant gating) pour la détection de
            // tempo, si activée — ne modifie pas le buffer.
            analyzeForTempo(monoBuffer, read, sampleRate)

            // Durée d'un step en échantillons, identique pour tous les
            // steps, dérivée du BPM et de la subdivision choisie.
            val stepSamples = (sampleRate * 60.0 / bpm * stepSubdivision)
                .toLong().coerceAtLeast(1)

            // Fondu anti-clic, recalculé à chaque buffer pour rester
            // réactif si l'utilisateur ajuste fadeMs en cours de lecture.
            val fadeSamples = (sampleRate * fadeMs / 1000.0).toInt().coerceAtLeast(1)

            val patternLen = pattern.size.coerceAtLeast(1)

            // Coefficient du filtre à un pôle, recalculé à chaque buffer
            // pour rester réactif si l'utilisateur change cutoffHz.
            // alpha proche de 0 = filtre très marqué, proche de 1 = filtre
            // léger (formule standard RC one-pole).
            val rc = 1.0 / (2.0 * Math.PI * filterCutoffHz)
            val dt = 1.0 / sampleRate
            val alpha = dt / (rc + dt)

            for (i in 0 until read) {

                // Sécurité si le pattern a été redimensionné pendant la lecture
                if (stepIdx >= patternLen) stepIdx = 0

                val step = pattern.getOrElse(stepIdx) { StepConfig(mode = StepMode.OPEN) }

                // --- Stutter : si ce step est en mode STUTTER, on
                // remplace l'échantillon source par un fragment capturé
                // au tout début du step, rejoué en boucle tant qu'on y
                // reste. Le gate/fade s'applique ensuite normalement
                // par-dessus ce signal en boucle.
                var sourceSample = monoBuffer[i]

                if (step.mode == StepMode.STUTTER) {
                    val fragmentSamples = (sampleRate * stutterFragmentMs / 1000.0)
                        .toInt().coerceIn(8, stepSamples.toInt().coerceAtLeast(8))

                    // Nouveau step stutter : on (re)capture le fragment
                    if (stepIdx != lastStutterStepIdx) {
                        stutterCaptureBuffer = ShortArray(fragmentSamples)
                        stutterCaptured = false
                        stutterReadPos = 0
                        lastStutterStepIdx = stepIdx
                    }

                    if (!stutterCaptured) {
                        // Phase de capture : on enregistre le signal réel
                        // entrant pendant les premiers fragmentSamples
                        // échantillons du step.
                        val capturePos = posInCurrentStep.toInt()
                        if (capturePos < stutterCaptureBuffer.size) {
                            stutterCaptureBuffer[capturePos] = sourceSample
                        } else {
                            stutterCaptured = true
                        }
                    } else {
                        // Phase de relecture en boucle du fragment capturé
                        if (stutterCaptureBuffer.isNotEmpty()) {
                            sourceSample = stutterCaptureBuffer[stutterReadPos]
                            stutterReadPos = (stutterReadPos + 1) % stutterCaptureBuffer.size
                        }
                    }
                } else if (stepIdx != lastStutterStepIdx) {
                    // On a quitté un step stutter, on nettoie l'état pour
                    // le prochain step stutter rencontré.
                    lastStutterStepIdx = -1
                }

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

                val stepIsAudible = step.mode == StepMode.OPEN || step.mode == StepMode.STUTTER
                val gateGain = if (stepIsAudible && isInAudiblePortion) fadeGain else 0.0

                var wetSample = sourceSample * gateGain

                // Filtre à un pôle, appliqué uniquement au signal traité
                // (wet), pas au dry — pour que le wet/dry mix garde un
                // signal brut non filtré comme référence.
                if (filterEnabled) {
                    filterState += alpha * (wetSample - filterState)
                    wetSample = when (filterType) {
                        FilterType.LOWPASS -> filterState
                        FilterType.HIGHPASS -> wetSample - filterState
                    }
                }

                // --- Bitcrush : réduction de sample rate (sample & hold)
                // puis réduction de bit depth (quantification), appliqués
                // uniquement au signal wet.
                if (crushSampleRateEnabled) {
                    val divider = crushSampleRateDivider.toInt().coerceAtLeast(1)
                    if (crushHoldCounter <= 0) {
                        crushHoldSample = wetSample.toInt().toShort()
                        crushHoldCounter = divider
                    }
                    wetSample = crushHoldSample.toDouble()
                    crushHoldCounter--
                }

                if (crushBitDepthEnabled) {
                    val bits = crushBitDepth.toInt().coerceIn(1, 16)
                    val levels = (1 shl bits) // nombre de niveaux d'amplitude possibles
                    val step2 = 65536.0 / levels
                    wetSample = (Math.round(wetSample / step2) * step2)
                }

                // Mix wet/dry : mélange le signal traité (gate+filtre+crush)
                // et le signal brut, puis applique le gain de sortie final.
                val drySample = dryBuffer[i].toDouble()
                val mixed = wetSample * wetDryMix + drySample * (1.0 - wetDryMix)
                val finalSample = (mixed * outputGain)
                    .coerceIn(Short.MIN_VALUE.toDouble(), Short.MAX_VALUE.toDouble())

                monoBuffer[i] = finalSample.toInt().toShort()

                posInCurrentStep++
                if (posInCurrentStep >= stepSamples) {
                    posInCurrentStep = 0
                    stepIdx = (stepIdx + 1) % patternLen
                }
            }

            track.write(monoBuffer, 0, read)

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

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        // Dimensions dérivées de la taille d'écran réelle, pour rester
        // cohérent aussi bien sur téléphone que sur tablette.
        // - La colonne de contrôles prend ~28% de la largeur, avec des
        //   bornes raisonnables (jamais trop étroite, jamais démesurée).
        // - Les cases de la grille grandissent avec l'écran, avec un
        //   maximum pour ne pas devenir énormes sur un grand écran.
        val controlsWidth = (maxWidth * 0.28f).coerceIn(200.dp, 340.dp)
        val stepCellSize = (maxWidth * 0.06f).coerceIn(40.dp, 80.dp)
        val basePadding = (maxWidth * 0.01f).coerceIn(8.dp, 20.dp)

        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(basePadding)
        ) {

        // Colonne de gauche : contrôles (scrollable pour rester accessible
        // même en paysage sur un écran bas)
        Column(
            modifier = Modifier
                .width(controlsWidth)
                .fillMaxHeight()
                .verticalScroll(rememberScrollState())
        ) {

            Text("CASIO GATE", color = Color.Red, style = MaterialTheme.typography.titleMedium)

            Spacer(Modifier.height(12.dp))

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

            Spacer(Modifier.height(16.dp))
            Text("Traitement sonore", color = Color.Cyan, fontSize = 13.sp)
            Spacer(Modifier.height(8.dp))

            Text(
                "Fade (anti-clic) : ${"%.1f".format(engine.fadeMs)} ms",
                color = Color.White,
                fontSize = 12.sp
            )
            Slider(
                value = engine.fadeMs,
                onValueChange = { engine.fadeMs = it },
                valueRange = 0.5f..30f
            )

            Spacer(Modifier.height(8.dp))

            Text(
                "Niveau de sortie : ${(engine.outputGain * 100).toInt()}%",
                color = Color.White,
                fontSize = 12.sp
            )
            Slider(
                value = engine.outputGain,
                onValueChange = { engine.outputGain = it },
                valueRange = 0f..2f
            )

            Spacer(Modifier.height(8.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Filtre", color = Color.White, fontSize = 12.sp)
                Spacer(Modifier.width(8.dp))
                FilterChip(
                    selected = engine.filterEnabled,
                    onClick = { engine.filterEnabled = !engine.filterEnabled },
                    label = { Text(if (engine.filterEnabled) "ON" else "OFF", fontSize = 10.sp) }
                )
            }
            if (engine.filterEnabled) {
                Row {
                    FilterChip(
                        selected = engine.filterType == FilterType.LOWPASS,
                        onClick = { engine.filterType = FilterType.LOWPASS },
                        label = { Text("Passe-bas", fontSize = 10.sp) }
                    )
                    Spacer(Modifier.width(6.dp))
                    FilterChip(
                        selected = engine.filterType == FilterType.HIGHPASS,
                        onClick = { engine.filterType = FilterType.HIGHPASS },
                        label = { Text("Passe-haut", fontSize = 10.sp) }
                    )
                }
                Text(
                    "Coupure : ${engine.filterCutoffHz.toInt()} Hz",
                    color = Color.White,
                    fontSize = 11.sp
                )
                Slider(
                    value = engine.filterCutoffHz,
                    onValueChange = { engine.filterCutoffHz = it },
                    valueRange = 100f..8000f
                )
            }

            Spacer(Modifier.height(8.dp))

            Text(
                "Wet/Dry : ${(engine.wetDryMix * 100).toInt()}% traité",
                color = Color.White,
                fontSize = 12.sp
            )
            Slider(
                value = engine.wetDryMix,
                onValueChange = { engine.wetDryMix = it },
                valueRange = 0f..1f
            )

            Spacer(Modifier.height(12.dp))

            Text("Nombre de steps : ${engine.stepCount}", color = Color.White, fontSize = 13.sp)
            Slider(
                value = engine.stepCount.toFloat(),
                onValueChange = { engine.changeStepCount(it.toInt()) },
                valueRange = 2f..32f,
                steps = 29
            )

            Spacer(Modifier.height(12.dp))

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

            Spacer(Modifier.height(8.dp))

            Text(
                "Presets (appui court : charger, appui long : sauver)",
                color = Color.Gray,
                fontSize = 9.sp
            )
            Row(modifier = Modifier.fillMaxWidth()) {
                for (slot in 0..2) {
                    val occupied = engine.presetOccupied[slot]
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .padding(horizontal = 2.dp)
                            .background(if (occupied) Color(0xFF2A4D2A) else Color.DarkGray)
                            .pointerInput(slot) {
                                detectTapGestures(
                                    onTap = { engine.loadPreset(slot) },
                                    onLongPress = { engine.savePreset(slot) }
                                )
                            }
                            .padding(vertical = 10.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            "P${slot + 1}",
                            color = if (occupied) Color.Green else Color.LightGray,
                            fontSize = 12.sp
                        )
                    }
                }
            }

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

        // Colonne de droite : grille de steps, puis en dessous les
        // réglages d'entrée/sortie audio.
        Column(
            modifier = Modifier
                .fillMaxHeight()
                .verticalScroll(rememberScrollState())
        ) {

            LazyVerticalGrid(
                columns = GridCells.Fixed(8),
                modifier = Modifier.height(220.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                items(engine.pattern.size) { i ->
                    val step = engine.pattern[i]
                    Box(
                        modifier = Modifier
                            .size(stepCellSize)
                            .background(
                                when {
                                    i == engine.currentStep && playing -> Color.White
                                    step.mode == StepMode.STUTTER -> Color.Yellow
                                    step.mode == StepMode.OPEN -> Color.Red
                                    else -> Color.DarkGray
                                }
                            )
                            .clickable { engine.toggleStep(i) }
                    )
                }
            }

            Spacer(Modifier.height(20.dp))

            Text("Bit crush", color = Color.Cyan, fontSize = 13.sp)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Actif", color = Color.White, fontSize = 12.sp)
                Spacer(Modifier.width(8.dp))
                FilterChip(
                    selected = engine.crushBitDepthEnabled,
                    onClick = { engine.crushBitDepthEnabled = !engine.crushBitDepthEnabled },
                    label = { Text(if (engine.crushBitDepthEnabled) "ON" else "OFF", fontSize = 10.sp) }
                )
            }
            if (engine.crushBitDepthEnabled) {
                Text(
                    "${engine.crushBitDepth.toInt()} bits",
                    color = Color.White,
                    fontSize = 11.sp
                )
                Slider(
                    value = engine.crushBitDepth,
                    onValueChange = { engine.crushBitDepth = it },
                    valueRange = 1f..16f
                )
            }

            Spacer(Modifier.height(12.dp))

            Text("Sample crush", color = Color.Cyan, fontSize = 13.sp)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Actif", color = Color.White, fontSize = 12.sp)
                Spacer(Modifier.width(8.dp))
                FilterChip(
                    selected = engine.crushSampleRateEnabled,
                    onClick = { engine.crushSampleRateEnabled = !engine.crushSampleRateEnabled },
                    label = { Text(if (engine.crushSampleRateEnabled) "ON" else "OFF", fontSize = 10.sp) }
                )
            }
            if (engine.crushSampleRateEnabled) {
                Text(
                    "÷${engine.crushSampleRateDivider.toInt()}",
                    color = Color.White,
                    fontSize = 11.sp
                )
                Slider(
                    value = engine.crushSampleRateDivider,
                    onValueChange = { engine.crushSampleRateDivider = it },
                    valueRange = 1f..64f
                )
            }

            Spacer(Modifier.height(12.dp))

            Text("Stutter", color = Color.Cyan, fontSize = 13.sp)
            Text(
                "Durée du fragment : ${engine.stutterFragmentMs.toInt()} ms",
                color = Color.White,
                fontSize = 12.sp
            )
            Slider(
                value = engine.stutterFragmentMs,
                onValueChange = { engine.stutterFragmentMs = it },
                valueRange = 5f..200f
            )
            Text(
                "Tap sur un step : fermé → ouvert → stutter → fermé",
                color = Color.Gray,
                fontSize = 10.sp
            )

            Spacer(Modifier.height(20.dp))

            Text("Entrée :", color = Color.White, fontSize = 13.sp)
            val inputs = remember { engine.availableInputDevices() }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterChip(
                    selected = engine.preferredInputDevice == null,
                    onClick = { engine.preferredInputDevice = null },
                    label = { Text("Auto (système)", fontSize = 11.sp) }
                )
                inputs.forEach { device ->
                    FilterChip(
                        selected = engine.preferredInputDevice?.id == device.id,
                        onClick = { engine.preferredInputDevice = device },
                        label = { Text(engine.inputDeviceLabel(device), fontSize = 11.sp) }
                    )
                }
            }

            Spacer(Modifier.height(12.dp))

            Text("Sortie :", color = Color.White, fontSize = 13.sp)
            val outputs = remember { engine.availableOutputDevices() }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterChip(
                    selected = engine.preferredOutputDevice == null,
                    onClick = { engine.preferredOutputDevice = null },
                    label = { Text("Auto (système)", fontSize = 11.sp) }
                )
                outputs.forEach { device ->
                    FilterChip(
                        selected = engine.preferredOutputDevice?.id == device.id,
                        onClick = { engine.preferredOutputDevice = device },
                        label = { Text(engine.outputDeviceLabel(device), fontSize = 11.sp) }
                    )
                }
            }
        }
        }
    }
}
