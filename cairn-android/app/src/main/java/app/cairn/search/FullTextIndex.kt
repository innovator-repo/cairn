package app.cairn.search

import app.cairn.data.Kind
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.util.TreeMap
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min

/**
 * A searchable document. [entryId] is the map node it belongs to (a file with no node of its own
 * points at its repo). [text] is the full body, kept so results can show a highlighted snippet.
 */
class Doc(
    val id: String,
    val entryId: String,
    val kind: Kind,
    val title: String,
    val path: String,
    val text: String,
    val repo: String? = null,
)

/** A result: the document, its score, and a snippet with the matching ranges to highlight. */
class TextHit(val doc: Doc, val score: Float, val snippet: String, val marks: List<IntRange>)

/**
 * An inverted index kept in memory and saved to one gzip file, so search is instant and offline.
 * Scoring is BM25 with title and path boosts; the last typed word also matches by prefix, and
 * camelCase identifiers are indexed both whole and in pieces so code is findable.
 */
class FullTextIndex {
    private val docs = ArrayList<Doc>()
    private val lens = ArrayList<Int>()
    private var totalLen = 0L
    private val terms = TreeMap<String, Postings>()

    private class Postings {
        var ids = IntArray(4); var tfs = IntArray(4); var n = 0
        fun add(doc: Int, tf: Int) {
            if (n == ids.size) { ids = ids.copyOf(n * 2); tfs = tfs.copyOf(n * 2) }
            ids[n] = doc; tfs[n] = tf; n++
        }
    }

    val size: Int get() = docs.size
    val documents: List<Doc> get() = docs

    fun addAll(list: List<Doc>) { for (d in list) add(d) }

    fun add(doc: Doc) {
        val idx = docs.size
        docs += doc
        val tf = HashMap<String, Int>()
        var len = 0
        fun feed(s: String, weight: Int) {
            for (t in indexTerms(s)) { tf[t] = (tf[t] ?: 0) + weight; len++ }
        }
        feed(doc.title, 3); feed(doc.path, 2); feed(doc.text, 1)
        lens += len
        totalLen += len
        for ((t, c) in tf) terms.getOrPut(t) { Postings() }.add(idx, c)
    }

    fun search(query: String, limit: Int = 50): List<TextHit> {
        val qs = tokenize(query).filter { it.isNotEmpty() }.distinct()
        if (qs.isEmpty() || docs.isEmpty()) return emptyList()
        val n = docs.size
        val avg = max(1f, totalLen.toFloat() / n)
        val perTerm = qs.mapIndexed { qi, q ->
            val scores = HashMap<Int, Float>()
            val last = qi == qs.size - 1
            val candidates = ArrayList<Pair<String, Float>>()
            if (terms.containsKey(q)) candidates += q to 1f
            if (q.length >= 2 || (last && q.length >= 1 && !q[0].isDigit() && q[0].code > 127)) {
                var taken = 0
                for (t in terms.subMap(q, q + '￿').keys) {
                    if (t == q) continue
                    candidates += t to 0.6f
                    if (++taken >= 60) break
                }
            }
            for ((t, w) in candidates) {
                val p = terms[t] ?: continue
                val idf = ln(1f + (n - p.n + 0.5f) / (p.n + 0.5f))
                for (i in 0 until p.n) {
                    val tf = p.tfs[i].toFloat()
                    val norm = tf * 2.2f / (tf + 1.2f * (0.25f + 0.75f * lens[p.ids[i]] / avg))
                    val s = idf * norm * w
                    val cur = scores[p.ids[i]]
                    if (cur == null || s > cur) scores[p.ids[i]] = s
                }
            }
            scores
        }
        var matched: Map<Int, Float> = perTerm.reduce { a, b ->
            val out = HashMap<Int, Float>()
            for ((d, s) in a) b[d]?.let { out[d] = s + it }
            out
        }
        if (matched.isEmpty()) {
            val out = HashMap<Int, Float>()
            for (m in perTerm) for ((d, s) in m) out[d] = (out[d] ?: 0f) + s * 0.5f
            matched = out
        }
        val ql = query.trim().lowercase()
        return matched.entries.map { (d, s) ->
            val doc = docs[d]
            var score = s
            if (doc.title.lowercase().contains(ql)) score += 4f
            if (doc.path.lowercase().contains(ql)) score += 2f
            doc to score
        }.sortedByDescending { it.second }.take(limit).map { (doc, score) ->
            val (snip, marks) = snippet(doc, qs)
            TextHit(doc, score, snip, marks)
        }
    }

