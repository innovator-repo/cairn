package app.cairn.search

import kotlin.math.ln
import kotlin.math.sqrt

/**
 * Turns text into a fixed-length, L2-normalised vector so similarity is a dot product.
 *
 * v0.1 uses [TfidfEmbedder]. A later version can add an ONNX MiniLM implementation (384 dims)
 * behind this same interface: nothing else in the app needs to change, because search, links and
 * layouts only ever call [prepare] once per index build and [embed] per text.
 */
interface Embedder {
    val name: String

    /** Called with the whole corpus before embedding, so models can learn corpus statistics. */
    fun prepare(corpus: List<String>)

    /** A unit-length vector, or all zeros for text with no usable words. */
    fun embed(text: String): FloatArray
}

fun dot(a: FloatArray, b: FloatArray): Float {
    var s = 0f
    val n = minOf(a.size, b.size)
    for (i in 0 until n) s += a[i] * b[i]
    return s
}

/** Lowercase words and numbers; works for any script with letters, so Japanese runs stay whole. */
fun tokenize(text: String): List<String> {
    val out = ArrayList<String>()
    val sb = StringBuilder()
    for (ch in text.lowercase()) {
        if (ch.isLetterOrDigit()) sb.append(ch) else if (sb.isNotEmpty()) { out += sb.toString(); sb.setLength(0) }
    }
    if (sb.isNotEmpty()) out += sb.toString()
    return out
}

/**
 * Keyword similarity: term frequency times inverse document frequency, hashed into [dims] buckets.
 * Words are also matched by their first four letters so "visa" finds "visas" without a stemmer.
 */
class TfidfEmbedder(private val dims: Int = 1024) : Embedder {
    override val name = "tfidf-hash-$dims"
    private var idf = HashMap<String, Float>()
    private var defaultIdf = 1f

    override fun prepare(corpus: List<String>) {
        val df = HashMap<String, Int>()
        for (doc in corpus) for (t in terms(doc).toSet()) df[t] = (df[t] ?: 0) + 1
        val n = corpus.size.coerceAtLeast(1)
        idf = HashMap(df.mapValues { (_, d) -> (ln((1.0 + n) / (1.0 + d)) + 1.0).toFloat() })
        defaultIdf = (ln(1.0 + n) + 1.0).toFloat()
    }

    private fun terms(text: String): List<String> {
        val out = ArrayList<String>()
        for (w in tokenize(text)) {
            if (w.length < 2) continue
            out += w
            if (w.length > 4) out += "~" + w.take(4)
        }
        return out
    }

    override fun embed(text: String): FloatArray {
        val v = FloatArray(dims)
        val tf = HashMap<String, Int>()
        for (t in terms(text)) tf[t] = (tf[t] ?: 0) + 1
        for ((t, c) in tf) {
            val w = (1f + ln(c.toFloat())) * (idf[t] ?: defaultIdf)
            val h = t.hashCode()
            val idx = ((h % dims) + dims) % dims
            v[idx] += if ((h ushr 16) and 1 == 0) w else -w
        }
        var norm = 0f
        for (x in v) norm += x * x
        norm = sqrt(norm)
        if (norm > 0f) for (i in v.indices) v[i] /= norm
        return v
    }
}
