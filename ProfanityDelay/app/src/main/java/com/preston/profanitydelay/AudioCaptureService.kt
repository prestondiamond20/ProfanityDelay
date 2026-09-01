package com.preston.profanitydelay

import android.app.*
import android.content.Intent
import android.media.*
import android.media.AudioManager
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import java.io.File
import java.io.FileOutputStream
import java.util.ArrayDeque
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.ZipInputStream

class AudioCaptureService : Service() {

    companion object {
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        private const val TAG = "ProfanityDelay"
        private const val CHANNEL_ID = "profanity_delay_channel"
        private const val NOTIF_ID = 1

        private const val SAMPLE_RATE = 44100
        private const val RECOGNIZER_SAMPLE_RATE = 16000
        private const val DELAY_MS = 10000L
        private const val CHUNK_MS = 500L
        private const val CHUNK_SAMPLES = (SAMPLE_RATE * CHUNK_MS / 1000).toInt()

        // Must match the folder name inside vosk-model.zip (the zip's
        // top-level folder), and the version downloaded in build.yml.
        private const val MODEL_DIR_NAME = "vosk-model-small-en-us-0.15"
    }

    private var mediaProjection: MediaProjection? = null
    private var audioRecord: AudioRecord? = null
    private var audioTrack: AudioTrack? = null
    private var recognizer: Recognizer? = null
    private var model: Model? = null
    private var audioManager: AudioManager? = null
    private var originalMusicVolume: Int = -1

    private val running = AtomicBoolean(false)
    private lateinit var profanityWords: Set<String>

    private val flaggedRanges = CopyOnWriteArrayList<Pair<Long, Long>>()

    private data class Chunk(val samples: ShortArray, val captureTimeMs: Long)
    private val bufferQueue = ArrayDeque<Chunk>()
    private val bufferLock = Object()

    private val recognitionQueue = ArrayDeque<ShortArray>()
    private val recognitionLock = Object()

    private var streamStartMillis = System.currentTimeMillis()

