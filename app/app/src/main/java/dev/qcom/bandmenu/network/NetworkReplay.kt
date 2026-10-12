package dev.qcom.bandmenu.network

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.BufferedWriter
import java.io.File
import java.io.IOException

/*
 * Replay of the Network page: each polling round is stored as one JSON line of
 * raw inputs (the daemon's hex frames, the DIAG helper's stats), and replay
 * rebuilds the snapshot with SnapshotBuilder, so it shows what the live page
 * would show with today's UI code. Android-free, so it's unit-tested.
 */

/** A read as the daemon reports it: [status] "ok" with the frame as hex in [rx], or a failure. */
fun readOutcome(status: String, rx: String): ReadOutcome {
    if (status != "ok") return ReadOutcome.Failed(status)
    return try {
        ReadOutcome.Ok(QmiFrame.parseHex(rx))
    } catch (e: QmiFormatException) {
        ReadOutcome.Malformed(e.message ?: "malformed")
    }
}

fun readOutcome(o: JSONObject): ReadOutcome = readOutcome(o.optString("status"), o.optString("rx"))

/** One recorded round: everything SnapshotBuilder needs. */
class ReplayRound(
    val sim: Int,
    val elapsedMs: Long,
    val roundMs: Int,
    val operatorName: String?,
    val nrBands: Set<Int>,
    val reads: Map<String, ReadOutcome>,
    val diag: DiagStats?
) {
    fun snapshot(): NetworkSnapshot = SnapshotBuilder(reads, nrBands, diag).build(sim, elapsedMs, roundMs)
}

object ReplayFormat {
    /**
     * {"t":<elapsed ms>,"sim":N,"m":<round ms>,"op":"name","nb":[bands],
     *  "r":{"<read key>":"<frame hex>" | "!<status>"},"d":{helper stats}}
     * "t" and "sim" come first, so a line can be indexed without parsing it.
     */
    fun encode(
        sim: Int, elapsedMs: Long, roundMs: Int, operatorName: String?, nrBands: Set<Int>,
        keys: List<String>, reads: JSONArray, diag: JSONObject?
    ): String {
        val r = JSONObject()
        for (i in keys.indices) {
            val o = reads.getJSONObject(i)
            val status = o.optString("status")
            r.put(keys[i], if (status == "ok") o.optString("rx") else "!$status")
        }
        val rest = JSONObject().put("m", roundMs).put("nb", JSONArray(nrBands.sorted())).put("r", r)
        operatorName?.let { rest.put("op", it) }
        diag?.let { rest.put("d", it) }
        val body = rest.toString()
        return "{\"t\":$elapsedMs,\"sim\":$sim," + body.substring(1)
    }

    /** (elapsed ms, SIM) from a line's start, or null when it isn't a whole line. */
    fun head(line: ByteArray): Pair<Long, Int>? {
        if (line.isEmpty() || line.last() != '}'.code.toByte()) return null
        val m = HEAD.find(String(line, 0, minOf(line.size, 48), Charsets.US_ASCII)) ?: return null
        return m.groupValues[1].toLong() to m.groupValues[2].toInt()
    }

    private val HEAD = Regex("""^\{"t":(\d+),"sim":(\d+),""")

    fun decode(line: String): ReplayRound? = try {
        val o = JSONObject(line)
        val r = o.getJSONObject("r")
        val reads = r.keys().asSequence().associateWith { k ->
            val v = r.getString(k)
            if (v.startsWith("!")) ReadOutcome.Failed(v.substring(1)) else readOutcome("ok", v)
        }
        val nb = o.optJSONArray("nb")
        ReplayRound(
            sim = o.getInt("sim"), elapsedMs = o.getLong("t"), roundMs = o.optInt("m"),
            operatorName = if (o.has("op")) o.getString("op") else null,
            nrBands = nb?.let { a -> (0 until a.length()).map(a::getInt).toSet() } ?: emptySet(),
            reads = reads,
            diag = o.optJSONObject("d")?.let { DiagStats.parse(it) }
        )
    } catch (e: JSONException) {
        null
    }
}

/**
 * Appends rounds to segment files in [dir], one per [SEGMENT_MS] of recording,
 * and keeps the newest [KEEP] (30-35 minutes). [clear] starts over (replay
 * switched on, or on again at the next app start) and deletes the files
 * (replay switched off). IO errors are swallowed: recording never fails a round.
 */
class ReplayRecorder(private val dir: File) {
    private var out: BufferedWriter? = null
    private var seq = -1
    private var segmentStartMs = 0L
    private var recording = true

    /** Closes the current segment and deletes every recorded file; with
     *  [keepRecording] false, appends are dropped until the next clear. */
    @Synchronized
    fun clear(keepRecording: Boolean = true) {
        runCatching { out?.close() }
        out = null
        recording = keepRecording
        dir.listFiles()?.forEach { it.delete() }
    }

    @Synchronized
    fun append(elapsedMs: Long, line: String) {
        if (!recording) return
        try {
            dir.mkdirs()
            if (out == null || elapsedMs - segmentStartMs >= SEGMENT_MS || elapsedMs < segmentStartMs) rotate(elapsedMs)
            val w = out ?: return
            w.write(line)
            w.write("\n")
            w.flush()
        } catch (e: IOException) {
            runCatching { out?.close() }
            out = null
        }
    }

    private fun rotate(elapsedMs: Long) {
        runCatching { out?.close() }
        seq++
        segmentStartMs = elapsedMs
        out = File(dir, "seg-%06d.ndjson".format(seq)).bufferedWriter(Charsets.UTF_8)
        dir.listFiles()?.filter { it.name.startsWith("seg-") }?.sortedBy { it.name }
            ?.dropLast(KEEP)?.forEach { it.delete() }
    }

    /** The recording of [sim] so far, oldest first. Blocking. */
    @Synchronized
    fun open(sim: Int): ReplaySession {
        val lines = ArrayList<ReplaySession.Entry>()
        dir.listFiles()?.filter { it.name.startsWith("seg-") }?.sortedBy { it.name }?.forEach { f ->
            val bytes = try { f.readBytes() } catch (e: IOException) { return@forEach }
            var start = 0
            for (i in bytes.indices) {
                if (bytes[i] != '\n'.code.toByte()) continue
                val line = bytes.copyOfRange(start, i)
                start = i + 1
                val h = ReplayFormat.head(line) ?: continue
                if (h.second == sim) lines += ReplaySession.Entry(h.first, line)
            }
        }
        return ReplaySession(lines)
    }

    companion object {
        const val SEGMENT_MS = 5 * 60_000L
        const val KEEP = 7
    }
}

/** A frozen view of the recording for one SIM: sample 0 is the oldest. */
class ReplaySession(private val entries: List<Entry>) {
    class Entry(val elapsedMs: Long, val line: ByteArray)

    val size get() = entries.size
    fun elapsedAt(i: Int) = entries[i].elapsedMs

    private var cached: Pair<Int, ReplayRound?>? = null

    /** The round at [i], or null when its line can't be read. */
    fun round(i: Int): ReplayRound? {
        cached?.let { if (it.first == i) return it.second }
        val r = ReplayFormat.decode(String(entries[i].line, Charsets.UTF_8))
        cached = i to r
        return r
    }
}
