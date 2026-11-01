package app.cairn.data

/** A small sample set so the first run shows a working map. Delete any of it freely. */
object Seed {
    class Note(val area: String, val title: String, val body: String, val ageDays: Int)

    private class Topic(val area: String, val nouns: List<String>, val words: List<String>)

    private val topics = listOf(
        Topic("Travel", listOf("Tokyo", "Kyoto", "Osaka", "Nara", "Hokkaido", "Okinawa", "rail pass", "hotel", "visa", "packing", "onsen", "museum", "airport", "ferry"),
            listOf("itinerary", "booking", "route", "budget", "map", "ticket", "passport", "luggage", "train", "reservation")),
        Topic("Japanese", listOf("kanji", "verbs", "particles", "listening", "reading", "grammar", "vocabulary", "keigo", "counters", "JLPT N4", "dictation", "pitch accent"),
            listOf("practice", "flashcards", "mnemonic", "review", "lesson", "sentence", "example", "drill", "quiz", "tutor")),
        Topic("Code", listOf("Gradle", "Compose", "Kotlin", "widgets", "tests", "release", "refactor", "database", "network", "settings", "build cache", "lint"),
            listOf("sprint", "bug", "design", "snippet", "review", "module", "pipeline", "benchmark", "profile", "checklist")),
        Topic("Money", listOf("budget", "rent", "savings", "taxes", "travel fund", "insurance", "subscriptions", "invoices", "salary", "investments", "bank", "receipts"),
            listOf("monthly", "forecast", "review", "plan", "tracker", "limit", "goal", "statement", "transfer", "report")),
        Topic("Life", listOf("reading list", "weekly review", "garden", "recipes", "fitness", "sleep", "friends", "gifts", "home", "errands", "journal", "ideas"),
            listOf("routine", "plan", "notes", "habit", "reminder", "list", "reflection", "schedule", "wishlist", "challenge")),
        Topic("Health", listOf("running", "stretching", "doctor", "vitamins", "meals", "water", "posture", "dentist", "sleep log", "yoga", "walking", "checkup"),
            listOf("routine", "plan", "tracker", "goal", "notes", "schedule", "results", "advice", "habit", "reminder")),
    )
    private val templates = listOf("%s notes", "%s checklist", "%s plan", "%s ideas", "%s questions", "%s log", "Next steps: %s", "%s summary")

    /** About 160 generated notes so the first map is full: the same words recur within an area, so links form. */
    val generated: List<Note> = run {
        val rnd = java.util.Random(11)
        val out = ArrayList<Note>()
        for (t in topics) {
            val titles = LinkedHashSet<String>()
            var guard = 0
            while (titles.size < 26 && guard++ < 500) {
                titles += templates[rnd.nextInt(templates.size)].format(t.nouns[rnd.nextInt(t.nouns.size)]).replaceFirstChar { it.uppercase() }
            }
            val list = titles.toList()
            for ((i, title) in list.withIndex()) {
                val a = t.words[rnd.nextInt(t.words.size)]
                val b = t.words[rnd.nextInt(t.words.size)]
                val noun = t.nouns[rnd.nextInt(t.nouns.size)]
                val link = if (i > 0 && rnd.nextInt(3) == 0) " See [[${list[rnd.nextInt(i)]}]]." else ""
                val body = if (i % 13 == 5) "https://example.com/${t.area.lowercase()}/${i + 1} saved link about $noun $a"
                else "A $a for $noun with a $b and a few open questions.$link"
                out += Note(t.area, title, body, rnd.nextInt(60))
            }
        }
        out
    }

    val notes = generated + listOf(
        Note("Travel", "Passport and visa checklist", "Renew passport, visa photo, bank statement. See [[Flight to Tokyo]].", 3),
        Note("Travel", "Flight to Tokyo", "Seat 23A, departs 08:40. Rail pass collected at the airport.", 5),
        Note("Travel", "Packing list", "Adapter, rain jacket, cash, notebook. Check weight limit.", 9),
        Note("Travel", "Tokyo rail pass guide", "https://example.com/tokyo-rail-pass saved link about passes and routes", 12),
        Note("Japanese", "Reading notes", "食べる たべる to eat. Verb forms practice from the weekly reading.", 1),
        Note("Japanese", "Verb forms cheat sheet", "Te-form, ta-form, nai-form. Group one and group two verbs.", 6),
        Note("Japanese", "Kanji mnemonics", "Short stories for the first hundred kanji. Review ten a day.", 14),
        Note("Code", "Widget design notes", "Rows, checkboxes and snooze for the home screen widget. Related: [[Sprint plan]].", 4),
        Note("Code", "Sprint plan", "Next release: fix snooze, tidy the settings screen, write the tests.", 2),
        Note("Code", "Gradle tips", "Use the wrapper, keep versions in settings, run unit tests before pushing.", 8),
        Note("Money", "Monthly budget", "Rent, travel fund, savings. Review at the end of each month.", 7),
        Note("Money", "Travel fund", "Set aside a fixed amount for the Tokyo trip. Link to [[Flight to Tokyo]].", 10),
        Note("Life", "Reading list", "Books to read this year, one per month.", 11),
        Note("Life", "Weekly review", "What went well, what to drop, what to try next week.", 0),
    )
}
