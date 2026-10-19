package dev.qcom.bandmenu.network

import org.json.JSONObject
import java.io.Closeable
import java.io.OutputStream
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.zip.GZIPOutputStream

/** A storage-independent, bounded writer. Only this thread touches compressed documents. */
class LogArchiveWriter(
    private val header: JSONObject,
    private val openSegment: (String) -> OutputStream,
    private val clockNs: () -> Long,
    private val onProgress: (Progress) -> Unit = {},
    private val onFailure: (String) -> Unit = {},
    private val segmentNs: Long = 30_000_000_000L,
    private val segmentBytes: Long = 8L * 1024 * 1024,
    queueCapacity: Int = 4096,
    private val maxQueueBytes: Long = 16L * 1024 * 1024
) : Closeable {
    data class Progress(val segments: Int = 0, val records: Long = 0, val bytes: Long = 0, val dropped: Long = 0)
    private val queue = ArrayBlockingQueue<ByteArray>(queueCapacity)
    private val queuedBytes = AtomicLong()
    private val dropped = AtomicLong()
    private val pendingDropped = AtomicLong()
    private var droppedStartNs = Long.MAX_VALUE
    private var droppedEndNs = Long.MIN_VALUE
    private val gate = Any()
    @Volatile private var accepting = true
    @Volatile private var reason = "Stopped by operator"
    @Volatile var error: String? = null
        private set
    @Volatile var progress = Progress()
        private set
    private var output: GZIPOutputStream? = null
    private var segment = 0
    private var openedNs = 0L
    private var bytesInSegment = 0L
    private var sequence = 0L
    private var records = 0L
    private var bytes = 0L
    private val sessionId = header.getString("sessionId")
    private val thread = Thread(::writeLoop, "QBM archive writer").apply { isDaemon = true; start() }

    /** Copies the record now; producers may reuse their JSON objects after this call. Never blocks on storage. */
    fun append(record: JSONObject): Boolean {
        val data = record.toString().toByteArray(Charsets.UTF_8)
        synchronized(gate) {
            if (!accepting) return false
            if (data.size.toLong() + queuedBytes.get() > maxQueueBytes || !queue.offer(data)) {
                dropped.incrementAndGet(); pendingDropped.incrementAndGet()
                val time = record.optString("tNs").toLongOrNull() ?: clockNs()
                droppedStartNs = minOf(droppedStartNs, time); droppedEndNs = maxOf(droppedEndNs, time)
                return false
            }
            queuedBytes.addAndGet(data.size.toLong())
            return true
        }
    }

    fun finish(reason: String) { synchronized(gate) { this.reason = reason; accepting = false } }
    fun awaitClosed(timeoutMs: Long = 5000): Boolean { thread.join(timeoutMs); return !thread.isAlive }
    override fun close() { finish("Writer closed"); awaitClosed() }

    private fun writeLoop() {
        try {
            ensureSegment()
            while (accepting || queue.isNotEmpty()) {
                val record = queue.poll(100, TimeUnit.MILLISECONDS)
                if (record != null) {
                    queuedBytes.addAndGet(-record.size.toLong())
                    writeGap()
                    writeRecord(JSONObject(String(record, Charsets.UTF_8)))
                } else if (output != null && clockNs() - openedNs >= segmentNs) closeSegment("Time rotation")
            }
            writeGap()
            ensureSegment()
            closeSegment(reason)
            publishProgress()
        } catch (e: Exception) {
            error = e.message ?: e.javaClass.simpleName
            synchronized(gate) { accepting = false }
            runCatching { output?.close() }
            output = null
            onFailure(error!!)
        }
    }

    private fun writeGap() {
        val gap = synchronized(gate) {
            val result = Triple(pendingDropped.getAndSet(0), droppedStartNs, droppedEndNs)
            droppedStartNs = Long.MAX_VALUE; droppedEndNs = Long.MIN_VALUE
            result
        }
        if (gap.first > 0) writeRecord(JSONObject().put("type", "gap").put("tNs", gap.second.toString()).put("endNs", gap.third.toString())
            .put("source", "all").put("dropped", gap.first).put("reason", "Bounded archive queue overflow"))
    }

    private fun ensureSegment() {
        if (output != null) return
        segment++
        openedNs = clockNs()
        bytesInSegment = 0
        output = GZIPOutputStream(openSegment("qbm-$sessionId-${segment.toString().padStart(5, '0')}.jsonl.gz"), 64 * 1024, true)
        val h = JSONObject(header.toString()).put("segment", segment).put("archiveSeq", ++sequence)
        rawWrite(h.toString())
        output!!.flush()
        publishProgress()
    }

    private fun writeRecord(record: JSONObject) {
        val length = record.toString().toByteArray(Charsets.UTF_8).size + 200L
        if (output != null && (clockNs() - openedNs >= segmentNs || bytesInSegment + length > segmentBytes)) closeSegment("Segment rotation")
        ensureSegment()
        record.put("sessionId", sessionId).put("segment", segment).put("archiveSeq", ++sequence)
        rawWrite(record.toString())
        records++
        if (records % 128L == 0L) { output!!.flush(); publishProgress() }
    }

    private fun rawWrite(line: String) {
        val data = (line + "\n").toByteArray(Charsets.UTF_8)
        output!!.write(data)
        bytesInSegment += data.size; bytes += data.size
    }
    private fun closeSegment(reason: String) {
        if (output == null) return
        rawWrite(JSONObject().put("type", "footer").put("sessionId", sessionId).put("segment", segment)
            .put("archiveSeq", ++sequence).put("tNs", clockNs().toString()).put("reason", reason)
            .put("dropped", dropped.get()).put("records", records).toString())
        output!!.close(); output = null; publishProgress()
    }
    private fun publishProgress() {
        progress = Progress(segment, records, bytes, dropped.get())
        onProgress(progress)
    }
}
