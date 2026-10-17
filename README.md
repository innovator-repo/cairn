# Kasane 重ね

One app for every daily routine: checks, counts, timers, rules for the day,
dated tasks, and the analysis Samsung Reminders and the Habits app never gave.
Kotlin and Compose for the screens; the math runs in compiled
[Lume](../../tree/clean/lume) through JNI, with a Kotlin fallback that is held
to the same numbers.

The app lives in [`kasane-android/`](kasane-android). The plan and spec are in
the project's "Daily tracker app spec" document.

## Stages

1. **Today, reminders, tasks, momentum, weekly analysis** (this branch)
2. Sleep lock 22:00 to 08:00 for the personal profile (work profile untouched), relapse guard with an allow-list
3. Pattern puzzles generated every day: built as its own app, Katachi, on `clean/katachi`; it fills in the "Pattern puzzles" count here
4. Import from the Habits app

## Build

```sh
cd kasane-android
# 1. The Lume core (needs the clean/lume branch and an Android NDK)
git -C .. worktree add ../lume clean/lume     # or set LUME_HOME
tools/build-core.sh --check                   # writes app/src/main/jniLibs/*/liblumecore.so
# 2. The app (needs the Android SDK)
gradle :app:assembleRelease
# Core logic only, no Android SDK needed:
gradle -PcoreOnly :core:test
```

## What runs where

| Job | Lume (`lume/core.lm`) | Kotlin |
| --- | --- | --- |
| Momentum per habit | Keel's decay-and-add over all 40 habits per day | Screens |
| Weekly completion rates | `group @ done / group @ sched` over the 366-day grid | Screens |
| What leads to, and follows, a slip | Lagged lift per habit via matrix products | Plain sentences |
| Storage, reminders, setup | | JSON file, `setAlarmClock`, permission card |

`lume/host_check.c` runs the compiled core 1000 times through the same calling
convention as the JNI shim and checks the arena returns to where it started.
`core/src/test` holds the Kotlin fallback to the same cases.
