package io.embodit.sunmaojoints.platform

import android.app.Application
import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import android.os.SystemClock
import android.util.Log
import com.pico.spatial.ui.foundation.dsl.launch
import com.pico.spatial.ui.platform.stub.SpatialLaunchActivity
import io.embodit.sunmaojoints.data.FileProgressStore
import io.embodit.sunmaojoints.domain.Flow
import io.embodit.sunmaojoints.domain.Sound
import io.embodit.sunmaojoints.mainApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.random.Random

const val TAG = "SunmaoJoints"

class LaunchActivity : SpatialLaunchActivity()

class SpatialApplication : Application() {
    lateinit var graph: Graph
        private set

    override fun onCreate() {
        super.onCreate()
        // board-input template: mirror input lines into files/input-log.txt (headset logcat drops lines)
        io.embodit.sunmaojoints.ui.board.input.BoardInputLog.attach(this)
        graph = Graph(this)
        launch(::mainApp)
    }
}

/** The process-wide objects (native-window-move.md §3.4: state shared by the volume and the window lives here). */
class Graph(context: Context) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val sounds = SoundPlayer(context)
    val store = FileProgressStore(context)
    val flow = Flow(
        scope = scope,
        clock = { SystemClock.uptimeMillis() },
        store = store,
        onSound = { sounds.play(it) },
        log = { Log.i(TAG, it) },
    )
    val window = WindowCtl()

    init {
        flow.start()
        scope.launch { flow.state.collect { sounds.enabled = it.progress.soundOn } }
    }

    companion object {
        fun from(context: Context): Graph = (context.applicationContext as SpatialApplication).graph
    }
}

/**
 * Short effects from assets/sounds/<name>.wav (+ _2 / _3 variants) through a SoundPool. Wood sounds (CC0 recordings)
 * rotate their variants with a random rate of 0.95-1.05 that never repeats back to back (design sound.rule, D51); the
 * pitched lock / all-done notes play as they are.
 */
class SoundPlayer(context: Context) {
    @Volatile var enabled = true

    private val pool = SoundPool.Builder().setMaxStreams(MAX_STREAMS)
        .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
        .build()
    private val ids = HashMap<Sound, List<Int>>()
    /** Sample id -> (duration ms, peak linear) read from the WAV, for the mix headroom below. */
    private val meta = HashMap<Int, Pair<Long, Float>>()
    private val loaded = HashSet<Int>()
    private val random = Random(11)
    private val lastRate = HashMap<Sound, Float>()
    private val lastVariant = HashMap<Sound, Int>()
    private val varied = setOf(Sound.SLIDE, Sound.SEAT, Sound.BLOCK, Sound.TRAY, Sound.TURN)
    /** The stream each event is playing right now (one instance per event, profile D51). */
    private val playing = HashMap<Sound, Int>()
    /** Streams still sounding: stream id -> (end time, peak contribution). */
    private val active = HashMap<Int, Pair<Long, Float>>()

    init {
        pool.setOnLoadCompleteListener { _, id, status -> if (status == 0) synchronized(loaded) { loaded += id } }
        for (s in Sound.entries) {
            val base = s.name.lowercase()
            val found = ArrayList<Int>()
            for (name in listOf("$base.wav", "${base}_2.wav", "${base}_3.wav")) {
                runCatching {
                    val id = context.assets.openFd("sounds/$name").use { fd -> pool.load(fd, 1) }
                    found += id
                    meta[id] = context.assets.open("sounds/$name").use { readMeta(it.readBytes()) }
                }
            }
            if (found.isNotEmpty()) ids[s] = found else Log.w(TAG, "no sound for $s")
        }
    }

    /**
     * Plays [s] once. The same event never overlaps itself (its previous stream stops first) and the summed peaks of
     * everything sounding stay under -1 dBFS (D51; SoundPool has no limiter of its own).
     */
    fun play(s: Sound) {
        if (!enabled) return
        val variants = ids[s] ?: return
        var rate = 1f
        var v = 0
        if (s in varied) {
            rate = 0.95f + random.nextFloat() * 0.10f
            val last = lastRate[s] ?: 1f
            if (abs(rate - last) < 0.01f) rate = if (rate < 1f) rate + 0.02f else rate - 0.02f
            lastRate[s] = rate
            v = random.nextInt(variants.size)
            if (v == lastVariant[s] && variants.size > 1) v = (v + 1) % variants.size
            lastVariant[s] = v
        }
        val id = variants[v]
        if (synchronized(loaded) { id !in loaded }) return
        val now = SystemClock.uptimeMillis()
        synchronized(active) {
            playing.remove(s)?.let { pool.stop(it); active.remove(it) }
            active.entries.removeAll { it.value.first <= now }
            val (durMs, peak) = meta[id] ?: (500L to 0.9f)
            val others = active.values.sumOf { it.second.toDouble() }.toFloat()
            val gain = MixHeadroom.gain(peak, others)
            val stream = pool.play(id, gain, gain, 1, 0, rate)
            if (stream != 0) {
                playing[s] = stream
                active[stream] = (now + (durMs / rate).toLong()) to gain * peak
            }
        }
    }

    fun release() = runCatching { pool.release() }

    companion object {
        const val MAX_STREAMS = 6

        /** Duration (ms) and peak (linear) of a 16-bit PCM WAV. */
        fun readMeta(b: ByteArray): Pair<Long, Float> {
            var i = 12
            var rate = 22050; var ch = 1
            while (i + 8 <= b.size) {
                val id = String(b, i, 4)
                val n = (b[i + 4].toInt() and 255) or ((b[i + 5].toInt() and 255) shl 8) or ((b[i + 6].toInt() and 255) shl 16) or ((b[i + 7].toInt() and 255) shl 24)
                if (id == "fmt ") {
                    ch = (b[i + 10].toInt() and 255) or ((b[i + 11].toInt() and 255) shl 8)
                    rate = (b[i + 12].toInt() and 255) or ((b[i + 13].toInt() and 255) shl 8) or ((b[i + 14].toInt() and 255) shl 16)
                }
                if (id == "data") {
                    var peak = 0
                    var j = i + 8
                    val end = minOf(b.size - 1, i + 8 + n)
                    while (j < end) { val v = abs(((b[j + 1].toInt() shl 8) or (b[j].toInt() and 255)).toShort().toInt()); if (v > peak) peak = v; j += 2 }
                    return (n / 2L / ch * 1000 / rate) to peak / 32768f
                }
                i += 8 + n + (n and 1)
            }
            return 500L to 0.9f
        }
    }
}

/** The mix limiter rule, kept pure for the unit test: the summed peaks of everything sounding stay <= -1 dBFS. */
object MixHeadroom {
    const val CEILING = 0.891f // -1 dBFS

    /** Gain (0..1) for a new stream whose peak at full gain is [newPeak], with [othersPeak] already sounding. */
    fun gain(newPeak: Float, othersPeak: Float): Float {
        if (newPeak <= 0f) return 1f
        val room = CEILING - othersPeak
        return (room / newPeak).coerceIn(0.15f, 1f)
    }
}
