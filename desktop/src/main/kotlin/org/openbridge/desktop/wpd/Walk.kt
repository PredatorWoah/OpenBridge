package org.openbridge.desktop.wpd

import org.openbridge.desktop.Entry
import org.openbridge.desktop.FolderSize
import org.openbridge.desktop.Found
import org.openbridge.desktop.SearchResult
import java.util.Locale

/**
 * Folder sizes and search over the cable, a folder at a time through `children`, with the phone's limits (see
 * Store.java) and the Mac's guard: every object is visited once, since Android's MTP can list a folder inside itself.
 */
internal object Walk {
    const val ITEMS = 50_000
    const val MEASURE_MILLIS = 5_000L
    const val SEARCH_MILLIS = 8_000L
    const val HITS = 500

    fun measure(folderId: String, children: (String) -> List<Entry>, millis: Long = MEASURE_MILLIS,
                now: () -> Long = System::currentTimeMillis): FolderSize {
        val deadline = now() + millis
        var bytes = 0L; var files = 0L; var seen = 0
        val visited = hashSetOf(folderId)
        val pending = ArrayDeque(listOf(folderId))
        while (pending.isNotEmpty()) {
            if (now() > deadline) return FolderSize(bytes, files, false)
            for (entry in children(pending.removeLast())) {
                if (++seen > ITEMS || now() > deadline) return FolderSize(bytes, files, false)
                if (!visited.add(entry.id)) continue
                if (entry.directory) pending.addLast(entry.id) else { bytes += entry.size; files++ }
            }
        }
        return FolderSize(bytes, files, true)
    }

    /**
     * Names containing `query` (ignoring case) below each start, breadth first. A start is a folder ID and the
     * location of what's directly in it: "" for the searched folder, a storage's name when searching every storage.
     */
    fun search(starts: List<Pair<String, String>>, query: String, children: (String) -> List<Entry>,
               millis: Long = SEARCH_MILLIS, now: () -> Long = System::currentTimeMillis): SearchResult {
        val needle = query.trim().lowercase(Locale.ROOT)
        val deadline = now() + millis
        val found = ArrayList<Found>()
        var seen = 0
        val visited = starts.mapTo(HashSet()) { it.first }
        val pending = ArrayDeque(starts)
        while (pending.isNotEmpty()) {
            val (folder, location) = pending.removeFirst()
            if (now() > deadline) return SearchResult(found, false)
            for (entry in children(folder)) {
                if (++seen > ITEMS || now() > deadline || found.size >= HITS) return SearchResult(found, false)
                if (!visited.add(entry.id)) continue
                if (entry.name.lowercase(Locale.ROOT).contains(needle)) found += Found(entry, location)
                if (entry.directory) pending.addLast(entry.id to join(location, entry.name))
            }
        }
        return SearchResult(found, true)
    }

    fun join(location: String, name: String) = if (location.isEmpty()) name else "$location/$name"
}
