package app.cairn.data

/** The one shape each kind is drawn with, everywhere (map, legend, search results, cards, lists). */
enum class Shape { CIRCLE, SQUARE, PAGE, TRIANGLE, DIAMOND }

/** What a node is. */
enum class Kind(val label: String, val shape: Shape) {
    NOTE("note", Shape.CIRCLE),
    REPO("repo", Shape.SQUARE),
    FILE("file", Shape.PAGE),
    ISSUE("issue", Shape.TRIANGLE),
    LINK("link", Shape.DIAMOND);

    companion object {
        fun parse(s: String?): Kind = entries.firstOrNull { it.name.equals(s, ignoreCase = true) } ?: NOTE
    }
}

/**
 * One thing in the brain: a note, a repo, an issue, a file. [text] is a short excerpt for cards and
 * link building; full text for search lives in the full-text index. [url] is set for items on the web.
 */
data class Entry(
    val id: String,
    val kind: Kind,
    val area: String,
    val title: String,
    val path: String,
    val text: String,
    val updatedAt: Long,
    val url: String? = null,
) {
    /** Title, path and excerpt as one string, the thing the link embedder sees. */
    val searchable: String get() = "$title $title $path $text"
}
