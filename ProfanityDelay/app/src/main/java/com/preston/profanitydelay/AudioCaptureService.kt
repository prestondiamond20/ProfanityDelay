package com.preston.profanitydelay

import android.app.*
import android.content.Intent
import android.media.*
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import java.util.ArrayDeque
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Broadcast-delay profanity filter.
 *
 * Captures whatever is playing (e.g. YouTube Music) via AudioPlaybackCapture,
 * holds it in a ~5s buffer, runs an offline speech recognizer on it, and only
 * writes audio to the speaker once it has cleared the profanity check.
 *
 * CAVEATS (read before you burn hours debugging):
 *  - Only works on Android 10+ (API 29+).
 *  - Only captures apps that allow it. If YouTube Music sets
 *    android:allowAudioPlaybackCapture="false", this will get silence and
 *    there's no workaround short of root.
 *  - Detection quality depends entirely on the Vosk model + how clean the
 *    music mix is. Fast rap / heavy reverb / music-over-vocals will cause
 *    misses. This is a prototype, not a certified filter.
 *  - Whole ~500ms window is muted when a flagged word is detected in it,
 *    not just the word itself — simpler and safer than sample-precise
 *    excision, but you'll hear a blip of silence.
 */
class AudioCaptureService : Service() {

    companion object {
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        private const val TAG = "ProfanityDelay"
        private const val CHANNEL_ID = "profanity_delay_channel"
        private const val NOTIF_ID = 1

        private const val SAMPLE_RATE = 16000
        private const val DELAY_MS = 5000L          // the "broadcast delay" window
        private const val CHUNK_MS = 500L            // granularity of mute decisions
        private const val CHUNK_SAMPLES = (SAMPLE_RATE * CHUNK_MS / 1000).toInt()
    }

    private var mediaProjection: MediaProjection? = null
    private var audioRecord: AudioRecord? = null
    private var audioTrack: AudioTrack? = null
    private var recognizer: Recognizer? = null
    private var model: Model? = null

    private val running = AtomicBoolean(false)
    private lateinit var profanityWords: Set<String>

    // (startMs, endMs) windows, relative to stream start, that must be muted
    private val flaggedRanges = CopyOnWriteArrayList<Pair<Long, Long>>()

    // buffered chunks waiting for their delay to elapse
    private data class Chunk(val samples: ShortArray, val captureTimeMs: Long)
    private val bufferQueue = ArrayDeque<Chunk>()
    private val bufferLock = Object()

    override fun onCreate() {
        super.onCreate()
        profanityWords = loadProfanityList()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED) ?: return START_NOT_STICKY
        val resultData: Intent? = intent.getParcelableExtra(EXTRA_RESULT_DATA)
        if (resultData == null) {
            stopSelf()
            return START_NOT_STICKY
        }