    override fun onCreate() {
        super.onCreate()
        profanityWords = loadProfanityList()
        createNotificationChannel()
        audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
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
                    buildNotification("Filtering audio..."),
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK or
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
                )
            } else {
                startForeground(NOTIF_ID, buildNotification("Filtering audio..."))
            }

            mediaProjection = projectionManager.getMediaProjection(resultCode, resultData)
            mediaProjection?.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    running.set(false)
                }
            }, null)

            extractModelIfNeeded()

            model = Model(assetsModelPath())
            recognizer = Recognizer(model, RECOGNIZER_SAMPLE_RATE.toFloat()).apply { setWords(true) }

            setupAudioCapture()
            setupAudioOutput()
            muteSourceAudio()
        } catch (e: Exception) {
            Log.e(TAG, "Startup failed", e)
            android.os.Handler(mainLooper).post {
                android.widget.Toast.makeText(
                    applicationContext,
                    "Filter failed to start: " + e.javaClass.simpleName + ": " + e.message,
                    android.widget.Toast.LENGTH_LONG
                ).show()
            }
            stopSelf()
            return START_NOT_STICKY
        }

        running.set(true)
        Thread(::captureLoop, "capture-thread").start()
        Thread(::recognitionLoop, "recognition-thread").start()
        Thread(::playbackLoop, "playback-thread").start()

        return START_STICKY
    }

    /**
     * Vosk needs the model on the real filesystem, not inside the APK's
     * compressed assets. This unzips assets/vosk-model.zip into internal
     * storage the first time the service runs, then reuses it after that.
     */
    private fun extractModelIfNeeded() {
        val modelDir = File(filesDir, MODEL_DIR_NAME)
        if (modelDir.exists() && modelDir.list()?.isNotEmpty() == true) {
            return
        }

        assets.open("vosk-model.zip").use { input ->
            ZipInputStream(input).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    val outFile = File(filesDir, entry.name)
                    if (entry.isDirectory) {
                        outFile.mkdirs()
                    } else {
                        outFile.parentFile?.mkdirs()
                        FileOutputStream(outFile).use { fos ->
                            zis.copyTo(fos)
                        }
                    }
                    zis.closeEntry()
                    entry = zis.nextEntry
                }
            }
        }
    }

    /**
     * Silences the phone's normal media volume so the original app (e.g.
     * YouTube Music) can't be heard directly. AudioPlaybackCapture still
     * receives full-volume audio regardless of this mute, so our own
     * delayed copy (played on USAGE_ALARM, a separate volume channel)
     * is the only thing you'll actually hear.
     */
    private fun muteSourceAudio() {
        val am = audioManager ?: return
        originalMusicVolume = am.getStreamVolume(AudioManager.STREAM_MUSIC)
        am.setStreamVolume(AudioManager.STREAM_MUSIC, 0, 0)
    }

    private fun restoreSourceAudio() {
        val am = audioManager ?: return
        if (originalMusicVolume >= 0) {
            am.setStreamVolume(AudioManager.STREAM_MUSIC, originalMusicVolume, 0)
        }
    }

    private fun setupAudioCapture() {
        val projection = mediaProjection ?: return
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
        // USAGE_ALARM routes our output to a volume channel independent of
        // STREAM_MUSIC, which we mute below to silence the original source.
        audioTrack = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
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
        audioTrack?.setVolume(0.6f)
    }

    /**
     * Reads live audio and only enqueues it (for playback and for
     * recognition). Deliberately does no heavy processing here, so a slow
     * recognition pass never stalls the live capture and causes glitches.
     */
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
            synchronized(recognitionLock) {
                recognitionQueue.add(samplesCopy)
            }
        }
    }

    /** Runs speech recognition at its own pace, independent of live capture. */
    private fun recognitionLoop() {
        while (running.get()) {
            var samples: ShortArray? = null
            synchronized(recognitionLock) {
                samples = recognitionQueue.poll()
            }
            if (samples == null) {
                Thread.sleep(20)
                continue
            }
            // The model expects RECOGNIZER_SAMPLE_RATE audio specifically;
            // feeding it our full-quality capture rate directly throws.
            try {
                val downsampled = resample(samples!!, SAMPLE_RATE, RECOGNIZER_SAMPLE_RATE)
                val gotFinal = recognizer?.acceptWaveForm(downsampled, downsampled.size) ?: false
                val json = if (gotFinal) recognizer?.result else recognizer?.partialResult
                json?.let { parseAndFlag(it) }
            } catch (e: Exception) {
                Log.e(TAG, "Recognition step failed", e)
            }
        }
    }

    /** Simple linear-interpolation resampler, good enough for feeding the recognizer. */
    private fun resample(input: ShortArray, fromRate: Int, toRate: Int): ShortArray {
        if (fromRate == toRate) return input
        val ratio = fromRate.toDouble() / toRate.toDouble()
        val outLen = (input.size / ratio).toInt()
        val output = ShortArray(outLen)
        for (i in 0 until outLen) {
            val srcIndex = i * ratio
            val idx0 = srcIndex.toInt()
            val idx1 = (idx0 + 1).coerceAtMost(input.size - 1)
            val frac = srcIndex - idx0
            val sample = input[idx0] * (1.0 - frac) + input[idx1] * frac
            output[i] = sample.toInt().toShort()
        }
        return output
    }

    private fun playbackLoop() {
        while (running.get()) {
            var toPlay: Chunk? = null
            synchronized(bufferLock) {
                val head = bufferQueue.peek()
                if (head != null) {
                    val age = (System.currentTimeMillis() - streamStartMillis) - head.captureTimeMs
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

            val muted = flaggedRanges.any { range -> range.first < windowEnd && range.second > windowStart }
            val outSamples = if (muted) ShortArray(chunk.samples.size) else chunk.samples
            audioTrack?.write(outSamples, 0, outSamples.size)

            flaggedRanges.removeAll { range -> range.second < windowStart }
        }
    }

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
                    flaggedRanges.add(Pair(startMs - 150, endMs + 150))
                    Log.i(TAG, "Flagged word at " + startMs + "ms-" + endMs + "ms")
                }
            }
        } catch (e: Exception) {
            // partial/empty JSON, ignore
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
        return File(filesDir, MODEL_DIR_NAME).absolutePath
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
        restoreSourceAudio()
        audioRecord?.stop()
        audioRecord?.release()
        audioTrack?.stop()
        audioTrack?.release()
        recognizer?.close()
        model?.close()
        mediaProjection?.stop()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
