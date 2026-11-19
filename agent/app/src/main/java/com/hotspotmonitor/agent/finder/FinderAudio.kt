package com.hotspotmonitor.agent.finder

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.ToneGenerator
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.hotspotmonitor.agent.core.AgentLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.PI
import kotlin.math.sin

/**
 * Finder sound (plan 7.3 "Walk" and "Headless"): the metal-detector tone generated with AudioTrack
 * (no audio files), short confirmation beeps, and TextToSpeech.
 *
 * Everything plays on the alarm stream: holding a volume key for a headless gesture changes the
 * media volume, which must not silence the spoken result.
 */
internal class FinderAudio(private val context: Context, private val log: AgentLog) {
    private enum class TtsState { NONE, INITIALIZING, READY, FAILED }

    private val main = Handler(Looper.getMainLooper())
    private val lock = Any()
    private var tts: TextToSpeech? = null
    private var ttsState = TtsState.NONE
    private val pending = ArrayDeque<Pair<String, Boolean>>()
    private val utteranceIds = AtomicInteger()
    private var toneGenerator: ToneGenerator? = null

    /** True while TextToSpeech is talking; the walk tone pauses meanwhile. */
    @Volatile
    var speaking: Boolean = false
        private set

    private val attributes: AudioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ALARM)
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
        .build()

    /** Speaks [text]; [flush] drops anything still queued (periodic score updates). */
    fun speak(text: String, flush: Boolean = false) {
        ensureAudible()
        synchronized(lock) {
            when (ttsState) {
                TtsState.READY -> speakNow(text, flush)
                TtsState.FAILED -> log.d(TAG, "Speech unavailable; not saying \"$text\"")
                TtsState.INITIALIZING -> enqueue(text, flush)
                TtsState.NONE -> {
                    enqueue(text, flush)
                    initTts()
                }
            }
        }
    }

    private fun enqueue(text: String, flush: Boolean) {
        if (flush) pending.clear()
        if (pending.size >= MAX_PENDING) pending.removeFirst()
        pending.addLast(text to flush)
    }

    // Caller holds lock.
    private fun initTts() {
        ttsState = TtsState.INITIALIZING
        main.post {
            try {
                val engine = TextToSpeech(context.applicationContext) { status ->
                    // Posted again so that `tts` is assigned even if the engine calls back synchronously.
                    main.post { onTtsInit(status) }
                }
                synchronized(lock) { tts = engine }
            } catch (e: Exception) {
                log.w(TAG, "TextToSpeech could not be created", e)
                synchronized(lock) {
                    ttsState = TtsState.FAILED
                    pending.clear()
                }
            }
        }
    }

    private fun onTtsInit(status: Int) {
        synchronized(lock) {
            val engine = tts
            if (status != TextToSpeech.SUCCESS || engine == null) {
                log.w(TAG, "TextToSpeech failed to initialise (status $status)")
                ttsState = TtsState.FAILED
                pending.clear()
                return
            }
            try {
                val lang = engine.setLanguage(Locale.US)
                if (lang == TextToSpeech.LANG_MISSING_DATA || lang == TextToSpeech.LANG_NOT_SUPPORTED) {
                    engine.setLanguage(Locale.getDefault())
                }
                engine.setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build(),
                )
                engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {
                        speaking = true
                    }

                    override fun onDone(utteranceId: String?) {
                        speaking = false
                    }

                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) {
                        speaking = false
                    }

                    override fun onError(utteranceId: String?, errorCode: Int) {
                        speaking = false
                    }

                    override fun onStop(utteranceId: String?, interrupted: Boolean) {
                        speaking = false
                    }
                })
                ttsState = TtsState.READY
            } catch (e: Exception) {
                log.w(TAG, "TextToSpeech setup failed", e)
                ttsState = TtsState.FAILED
                pending.clear()
                return
            }
            while (pending.isNotEmpty()) {
                val (text, flush) = pending.removeFirst()
                speakNow(text, flush)
            }
        }
    }

    // Caller holds lock.
    private fun speakNow(text: String, flush: Boolean) {
        val engine = tts ?: return
        try {
            val params = Bundle().apply { putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 1f) }
            val mode = if (flush) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
            engine.speak(text, mode, params, "finder-${utteranceIds.incrementAndGet()}")
        } catch (e: Exception) {
            log.w(TAG, "TextToSpeech.speak failed", e)
        }
    }

    /** Short confirmation beep: one for a long press, two for a very long press. */
    fun beep(double: Boolean) {
        ensureAudible()
        try {
            val generator = synchronized(lock) {
                toneGenerator ?: ToneGenerator(AudioManager.STREAM_ALARM, BEEP_VOLUME).also { toneGenerator = it }
            }
            generator.startTone(if (double) ToneGenerator.TONE_PROP_BEEP2 else ToneGenerator.TONE_PROP_BEEP, BEEP_MS)
        } catch (e: Exception) {
            log.w(TAG, "Beep failed", e)
        }
    }

    /**
     * Plays the walk-mode tone until cancelled: short beeps whose pitch and rate rise with
     * [score] (see FinderMath), silence while [audible] is false, the score is null, or speech is
     * playing. Blocking audio writes run on Dispatchers.IO.
     */
    suspend fun runTone(score: () -> Double?, audible: () -> Boolean) = withContext(Dispatchers.IO) {
        val minBuffer = AudioTrack.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val track = try {
            AudioTrack.Builder()
                .setAudioAttributes(attributes)
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(SAMPLE_RATE)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build(),
                )
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setBufferSizeInBytes(maxOf(minBuffer, CHUNK_SAMPLES * 2 * 2))
                .build()
        } catch (e: Exception) {
            log.w(TAG, "Walk tone unavailable", e)
            return@withContext
        }
        ensureAudible()
        val chunk = ShortArray(CHUNK_SAMPLES)
        var phase = 0.0
        try {
            track.play()
            while (currentCoroutineContext().isActive) {
                val s = if (audible() && !speaking) score() else null
                if (s == null) {
                    chunk.fill(0)
                    track.write(chunk, 0, CHUNK_SAMPLES)
                    continue
                }
                val freq = FinderMath.toneFrequencyHz(s)
                val beepSamples = (SAMPLE_RATE * FinderMath.BEEP_LENGTH_MS / 1000).toInt()
                val fade = (SAMPLE_RATE * FADE_MS / 1000).toInt()
                var written = 0
                while (written < beepSamples && currentCoroutineContext().isActive) {
                    val n = minOf(CHUNK_SAMPLES, beepSamples - written)
                    for (i in 0 until n) {
                        val idx = written + i
                        val envelope = when {
                            idx < fade -> idx.toDouble() / fade
                            idx > beepSamples - fade -> (beepSamples - idx).toDouble() / fade
                            else -> 1.0
                        }
                        chunk[i] = (sin(phase) * envelope * AMPLITUDE).toInt().toShort()
                        phase += 2 * PI * freq / SAMPLE_RATE
                        if (phase > 2 * PI) phase -= 2 * PI
                    }
                    track.write(chunk, 0, n)
                    written += n
                }
                // Silence until the next beep; re-read the score so a rising signal speeds up at once.
                var silentMs = FinderMath.BEEP_LENGTH_MS
                chunk.fill(0)
                while (currentCoroutineContext().isActive) {
                    val current = if (audible() && !speaking) score() else null
                    val period = if (current == null) FinderMath.BEEP_PERIOD_MAX_MS else FinderMath.beepPeriodMs(current)
                    if (silentMs >= period) break
                    track.write(chunk, 0, CHUNK_SAMPLES)
                    silentMs += CHUNK_MS
                }
            }
        } catch (e: Exception) {
            log.w(TAG, "Walk tone stopped", e)
        } finally {
            runCatching { track.pause() }
            runCatching { track.flush() }
            runCatching { track.stop() }
            track.release()
        }
    }

    /** The Finder is useless when the alarm stream is at 0, so lift it to half volume. */
    private fun ensureAudible() {
        try {
            val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
            if (am.getStreamVolume(AudioManager.STREAM_ALARM) == 0) {
                am.setStreamVolume(AudioManager.STREAM_ALARM, (am.getStreamMaxVolume(AudioManager.STREAM_ALARM) + 1) / 2, 0)
            }
        } catch (e: Exception) {
            log.d(TAG, "Could not check the alarm volume: ${e.message}")
        }
    }

    /** Stops speech and frees the engines (service stop). */
    fun shutdown() {
        val (engine, generator) = synchronized(lock) {
            val pair = tts to toneGenerator
            tts = null
            toneGenerator = null
            ttsState = TtsState.NONE
            pending.clear()
            pair
        }
        speaking = false
        runCatching { generator?.release() }
        if (engine != null) {
            main.post {
                runCatching { engine.stop() }
                runCatching { engine.shutdown() }
            }
        }
    }

    /** Stops what is being said right now (Finder stopped). */
    fun silence() {
        synchronized(lock) {
            pending.clear()
            runCatching { tts?.stop() }
        }
        speaking = false
    }

    companion object {
        private const val TAG = "finder"
        private const val SAMPLE_RATE = 22_050
        private const val CHUNKS_PER_SECOND = 50
        private const val CHUNK_MS = 1000L / CHUNKS_PER_SECOND
        private const val CHUNK_SAMPLES = SAMPLE_RATE / CHUNKS_PER_SECOND
        private const val FADE_MS = 5L
        private const val AMPLITUDE = 0.6 * 32_767
        private const val BEEP_VOLUME = 90
        private const val BEEP_MS = 180
        private const val MAX_PENDING = 8
    }
}
