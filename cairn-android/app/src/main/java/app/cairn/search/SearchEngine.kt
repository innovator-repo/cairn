package app.cairn.search

import app.cairn.data.Entry

data class Hit(val entry: Entry, val score: Float)

/** Offline search over titles, paths and notes (and GitHub READMEs and issues once indexed). */
class SearchEngine(private val entries: List<Entry>, private val embedder: Embedder) {
    private val vectors: List<FloatArray>

    init {
        embedder.prepare(entries.map { it.searchable })
        vectors = entries.map { embedder.embed(it.searchable) }
    }

    fun search(query: String, limit: Int = 50): List<Hit> {
        val q = query.trim()
        if (q.isEmpty()) return emptyList()
        val qv = embedder.embed(q)
        val words = tokenize(q)
        val hits = ArrayList<Hit>()
        for ((i, e) in entries.withIndex()) {
            var s = dot(qv, vectors[i])
            val title = e.title.lowercase()
            val path = e.path.lowercase()
            val text = e.text.lowercase()
            val ql = q.lowercase()
            if (title.contains(ql)) s += 1.0f
            if (path.contains(ql)) s += 0.5f
            if (text.contains(ql)) s += 0.3f
            for (w in words) {
                if (w.length < 2) continue
                if (title.contains(w)) s += 0.25f
                if (path.contains(w)) s += 0.1f
            }
            if (s > MIN_SCORE) hits += Hit(e, s)
        }
        return hits.sortedByDescending { it.score }.take(limit)
    }

    companion object {
        const val MIN_SCORE = 0.12f
    }
}
