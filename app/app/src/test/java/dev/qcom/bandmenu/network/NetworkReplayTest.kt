package dev.qcom.bandmenu.network

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.io.path.createTempDirectory

class NetworkReplayTest {
    private val keys = listOf(NetworkReads.SYS, NetworkReads.SIG, NetworkReads.LOC)

    private fun reads(vararg e: Pair<String, String>) = JSONArray().also { a ->
        e.forEach { (status, rx) -> a.put(JSONObject().put("status", status).put("rx", rx)) }
    }

    private fun line(t: Long, sim: Int = 1) = ReplayFormat.encode(
        sim, t, 120, "Operator", setOf(78, 28), keys,
        reads("timeout" to "", "ok" to "0201", "ok" to "ZZ"), null
    )

    @Test
    fun roundTripBuildsTheSameSnapshot() {
        val round = ReplayFormat.decode(line(5000, 2))!!
        assertEquals(2, round.sim)
        assertEquals(5000L, round.elapsedMs)
        assertEquals("Operator", round.operatorName)
        assertEquals(setOf(28, 78), round.nrBands)
        assertEquals(ReadOutcome.Failed("timeout"), round.reads[NetworkReads.SYS])
        assertTrue(round.reads[NetworkReads.SIG] is ReadOutcome.Malformed)
        val direct = SnapshotBuilder(round.reads, setOf(28, 78), null).build(2, 5000, 120)
        assertEquals(direct, round.snapshot())
    }

    @Test
    fun headIndexesWholeLinesOnly() {
        val l = line(1234, 2)
        assertEquals(1234L to 2, ReplayFormat.head(l.toByteArray()))
        assertNull(ReplayFormat.head(l.dropLast(5).toByteArray()))
        assertNull(ReplayFormat.decode(l.dropLast(5)))
    }

    @Test
    fun keepsUpToAnHourAndSkipsATruncatedLine() {
        val dir = createTempDirectory("replay").toFile()
        try {
            val rec = ReplayRecorder(dir)
            val total = 80 * 60
            for (s in 0..total) rec.append(s * 1000L, line(s * 1000L))
            val files = dir.listFiles()!!.size
            assertTrue("files=$files", files == ReplayRecorder.KEEP)
            val session = rec.open(1)
            val covered = (session.elapsedAt(session.size - 1) - session.elapsedAt(0)) / 1000
            assertTrue("covered=${covered}s", covered in 55 * 60..60 * 60)
            assertNotNull(session.round(session.size - 1))
            // Extending the timeline keeps a sample by its time.
            assertEquals(session.size - 1, session.indexAt(session.elapsedAt(session.size - 1)))
            assertEquals(5, session.indexAt(session.elapsedAt(5) + 500))
            assertEquals(0, session.indexAt(0))
            assertEquals(0, rec.open(2).size)

            File(dir, dir.list()!!.sorted().last()).appendText(line(total * 1000L + 1000).dropLast(9))
            assertEquals(session.size, rec.open(1).size)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun clearStartsOver() {
        val dir = createTempDirectory("replay").toFile()
        try {
            val rec = ReplayRecorder(dir)
            rec.append(0, line(0))
            rec.clear()
            assertEquals(0, dir.listFiles()!!.size)
            rec.append(0, line(0))
            rec.append(1000, line(1000))
            assertEquals(2, rec.open(1).size)
            assertEquals(1, dir.listFiles()!!.size)

            // Replay off: files deleted, nothing written until it's on again.
            rec.clear(keepRecording = false)
            rec.append(2000, line(2000))
            assertEquals(0, dir.listFiles()!!.size)
        } finally {
            dir.deleteRecursively()
        }
    }
}