              val projectionManager =
            getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(
                    NOTIF_ID,
                    buildNotification("Filtering audio…"),
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK or
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
                )
            } else {
                startForeground(NOTIF_ID, buildNotification("Filtering audio…"))
            }

            mediaProjection = projectionManager.getMediaProjection(resultCode, resultData)

        try {
            model = Model(assetsModelPath())
            recognizer = Recognizer(model, SAMPLE_RATE.toFloat()).apply { setWords(true) }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load Vosk model — did you put it in assets/model?", e)
            stopSelf()
            return START_NOT_STICKY
        }

        setupAudioCapture()
        setupAudioOutput()

        running.set(true)
        Thread(::captureLoop, "capture-thread").start()
        Thread(::playbackLoop, "playback-thread").start()

        return START_STICKY
    }

    private fun setupAudioCapture() {
        val projection = mediaProjection ?: return
        // Widened to catch as much of the phone's audio as the OS permits.
        // Still excludes: calls/VOIP (blocked by Android, no workaround),
        // DRM-protected streams, and any app that opts out of capture.
        val captureConfig = AudioPlaybackCaptureConfiguration.Builder(projection)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .addMatchingUsage(AudioAttributes.USAGE_ASSISTANT)
            .addMatchingUsage(AudioAttributes.USAGE_NOTIFICATION)
            .build()

        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(SAMPLE_RATE)
            .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
            .build()

        val minBufSize = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )

        audioRecord = AudioRecord.Builder()
            .setAudioFormat(format)
            .setBufferSizeInBytes(minBufSize * 4)
            .setAudioPlaybackCaptureConfig(captureConfig)
            .build()

        audioRecord?.startRecording()
    }

    private fun setupAudioOutput() {
        val minBufSize = AudioTrack.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        audioTrack = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(minBufSize * 4)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        audioTrack?.play()
    }

    /** Reads captured PCM, feeds STT, buffers samples for delayed playback. */
    private fun captureLoop() {
        streamStartMillis = System.currentTimeMillis()
        val chunkBuf = ShortArray(CHUNK_SAMPLES)

        while (running.get()) {
            val read = audioRecord?.read(chunkBuf, 0, chunkBuf.size) ?: -1
            if (read <= 0) continue

            val nowMs = System.currentTimeMillis() - streamStartMillis
            val samplesCopy = chunkBuf.copyOf(read)

            synchronized(bufferLock) {
                bufferQueue.add(Chunk(samplesCopy, nowMs))
            }

            // Feed the recognizer; when it finalizes a phrase, check the words
            val gotFinal = recognizer?.acceptWaveForm(samplesCopy, read) ?: false
            val json = if (gotFinal) recognizer?.result else recognizer?.partialResult
            json?.let { parseAndFlag(it) }
        }
    }

    /** Waits out the delay, then plays or mutes each buffered chunk. */
    private fun playbackLoop() {
        while (running.get()) {
            var toPlay: Chunk? = null
            synchronized(bufferLock) {
                val head = bufferQueue.peek()
                if (head != null) {
                    val age = (System.currentTimeMillis() - streamStartRef()) - head.captureTimeMs
                    if (age >= DELAY_MS) {
                        toPlay = bufferQueue.poll()
                    }
                }
            }
            if (toPlay == null) {
                Thread.sleep(20)
                continue
            }
            val chunk = toPlay!!
            val windowStart = chunk.captureTimeMs
            val windowEnd = chunk.captureTimeMs + CHUNK_MS

            val muted = flaggedRanges.any { (s, e) -> s < windowEnd && e > windowStart }
            val outSamples = if (muted) ShortArray(chunk.samples.size) else chunk.samples
            audioTrack?.write(outSamples, 0, outSamples.size)

            // Trim old flagged ranges we no longer need
            flaggedRanges.removeAll { (_, e) -> e < windowStart }
        }
    }

    // capture loop's streamStart isn't visible here directly; re-derive via a shared field instead
    private var streamStartMillis = System.currentTimeMillis()
    private fun streamStartRef() = streamStartMillis

    private fun parseAndFlag(json: String) {
        try {
            val obj = JSONObject(json)
            val resultArray = obj.optJSONArray("result") ?: return
            for (i in 0 until resultArray.length()) {
                val w = resultArray.getJSONObject(i)
                val word = w.optString("word").lowercase()
                if (word in profanityWords) {
                    val startMs = (w.optDouble("start") * 1000).toLong()
                    val endMs = (w.optDouble("end") * 1000).toLong()
                    // pad 150ms on each side to be safe
                    flaggedRanges.add(Pair(startMs - 150, endMs + 150))
                    Log.i(TAG, "Flagged word at ${startMs}ms-${endMs}ms")
                }
            }
        } catch (e: Exception) {
            // partial/empty JSON — ignore
        }
    }

    private fun loadProfanityList(): Set<String> {
        return try {
            assets.open("profanity_list.txt").bufferedReader().readLines()
                .map { it.trim().lowercase() }
                .filter { it.isNotEmpty() }
                .toSet()
        } catch (e: Exception) {
            emptySet()
        }
    }

    private fun assetsModelPath(): String {
        // Vosk needs a real filesystem path, not an APK asset path, so copy it
        // out to internal storage once. See README for the one-time setup.
        return "${filesDir.absolutePath}/vosk-model"
    }

    private fun buildNotification(text: String): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Profanity Delay")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setOngoing(true)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "Profanity Delay", NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        running.set(false)
        audioRecord?.stop(); audioRecord?.release()
        audioTrack?.stop(); audioTrack?.release()
        recognizer?.close()
        model?.close()
        mediaProjection?.stop()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
