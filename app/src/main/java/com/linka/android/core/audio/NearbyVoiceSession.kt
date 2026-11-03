package com.linka.android.core.audio

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.os.Build
import androidx.core.content.ContextCompat
import com.linka.android.core.nearby.NearbyDirectLink
import com.linka.android.core.nearby.NearbyIncomingStream
import com.linka.android.core.nearby.NearbyOutgoingStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.IOException
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicBoolean

data class VoiceSessionState(
    val isStarting: Boolean = false,
    val isRunning: Boolean = false,
    val status: String = "Voice media stopped",
    val error: String? = null
)

/**
 * Foreground-only, full-duplex 8 kHz narrowband voice path.
 *
 * Audio is captured as PCM16 mono, compressed to G.711 mu-law 20 ms frames,
 * and carried by a Nearby Connections stream. The Nearby SDK encrypts the
 * direct transport. A separate signed identity/key schedule is still required
 * before mesh relaying or any claim of app-layer E2E protection.
 */
class NearbyVoiceSession(
    context: Context,
    private val nearbyLink: NearbyDirectLink
) {
    private val appContext = context.applicationContext
    private val audioManager = appContext.getSystemService(AudioManager::class.java)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val _state = MutableStateFlow(VoiceSessionState())
    val state: StateFlow<VoiceSessionState> = _state.asStateFlow()

    private var callScope: CoroutineScope? = null
    private var callJob: Job? = null
    private var endpointId: String? = null
    private var outgoingStream: NearbyOutgoingStream? = null
    private var incomingStream: NearbyIncomingStream? = null
    private val incomingStreamLock = Any()
    private val pendingIncomingStreams = ArrayDeque<NearbyIncomingStream>()
    private var recorder: AudioRecord? = null
    private var player: AudioTrack? = null
    private var echoCanceler: AcousticEchoCanceler? = null
    private var noiseSuppressor: NoiseSuppressor? = null
    private var gainControl: AutomaticGainControl? = null
    private var previousAudioMode: Int? = null
    private var focusRequest: AudioFocusRequest? = null
    private val started = AtomicBoolean(false)

    init {
        scope.launch(Dispatchers.IO) {
            nearbyLink.incomingStreams.collect { incoming ->
                dispatchIncomingStream(incoming)
            }
        }
    }

    fun start(peerEndpointId: String) {
        if (!started.compareAndSet(false, true)) return
        endpointId = peerEndpointId
        _state.value = VoiceSessionState(isStarting = true, status = "Preparing microphone and speaker")

        val job = SupervisorJob()
        callJob = job
        callScope = CoroutineScope(job + Dispatchers.IO)
        drainPendingIncomingStreams()

        val setupError = try {
            prepareAudioDevices()
            null
        } catch (error: Exception) {
            error.localizedMessage ?: "Unable to prepare audio devices"
        }

        if (setupError != null) {
            fail(setupError)
            return
        }

        nearbyLink.openOutgoingStream(peerEndpointId) { writer, error ->
            if (error != null || writer == null) {
                fail(error ?: "Could not open the nearby voice stream")
                return@openOutgoingStream
            }
            if (!started.get()) {
                writer.close()
                return@openOutgoingStream
            }
            outgoingStream = writer
            _state.value = VoiceSessionState(
                isRunning = true,
                status = "Nearby voice stream active"
            )
            callScope?.launch { captureAndSend(writer) }
        }
    }

    private fun prepareAudioDevices() {
        check(
            ContextCompat.checkSelfPermission(appContext, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
        ) { "Microphone permission is required for LINKA calls" }

        val sampleRate = VoiceFrameCodec.SAMPLE_RATE_HZ
        val recordBuffer = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        val playbackBuffer = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        check(recordBuffer > 0) { "This device does not support 8 kHz microphone capture" }
        check(playbackBuffer > 0) { "This device does not support 8 kHz audio playback" }

        requestAudioFocus()
        previousAudioMode = audioManager.mode
        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION

        val formatIn = AudioFormat.Builder()
            .setSampleRate(sampleRate)
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
            .build()
        val formatOut = AudioFormat.Builder()
            .setSampleRate(sampleRate)
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
            .build()

        val audioRecord = AudioRecord.Builder()
            .setAudioSource(MediaRecorder.AudioSource.VOICE_COMMUNICATION)
            .setAudioFormat(formatIn)
            .setBufferSizeInBytes(maxOf(recordBuffer * 2, VoiceFrameCodec.SAMPLES_PER_FRAME * 4))
            .build()
        check(audioRecord.state == AudioRecord.STATE_INITIALIZED) {
            "The Android microphone could not be initialized"
        }

        val audioTrack = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(formatOut)
            .setBufferSizeInBytes(maxOf(playbackBuffer * 2, VoiceFrameCodec.SAMPLES_PER_FRAME * 4))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        check(audioTrack.state == AudioTrack.STATE_INITIALIZED) {
            "The Android speaker could not be initialized"
        }

        recorder = audioRecord
        player = audioTrack

        if (AcousticEchoCanceler.isAvailable()) {
            echoCanceler = AcousticEchoCanceler.create(audioRecord.audioSessionId)?.apply {
                enabled = true
            }
        }
        if (NoiseSuppressor.isAvailable()) {
            noiseSuppressor = NoiseSuppressor.create(audioRecord.audioSessionId)?.apply {
                enabled = true
            }
        }
        if (AutomaticGainControl.isAvailable()) {
            gainControl = AutomaticGainControl.create(audioRecord.audioSessionId)?.apply {
                enabled = true
            }
        }

        audioTrack.play()
        audioRecord.startRecording()
        check(audioRecord.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
            "Android did not start microphone capture"
        }
    }

    private fun requestAudioFocus() {
        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                .setAudioAttributes(attributes)
                .setOnAudioFocusChangeListener { change ->
                    if (change == AudioManager.AUDIOFOCUS_LOSS ||
                        change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT
                    ) {
                        stop("Audio focus lost")
                    }
                }
                .build()
            focusRequest = request
            check(audioManager.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
                "LINKA could not obtain audio focus"
            }
        } else {
            @Suppress("DEPRECATION")
            check(
                audioManager.requestAudioFocus(
                    null,
                    AudioManager.STREAM_VOICE_CALL,
                    AudioManager.AUDIOFOCUS_GAIN_TRANSIENT
                ) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
            ) { "LINKA could not obtain audio focus" }
        }
    }

    private fun captureAndSend(writer: NearbyOutgoingStream) {
        val audioRecord = recorder ?: return
        val frame = ShortArray(VoiceFrameCodec.SAMPLES_PER_FRAME)
        var sequence = 0L

        try {
            while (started.get()) {
                var collected = 0
                while (collected < frame.size && started.get()) {
                    val count = audioRecord.read(
                        frame,
                        collected,
                        frame.size - collected,
                        AudioRecord.READ_BLOCKING
                    )
                    if (count < 0) throw IOException("Microphone read failed: $count")
                    if (count == 0) continue
                    collected += count
                }
                if (!started.get()) break
                VoiceFrameCodec.write(writer.let { StreamOutputAdapter(it) }, sequence++, frame)
            }
        } catch (error: Exception) {
            if (started.get()) fail(error.localizedMessage ?: "Microphone stream failed")
        }
    }

    private fun dispatchIncomingStream(incoming: NearbyIncomingStream) {
        synchronized(incomingStreamLock) {
            val connectedEndpoint = nearbyLink.state.value.connectedPeer?.endpointId
            if (incoming.endpointId != connectedEndpoint) {
                incoming.close()
                return
            }

            val worker = callScope
            if (started.get() && worker != null && incoming.endpointId == endpointId) {
                worker.launch { receiveAudio(incoming) }
            } else {
                // Both peers open their outgoing stream after accepting. The
                // remote stream can therefore arrive just before this side
                // processes ACCEPT and starts its own session.
                pendingIncomingStreams.addLast(incoming)
            }
        }
    }

    private fun drainPendingIncomingStreams() {
        synchronized(incomingStreamLock) {
            val worker = callScope ?: return
            while (pendingIncomingStreams.isNotEmpty()) {
                val incoming = pendingIncomingStreams.removeFirst()
                if (incoming.endpointId == endpointId) {
                    worker.launch { receiveAudio(incoming) }
                } else {
                    incoming.close()
                }
            }
        }
    }

    private fun receiveAudio(incoming: NearbyIncomingStream) {
        incomingStream = incoming
        val audioTrack = player
        if (audioTrack == null) {
            incoming.close()
            return
        }

        var lastSequence = -1L
        try {
            incoming.input.use { input ->
                while (started.get()) {
                    val frame = VoiceFrameCodec.read(input) ?: break
                    if (frame.sequence <= lastSequence) continue
                    lastSequence = frame.sequence

                    var written = 0
                    while (written < frame.pcm.size && started.get()) {
                        val count = audioTrack.write(
                            frame.pcm,
                            written,
                            frame.pcm.size - written,
                            AudioTrack.WRITE_BLOCKING
                        )
                        if (count < 0) throw IOException("Speaker write failed: $count")
                        written += count
                    }
                }
            }
        } catch (error: Exception) {
            if (started.get()) fail(error.localizedMessage ?: "Incoming voice stream failed")
        } finally {
            incoming.close()
            if (incomingStream === incoming) incomingStream = null
        }
    }

    private class StreamOutputAdapter(
        private val stream: NearbyOutgoingStream
    ) : java.io.OutputStream() {
        override fun write(value: Int) {
            stream.write(byteArrayOf(value.toByte()))
        }

        override fun write(buffer: ByteArray, offset: Int, length: Int) {
            stream.write(buffer.copyOfRange(offset, offset + length))
        }

        override fun flush() = Unit
        override fun close() = stream.close()
    }

    private fun fail(message: String) {
        _state.value = VoiceSessionState(
            status = "Voice media failed",
            error = message
        )
        stop(message)
        _state.value = VoiceSessionState(
            status = "Voice media failed",
            error = message
        )
    }

    fun stop(reason: String = "Voice media stopped") {
        if (!started.getAndSet(false)) return
        outgoingStream?.close()
        outgoingStream = null
        synchronized(incomingStreamLock) {
            while (pendingIncomingStreams.isNotEmpty()) {
                pendingIncomingStreams.removeFirst().close()
            }
            incomingStream?.close()
            incomingStream = null
        }
        runCatching { recorder?.stop() }
        runCatching { player?.pause() }
        runCatching { player?.flush() }
        runCatching { player?.stop() }
        runCatching { echoCanceler?.release() }
        runCatching { noiseSuppressor?.release() }
        runCatching { gainControl?.release() }
        runCatching { recorder?.release() }
        runCatching { player?.release() }
        echoCanceler = null
        noiseSuppressor = null
        gainControl = null
        recorder = null
        player = null
        callScope?.cancel()
        callScope = null
        callJob = null

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            focusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
        } else {
            @Suppress("DEPRECATION")
            audioManager.abandonAudioFocus(null)
        }
        focusRequest = null
        previousAudioMode?.let { audioManager.mode = it }
        previousAudioMode = null
        endpointId = null
        _state.value = VoiceSessionState(status = reason)
    }

    fun close() {
        stop()
        scope.cancel()
    }
}
