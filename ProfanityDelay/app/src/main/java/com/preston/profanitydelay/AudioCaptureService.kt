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

        private const val SAMPLE_RATE = 16000
        private const val DELAY_MS = 5000L
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

    private val running = AtomicBoolean(false)
    private lateinit var profanityWords: Set<String>

    private val flaggedRanges = CopyOnWriteArrayList<Pair<Long, Long>>()

    private data class Chunk(val samples: ShortArray, val captureTimeMs: Long)
    private val bufferQueue = ArrayDeque<Chunk>()
    private val bufferLock = Object()

    private var streamStartMillis = System.currentTimeMillis()

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
            recognizer = Recognizer(model, SAMPLE_RATE.toFloat()).apply { setWords(true) }

            setupAudioCapture()
            setupAudioOutput()
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

            val gotFinal = recognizer?.acceptWaveForm(samplesCopy, read) ?: false
            val json = if (gotFinal) recognizer?.result else recognizer?.partialResult
            json?.let { parseAndFlag(it) }
        }
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
