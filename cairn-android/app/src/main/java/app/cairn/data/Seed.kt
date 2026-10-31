package app.cairn.data

/** A small sample set so the first run shows a working map. Delete any of it freely. */
object Seed {
    class Note(val area: String, val title: String, val body: String, val ageDays: Int)

    val notes = listOf(
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
