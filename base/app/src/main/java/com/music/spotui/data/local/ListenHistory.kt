package com.music.spotui.data.local

import android.content.Context
import org.json.JSONObject
import java.io.File

/**
 * Every listen on this phone, whatever the source (Spotify, downloads, local files), for the
 * in-app recap. Stays on the phone; nothing is reported to Spotify or anyone else.
 * A listen counts after 30 s, like most services.
 */
object ListenHistory {
    enum class Source { SPOTIFY, DOWNLOAD, LOCAL }

    data class Listen(
        val atMs: Long,
        val key: String,
        val title: String,
        val artist: String,
        val album: String,
        val cover: String,
        val playedMs: Long,
        val source: Source,
    )

    const val MIN_PLAYED_MS = 30_000L
    private var file: File? = null
    private val lock = Any()

    fun init(context: Context) {
        file = File(context.filesDir, "listen_history.jsonl")
    }

    fun record(listen: Listen) {
        if (listen.playedMs < MIN_PLAYED_MS || listen.title.isBlank()) return
        val f = file ?: return
        val line = JSONObject()
            .put("at", listen.atMs).put("key", listen.key).put("title", listen.title).put("artist", listen.artist)
            .put("album", listen.album).put("cover", listen.cover).put("ms", listen.playedMs).put("src", listen.source.name)
            .toString()
        synchronized(lock) { f.appendText(line + "\n") }
    }

    fun all(): List<Listen> = synchronized(lock) {
        val f = file?.takeIf { it.exists() } ?: return emptyList()
        f.readLines().mapNotNull { line ->
            runCatching {
                val o = JSONObject(line)
                Listen(o.getLong("at"), o.getString("key"), o.getString("title"), o.optString("artist"), o.optString("album"),
                    o.optString("cover"), o.getLong("ms"), Source.valueOf(o.optString("src", "SPOTIFY")))
            }.getOrNull()
        }
    }

    fun clear() = synchronized(lock) { file?.delete() }

    data class Ranked(val name: String, val subtitle: String, val cover: String, val count: Int, val minutes: Long)

    data class Recap(
        val listens: Int,
        val minutes: Long,
        val bySource: Map<Source, Long>,
        val topTracks: List<Ranked>,
        val topArtists: List<Ranked>,
        val topAlbums: List<Ranked>,
    )

    /** One calendar week of listening: its first day (local midnight, epoch ms) and the minutes played in it. */
    data class Week(val startMs: Long, val minutes: Long)

    /** Minutes per week over the last [weeks] calendar weeks, oldest first, the current week last. */
    fun weekly(weeks: Int = 8, nowMs: Long = System.currentTimeMillis()): List<Week> = weekly(all(), weeks, nowMs)

    /**
     * Groups [listens] by calendar week (weeks start on the locale's first day, in [zone]); the
     * current, unfinished week is last. Weeks without listens count 0.
     */
    fun weekly(
        listens: List<Listen>,
        weeks: Int,
        nowMs: Long,
        zone: java.util.TimeZone = java.util.TimeZone.getDefault(),
        firstDayOfWeek: Int = java.util.Calendar.getInstance().firstDayOfWeek,
    ): List<Week> {
        val cal = java.util.Calendar.getInstance(zone).apply {
            this.firstDayOfWeek = firstDayOfWeek
            timeInMillis = nowMs
            set(java.util.Calendar.HOUR_OF_DAY, 0); set(java.util.Calendar.MINUTE, 0)
            set(java.util.Calendar.SECOND, 0); set(java.util.Calendar.MILLISECOND, 0)
            while (get(java.util.Calendar.DAY_OF_WEEK) != firstDayOfWeek) add(java.util.Calendar.DAY_OF_MONTH, -1)
        }
        // Week starts, newest first; calendar arithmetic keeps daylight-saving weeks right.
        val starts = (0 until weeks).map { i ->
            if (i > 0) cal.add(java.util.Calendar.WEEK_OF_YEAR, -1)
            cal.timeInMillis
        }.reversed()
        val ends = starts.drop(1) + Long.MAX_VALUE
        return starts.indices.map { i ->
            val ms = listens.filter { it.atMs >= starts[i] && it.atMs < ends[i] && it.atMs <= nowMs }.sumOf { it.playedMs }
            Week(starts[i], ms / 60_000)
        }
    }

    /** Totals and top lists for listens since [sinceMs]. */
    fun recap(sinceMs: Long): Recap {
        val list = all().filter { it.atMs >= sinceMs }
        fun rank(group: (Listen) -> String, subtitle: (Listen) -> String) = list.groupBy(group)
            .filterKeys { it.isNotBlank() }
            .map { (name, l) -> Ranked(name, subtitle(l.first()), l.first().cover, l.size, l.sumOf { it.playedMs } / 60_000) }
            .sortedWith(compareByDescending<Ranked> { it.count }.thenByDescending { it.minutes })
            .take(10)
        return Recap(
            listens = list.size,
            minutes = list.sumOf { it.playedMs } / 60_000,
            bySource = list.groupBy { it.source }.mapValues { (_, l) -> l.sumOf { it.playedMs } / 60_000 },
            topTracks = rank({ it.title + "\u0000" + it.artist }, { it.artist }).map { it.copy(name = it.name.substringBefore('\u0000')) },
            topArtists = rank({ it.artist.substringBefore(",").trim() }, { "" }),
            topAlbums = rank({ it.album }, { it.artist }),
        )
    }
}