    fun save(file: File) {
        val tmp = File(file.parentFile, file.name + ".tmp")
        DataOutputStream(GZIPOutputStream(BufferedOutputStream(tmp.outputStream()))).use { o ->
            o.writeInt(MAGIC); o.writeInt(docs.size)
            for (d in docs) {
                o.str(d.id); o.str(d.entryId); o.str(d.kind.name); o.str(d.title); o.str(d.path); o.str(d.text); o.str(d.repo ?: "")
            }
        }
        if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
    }

    companion object {
        private const val MAGIC = 0x43465431 // "CFT1"
        const val MAX_TERM = 64

        /** An empty index when the file is missing or damaged. */
        fun load(file: File): FullTextIndex {
            val idx = FullTextIndex()
            if (!file.isFile) return idx
            try {
                DataInputStream(GZIPInputStream(BufferedInputStream(file.inputStream()))).use { i ->
                    if (i.readInt() != MAGIC) return idx
                    val n = i.readInt()
                    repeat(n) {
                        val id = i.str(); val entry = i.str(); val kind = Kind.parse(i.str())
                        val title = i.str(); val path = i.str(); val text = i.str(); val repo = i.str()
                        idx.add(Doc(id, entry, kind, title, path, text, repo.ifEmpty { null }))
                    }
                }
            } catch (_: Exception) {
                return FullTextIndex()
            }
            return idx
        }

        private fun DataOutputStream.str(s: String) { val b = s.toByteArray(Charsets.UTF_8); writeInt(b.size); write(b) }
        private fun DataInputStream.str(): String {
            val n = readInt()
            require(n in 0..(64 * 1024 * 1024))
            return String(ByteArray(n).also { readFully(it) }, Charsets.UTF_8)
        }

        /** Index terms: lowercase words, plus the pieces of camelCase words. */
        fun indexTerms(s: String): List<String> {
            val out = ArrayList<String>()
            var start = -1
            fun flush(end: Int) {
                if (start < 0) return
                val raw = s.substring(start, end)
                start = -1
                if (raw.length > MAX_TERM) return
                val lower = raw.lowercase()
                if (lower.length >= 2 || lower[0].code > 127) out += lower
                var p = 0
                for (i in 1 until raw.length) {
                    if (raw[i].isUpperCase() && raw[i - 1].isLowerCase()) {
                        val piece = raw.substring(p, i).lowercase()
                        if (piece.length >= 2 && piece != lower) out += piece
                        p = i
                    }
                }
                if (p > 0) { val piece = raw.substring(p).lowercase(); if (piece.length >= 2 && piece != lower) out += piece }
            }
            for ((i, ch) in s.withIndex()) {
                if (ch.isLetterOrDigit()) { if (start < 0) start = i } else flush(i)
            }
            flush(s.length)
            return out
        }

        /** A window of the text around the first match, with the matching words marked. */
        fun snippet(doc: Doc, queryWords: List<String>): Pair<String, List<IntRange>> {
            val text = doc.text
            var best = -1
            for (q in queryWords) {
                val i = text.indexOf(q, ignoreCase = true)
                if (i >= 0 && (best < 0 || i < best)) best = i
            }
            val body: String
            val prefix: String
            val suffix: String
            if (best >= 0) {
                val start = max(0, best - 50)
                val end = min(text.length, best + 110)
                body = text.substring(start, end).map { if (it == '\n' || it == '\r' || it == '\t') ' ' else it }.joinToString("")
                prefix = if (start > 0) "…" else ""
                suffix = if (end < text.length) "…" else ""
            } else {
                body = (text.ifBlank { doc.path }).take(120).map { if (it == '\n' || it == '\r' || it == '\t') ' ' else it }.joinToString("")
                prefix = ""; suffix = if (text.length > 120) "…" else ""
            }
            val snip = prefix + body + suffix
            val marks = ArrayList<IntRange>()
            for (q in queryWords) {
                var from = 0
                while (true) {
                    val i = snip.indexOf(q, from, ignoreCase = true)
                    if (i < 0) break
                    var e = i + q.length
                    while (e < snip.length && snip[e].isLetterOrDigit()) e++
                    marks += i until e
                    from = e
                }
            }
            return snip to merge(marks)
        }

        private fun merge(r: List<IntRange>): List<IntRange> {
            if (r.isEmpty()) return r
            val sorted = r.sortedBy { it.first }
            val out = ArrayList<IntRange>()
            var cur = sorted[0]
            for (x in sorted.drop(1)) {
                if (x.first <= cur.last + 1) cur = cur.first..max(cur.last, x.last) else { out += cur; cur = x }
            }
            out += cur
            return out
        }
    }
}
