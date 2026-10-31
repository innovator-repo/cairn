package app.cairn

import app.cairn.data.Entry
import app.cairn.data.Kind
import app.cairn.graph.Graph
import app.cairn.graph.ViewMode
import app.cairn.search.SearchEngine
import app.cairn.search.TfidfEmbedder
import app.cairn.search.dot
import app.cairn.search.tokenize
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchAndGraphTest {
    private val day = 86_400_000L
    private val now = 1_800_000_000_000L

    private fun e(id: String, area: String, title: String, text: String, kind: Kind = Kind.NOTE, age: Int = 1, path: String = "notes/$area/$id.md") =
        Entry(id, kind, area, title, path, text, now - age * day)

    private val sample = listOf(
        e("a", "Travel", "Passport and visa checklist", "renew passport, visa photo, bank statement"),
        e("b", "Travel", "Flight to Tokyo", "seat 23A departs early, passport at hand"),
        e("c", "Japanese", "Verb forms", "te-form ta-form nai-form for verbs"),
        e("d", "Code", "Gradle tips", "use the wrapper, run unit tests", path = "notes/Code/gradle.md"),
        e("e", "Code", "Widget notes", "rows checkboxes snooze for the widget"),
    )

    @Test fun tokenizeSplitsWordsAndKeepsJapanese() {
        assertEquals(listOf("hello", "world", "x"), tokenize("Hello, world-x"))
        assertEquals(listOf("食べる", "to", "eat"), tokenize("食べる to eat"))
    }

    @Test fun embeddingsAreUnitLengthAndSimilarTextScoresHigher() {
        val emb = TfidfEmbedder(512)
        emb.prepare(sample.map { it.searchable })
        val a = emb.embed(sample[0].searchable)
        val b = emb.embed(sample[1].searchable)
        val c = emb.embed(sample[3].searchable)
        assertEquals(1f, dot(a, a), 1e-3f)
        assertTrue(dot(a, b) > dot(a, c))
        assertEquals(0f, emb.embed("").sum(), 0f)
    }

    @Test fun searchFindsTitleBodyAndPath() {
        val s = SearchEngine(sample, TfidfEmbedder(512))
        assertEquals("a", s.search("visa").first().entry.id)
        assertEquals("b", s.search("tokyo flight").first().entry.id)
        assertEquals("d", s.search("code/gradle").first().entry.id)
        assertEquals("e", s.search("snooze").first().entry.id)
        assertTrue(s.search("   ").isEmpty())
        assertTrue(s.search("zzzzqqq").isEmpty())
    }

    @Test fun searchMatchesPrefixVariants() {
        val s = SearchEngine(sample, TfidfEmbedder(512))
        assertEquals("a", s.search("passports").first().entry.id)
    }

    @Test fun graphHasEveryNodeInEveryViewWithFiniteCoordinates() {
        val g = Graph.build(sample, TfidfEmbedder(512), now)
        assertEquals(5, g.size)
        for (v in ViewMode.entries) {
            val p = g.positions.getValue(v)
            assertEquals(15, p.size)
            assertTrue(p.all { it.isFinite() })
        }
    }

    @Test fun globePositionsLieOnTheUnitSphere() {
        val g = Graph.build(sample, TfidfEmbedder(512), now)
        val p = g.positions.getValue(ViewMode.GLOBE)
        for (i in 0 until g.size) {
            val len = Math.sqrt((p[3 * i] * p[3 * i] + p[3 * i + 1] * p[3 * i + 1] + p[3 * i + 2] * p[3 * i + 2]).toDouble())
            assertEquals(1.0, len, 1e-4)
        }
    }

    @Test fun layoutIsDeterministic() {
        val g1 = Graph.build(sample, TfidfEmbedder(512), now)
        val g2 = Graph.build(sample, TfidfEmbedder(512), now)
        for (v in ViewMode.entries) assertTrue(g1.positions.getValue(v).contentEquals(g2.positions.getValue(v)))
    }

    @Test fun relatedNotesLinkAndWikiLinksAreHonoured() {
        val withWiki = sample + e("w", "Life", "Plan", "see [[Flight to Tokyo]] soon")
        val g = Graph.build(withWiki, TfidfEmbedder(512), now)
        val plan = withWiki.indexOfFirst { it.id == "w" }
        val flight = withWiki.indexOfFirst { it.id == "b" }
        assertTrue(g.neighbours(plan).contains(flight))
        val a = withWiki.indexOfFirst { it.id == "a" }
        assertTrue(g.neighbours(a).contains(flight))
        assertTrue(g.links.none { it[0] == it[1] })
        assertEquals(g.links.size, g.links.map { it.toList() }.toSet().size)
    }

    @Test fun issuesLinkToTheirRepo() {
        val items = listOf(
            e("gh:o/r", "GitHub", "r", "a repo", Kind.REPO, path = "o/r"),
            e("gh:o/r#1", "GitHub", "#1 broken", "something else entirely", Kind.ISSUE, path = "o/r#1"),
            e("n", "Life", "Garden", "tomatoes and basil"),
        )
        val g = Graph.build(items, TfidfEmbedder(512), now)
        assertTrue(g.neighbours(1).contains(0))
    }

    @Test fun areasAreOrderedBySizeAndColoured() {
        val g = Graph.build(sample, TfidfEmbedder(512), now)
        assertEquals(setOf("Travel", "Code", "Japanese"), g.areas.map { it.name }.toSet())
        // Travel and Code have two notes each; Japanese has one, so it comes last.
        assertEquals("Japanese", g.areas.last().name)
        assertEquals(g.areas.size, g.areas.map { it.color }.toSet().size)
    }

    @Test fun singleAreaAndSingleNodeDoNotBreak() {
        val g = Graph.build(listOf(e("x", "Life", "Only", "just one")), TfidfEmbedder(512), now)
        assertEquals(1, g.size)
        assertTrue(g.positions.values.all { p -> p.all { it.isFinite() } })
        assertTrue(Graph.build(emptyList(), TfidfEmbedder(512), now).size == 0)
    }
}
