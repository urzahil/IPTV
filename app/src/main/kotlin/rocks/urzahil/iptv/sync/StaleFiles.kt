package rocks.urzahil.iptv.sync

import java.io.File

/** Removes leftovers of syncs that died with the process (downloads and playlist copies in the cache). */
object StaleFiles {
    const val MAX_AGE_MS = 24 * 3_600_000L

    /** Deletes regular files directly in [dir] last modified more than [maxAgeMs] before [nowMs]. Returns how many. */
    fun deleteOlderThan(dir: File, maxAgeMs: Long, nowMs: Long = System.currentTimeMillis()): Int {
        val files = dir.listFiles() ?: return 0
        var n = 0
        for (f in files) {
            if (f.isFile && nowMs - f.lastModified() > maxAgeMs && f.delete()) n++
        }
        return n
    }
}
