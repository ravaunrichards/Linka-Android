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
import com.linka.android.core.security.SecureCallCipher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Full-duplex audio engine for end-to-end encrypted mesh voice frames.
 * Each 20 ms frame is sent as its own bounded mesh data packet. The supplied
 * transport callbacks only ever receive AEAD ciphertext.
 */
class RoutedVoiceSession(context: Context) {
    private val appContext = context.applicationContext
    private val audioManager = appContext.getSystemService(AudioManager::class.java)
    private val _state = MutableStateFlow(VoiceSessionState())
    val state: StateFlow<VoiceSessionState> = _state.asStateFlow()

    private var scope: CoroutineScope? = null
    private var cipher: SecureCallCipher? = null
    private var recorder: AudioRecord? = null
    private var player: AudioTrack? = null
    private var echoCanceler: AcousticEchoCanceler? = null
    private var noiseSuppressor: NoiseSuppressor? = null
    private var gainControl: AutomaticGainControl? = null
    private var previousAudioMode: Int? = null
    private var focusRequest: AudioFocusRequest? = null
    private val started = AtomicBoolean(false)

    fun start(
        secureCipher: SecureCallCipher,
        sendEncryptedFrame: (ByteArray) -> Unit,
        incomingEncryptedFrames: Flow<ByteArray>
    ) {
        if (!started.compareAndSet(false, true)) {
            secureCipher.destroy()
            return
        }
        cipher = secureCipher
        _state.value = VoiceSessionState(
            isStarting = true,
            status = "Preparing encrypted mesh voice"
        )

        try {
            prepareAudioDevices()
        } catch (error: Exception) {
            fail(error.localizedMessage ?: "Could not prepare routed audio")
            return
        }

        val worker = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope = worker
        _state.value = VoiceSessionState(
            isRunning = true,
            status = "End-to-end encrypted mesh voice active"
        )
        worker.launch { captureAndSend(sendEncryptedFrame) }
        worker.launch { receiveAndPlay(incomingEncryptedFrames) }
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

        val inputFormat = AudioFormat.Builder()
            .setSampleRate(sampleRate)
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
            .build()
        val outputFormat = AudioFormat.Builder()
            .setSampleRate(sampleRate)
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
            .build()

        val audioRecord = AudioRecord.Builder()
            .setAudioSource(MediaRecorder.AudioSource.VOICE_COMMUNICATION)
            .setAudioFormat(inputFormat)
            .setBufferSizeInBytes(maxOf(recordBuffer * 2, VoiceFrameCodec.SAMPLES_PER_FRAME * 4))
            .build()
        recorder = audioRecord
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
            .setAudioFormat(outputFormat)
            .setBufferSizeInBytes(maxOf(playbackBuffer * 2, VoiceFrameCodec.SAMPLES_PER_FRAME * 4))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        player = audioTrack
        check(audioTrack.state == AudioTrack.STATE_INITIALIZED) {
            "The Android speaker could not be initialized"
        }
        if (AcousticEchoCanceler.isAvailable()) {
            echoCanceler = runCatching {
                AcousticEchoCanceler.create(audioRecord.audioSessionId)?.apply { enabled = true }
            }.getOrNull()
        }
        if (NoiseSuppressor.isAvailable()) {
            noiseSuppressor = runCatching {
                NoiseSuppressor.create(audioRecord.audioSessionId)?.apply { enabled = true }
            }.getOrNull()
        }
        if (AutomaticGainControl.isAvailable()) {
            gainControl = runCatching {
                AutomaticGainControl.create(audioRecord.audioSessionId)?.apply { enabled = true }
            }.getOrNull()
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

    private fun captureAndSend(sendEncryptedFrame: (ByteArray) -> Unit) {
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
                val plaintext = VoiceFrameCodec.encode(sequence++, frame)
                val encrypted = cipher?.encryptFrame(plaintext)
                    ?: throw IOException("Secure mesh voice keys are unavailable")
                sendEncryptedFrame(encrypted)
            }
        } catch (error: Exception) {
            if (started.get()) fail(error.localizedMessage ?: "Encrypted mesh voice send failed")
        }
    }

    private suspend fun receiveAndPlay(incomingEncryptedFrames: Flow<ByteArray>) {
        val audioTrack = player ?: return
        var lastSequence = -1L
        try {
            incomingEncryptedFrames.collect { encrypted ->
                if (!started.get()) return@collect
                val plaintext = cipher?.decryptFrame(encrypted)
                    ?: throw IOException("Secure mesh voice keys are unavailable")
                val frame = VoiceFrameCodec.decode(plaintext)
                // Replayed or late frames are discarded without tearing down
                // an otherwise healthy call. The AEAD replay window already
                // rejects duplicate ciphertext; this also protects playout
                // ordering when a routed frame arrives out of sequence.
                if (frame.sequence <= lastSequence) return@collect
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
        } catch (error: Exception) {
            if (started.get()) fail(error.localizedMessage ?: "Encrypted mesh voice receive failed")
        }
    }

    private fun fail(message: String) {
        stop(message)
        _state.value = VoiceSessionState(status = "Mesh voice failed", error = message)
    }

    fun stop(reason: String = "Mesh voice stopped") {
        if (!started.getAndSet(false)) return
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
        scope?.cancel()
        scope = null
        cipher?.destroy()
        cipher = null

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            focusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
        } else {
            @Suppress("DEPRECATION")
            audioManager.abandonAudioFocus(null)
        }
        focusRequest = null
        previousAudioMode?.let { audioManager.mode = it }
        previousAudioMode = null
        _state.value = VoiceSessionState(status = reason)
    }

    fun close() = stop()
}
