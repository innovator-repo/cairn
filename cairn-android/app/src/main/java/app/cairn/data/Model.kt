package app.cairn.data

/** What a node is. The map draws each kind with its own shape. */
enum class Kind(val label: String) {
    NOTE("note"), REPO("repo"), FILE("file"), ISSUE("issue"), LINK("link");

    companion object {
        fun parse(s: String?): Kind = entries.firstOrNull { it.name.equals(s, ignoreCase = true) } ?: NOTE
    }
}

/**
 * One thing in the brain: a note, a repo, an issue. [text] is what search reads (the note body or
 * README, trimmed). [url] is set for items that live on the web and open in the browser.
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
    val isNote: Boolean get() = kind == Kind.NOTE || kind == Kind.LINK
    /** Title, path and body as one string, the thing the embedder sees. */
    val searchable: String get() = "$title $title $path $text"
}
