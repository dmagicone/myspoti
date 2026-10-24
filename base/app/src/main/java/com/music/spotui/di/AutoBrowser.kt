package com.music.spotui.di

import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.support.v4.media.MediaBrowserCompat.MediaItem
import android.support.v4.media.MediaDescriptionCompat
import app.myspoti.engine.R
import app.myspoti.host.android.MyspotiRuntime
import com.music.spotui.data.api.Response
import com.music.spotui.data.entity.ContextPageModel
import com.music.spotui.data.entity.HomeItem
import com.music.spotui.data.entity.HomeSection
import com.music.spotui.data.entity.SongsModel
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The hidden player did not come up in time (cold start, no network). The browse answer is
 * an empty folder then — MediaBrowserCompat has no error reply — and the log says why.
 */
class AudioNotReady(what: String) : IllegalStateException("player not ready: $what")

/**
 * Android Auto / media browser tree, built from the app's own data (not the web page):
 * a home-style root grid (see [rootGrid]), Liked Songs, playlists, albums, followed
 * artists, and search. Tapping a track plays it in its context, the same way the
 * album / playlist screens do.
 *
 * Media ids: "home", "library", "recent", "radio_recent", "top_songs", "liked",
 * "playlists", "albums", "artists" (lists); "shelf:<i>" (one home-feed shelf);
 * "pl:<id>", "al:<id>", "ar:<id>", "sh:<id>" (contexts);
 * "t|<context uri>|<play key>" (a track to play).
 */
object AutoBrowser {
    const val ROOT = "root"
    private const val TIMEOUT_MS = 15_000L
    /**
     * The most one folder lists. A library of thousands of liked songs, each with a cover the car
     * fetches through [ArtworkProvider] (a binder thread held per image), is what froze Android
     * Auto's screen; the car shows no more than a page of a list while driving anyway.
     */
    private const val MAX_ITEMS = 300
    /** Cold start: the car binds before the hidden Web Player is up; browse waits this long. */
    private const val READY_WAIT_MS = 25_000L
    /** The most the root grid lists: section tiles, home cards, recents, then categories. */
    private const val MAX_ROOT_ITEMS = 48
    /** Spotify's own Liked Songs cover (also used by the phone app for the same tile). */
    private const val LIKED_COVER = "https://misc.scdn.co/liked-songs/liked-songs-640.png"

    // Contexts recently listed, so a tap plays with the right queue.
    private val pages = java.util.concurrent.ConcurrentHashMap<String, ContextPageModel>()

    // The last home feed loaded for the car, so "shelf:<i>" can reopen a shelf by index.
    @Volatile private var homeSections: List<HomeSection> = emptyList()

    // The last recents grouping loaded for the car, so the radio tile reuses its seed.
    @Volatile private var recentGroupsCache: List<app.myspoti.api.RecentGroup>? = null

    /**
     * Browse for [parentId]. The root is a home-style grid (see [rootGrid]); if the
     * player is not up yet it falls back to the same section tiles without covers, so
     * the car still draws something at a cold start instead of an empty folder, which
     * Android Auto keeps as the folder's content until it unsubscribes (issue #41).
     * Every other node needs the session and waits for it, for the same reason.
     */
    suspend fun children(context: Context, parentId: String): List<MediaItem> {
        if (parentId == ROOT) return rootGrid(context)
        if (!awaitPlayer()) throw AudioNotReady("browse $parentId")
        return when {
            parentId == "home" -> homeShelves().mapIndexed { i, s ->
                browsable("shelf:$i", s.title, s.items.firstOrNull()?.imageUrl)
            }
            parentId.startsWith("shelf:") -> {
                val sections = if (homeSections.isNotEmpty()) homeSections else homeShelves()
                sections.getOrNull(parentId.removePrefix("shelf:").toIntOrNull() ?: -1)
                    ?.items?.mapNotNull { homeItemTile(it) }.orEmpty()
            }
            parentId == "library" -> {
                // The library in one list, as the phone's Library tab shows it: Liked
                // Songs, then the playlists, then the saved albums (the flat library
                // the categories below also read), then the followed artists.
                val entries = (firstResult { Graph.api.getLibrary() } ?: emptyList())
                    .mapNotNull { libraryEntryTile(it) }
                val artists = Graph.api.getFollowedArtists().filter { it.id.isNotBlank() }.map { a ->
                    browsable("ar:${a.id}|${a.name}", a.name, a.coverUri)
                }
                (entries + artists).take(MAX_ITEMS)
            }
            parentId == "recent" -> {
                val groups = recentGroups()
                val radio = if (recentSeedUri(groups) != null) listOf(
                    browsable("radio_recent", context.getString(R.string.aa_recent_radio), groups.firstPlayCover())
                ) else emptyList()
                radio + groups.mapNotNull { recentTile(it.context) }
            }
            parentId == "radio_recent" -> {
                val seed = recentSeedUri(recentGroups())
                    ?: (firstResult { Graph.api.getSongs() }?.firstOrNull()?.spotifyTrackId
                        ?.takeIf { it.isNotBlank() }?.let { "spotify:track:$it" })
                    ?: return emptyList()
                val radioId = Graph.api.radioPlaylistId(seed) ?: return emptyList()
                tracksOf(firstResult { Graph.api.getPlaylistPage(radioId) })
            }
            parentId == "top_songs" ->
                (firstResult { Graph.api.getSongs() } ?: emptyList()).take(MAX_ITEMS)
                    .map { playable("", it, grid = true) }
            parentId == "playlists" || parentId == "albums" -> {
                val wantPlaylists = parentId == "playlists"
                val entries = (firstResult { Graph.api.getLibrary() } ?: emptyList())
                    .filter { it.isPlaylist == wantPlaylists && it.spotifyId != com.music.spotui.data.api.Api.HomeCache.LIKED_SONGS_ID }
                entries.take(MAX_ITEMS).map { e ->
                    browsable(if (wantPlaylists) "pl:${e.spotifyId}" else "al:${e.spotifyId}", e.name, e.coverUri, subtitle = e.subtitle)
                }
            }
            parentId == "artists" -> Graph.api.getFollowedArtists().filter { it.id.isNotBlank() }.take(MAX_ITEMS).map { a ->
                browsable("ar:${a.id}|${a.name}", a.name, a.coverUri)
            }
            parentId == "liked" -> tracksOf(firstResult { Graph.api.getLikedPage() })
            parentId.startsWith("pl:") -> tracksOf(firstResult { Graph.api.getPlaylistPage(parentId.removePrefix("pl:")) })
            parentId.startsWith("al:") -> tracksOf(firstResult { Graph.api.getAlbumPage(parentId.removePrefix("al:")) })
            parentId.startsWith("ar:") -> {
                val (id, name) = parentId.removePrefix("ar:").split("|", limit = 2).let { it[0] to it.getOrElse(1) { "" } }
                val overview = firstResult { Graph.api.getArtistOverview(name, id) } ?: return emptyList()
                // The artist's albums first (as a grid), then their top tracks:
                // the phone artist page shows the same two things in that order.
                val albums = overview.popularReleases.filter { it.spotifyId.isNotBlank() }.take(MAX_ITEMS).map { a ->
                    browsable("al:${a.spotifyId}", a.name, a.coverUri, subtitle = a.artists)
                }
                albums + overview.topTracks.map { playable("spotify:artist:$id", it.song) }
            }
            parentId.startsWith("sh:") -> {
                val id = parentId.removePrefix("sh:")
                (firstResult { Graph.api.getShowEpisodes(id) } ?: emptyList()).take(MAX_ITEMS)
                    .map { playable("spotify:show:$id", it) }
            }
            else -> emptyList()
        }
    }

    suspend fun search(query: String): List<MediaItem> {
        if (!awaitPlayer()) throw AudioNotReady("search")
        val r = firstResult { Graph.api.searchEverything(query) } ?: return emptyList()
        return r.songs.take(20).map { playable("", it) } +
            r.albums.filter { it.spotifyId.isNotBlank() }.take(8).map { browsable("al:${it.spotifyId}", it.name, it.coverUri, subtitle = it.artists) }
    }

    /** Voice search ("play X on Myspoti"): the best matching track. */
    suspend fun playFromSearch(query: String): Boolean {
        // A cold start asks before the page is up: waiting gives the answer, returning at
        // once would tell the driver nothing matched.
        if (!awaitPlayer()) {
            android.util.Log.w("Myspoti", "voice search dropped: the player page is not ready")
            return false
        }
        val song = (firstResult { Graph.api.searchEverything(query) })?.songs?.firstOrNull() ?: return false
        play("t||${song.url}")
        return true
    }

    /**
     * The root, as a grid with cover art, mimicking the phone's Home tab: section
     * tiles first (Home shelves, Recently Played, Liked Songs, Top Songs), then the
     * home feed's own cards (albums, playlists, artists, shows — radio and mix
     * playlists included, since those are playlist cards in the feed), then recent
     * contexts, then the library categories. Each source loads independently, so
     * one failure only drops its own tiles. Without a ready player (cold start)
     * the section tiles alone are returned, without covers.
     */
    private suspend fun rootGrid(context: Context): List<MediaItem> = coroutineScope {
        if (!awaitPlayer()) return@coroutineScope sectionTiles(context, null, null, null, null, null)
        // In parallel: the root must not keep the car waiting on four loads in a row.
        val groupsD = async { runCatching { recentGroups() }.getOrDefault(emptyList()) }
        val feedD = async { firstResult { Graph.api.getHomeFeed() } }
        val topD = async { firstResult { Graph.api.getSongs() } ?: emptyList() }
        val libraryD = async { firstResult { Graph.api.getLibrary() } ?: emptyList() }
        val groups = groupsD.await()
        val feed = feedD.await()
        if (feed != null) homeSections = feed.sections
        val topSongs = topD.await()
        val library = libraryD.await()

        val tiles = ArrayList<MediaItem>()
        val seen = HashSet<String>()
        fun add(item: MediaItem?) {
            if (item == null) return
            val id = item.description.mediaId ?: return
            if (seen.add(id)) tiles += item
        }

        sectionTiles(
            context,
            recentCover = groups.firstOrNull()?.context?.cover(),
            homeCover = feed?.sections?.firstOrNull()?.items?.firstOrNull()?.imageUrl,
            libraryCover = library.firstOrNull { it.coverUri.isNotBlank() }?.coverUri,
            topCover = topSongs.firstOrNull()?.coverUri,
            likedCover = LIKED_COVER,
        ).forEach { add(it) }

        // The home feed's cards, in feed order, shelves flattened.
        feed?.sections.orEmpty().flatMap { it.items }.forEach { add(homeItemTile(it)) }
        // Recent contexts not already in the feed.
        groups.forEach { add(recentTile(it.context)) }
        // Library categories close the grid, with the first entry's cover each.
        add(browsable("playlists", context.getString(R.string.aa_playlists),
            library.firstOrNull { it.isPlaylist && it.spotifyId != com.music.spotui.data.api.Api.HomeCache.LIKED_SONGS_ID }?.coverUri))
        add(browsable("albums", context.getString(R.string.aa_albums),
            library.firstOrNull { !it.isPlaylist }?.coverUri))
        add(browsable("artists", context.getString(R.string.aa_artists),
            feed?.sections.orEmpty().flatMap { it.items }.filterIsInstance<HomeItem.Artist>()
                .firstOrNull()?.imageUrl))
        tiles.take(MAX_ROOT_ITEMS)
    }

    /** The section tiles of the root grid, in home-tab order. */
    private fun sectionTiles(
        context: Context,
        recentCover: String?,
        homeCover: String?,
        libraryCover: String?,
        topCover: String?,
        likedCover: String?,
    ): List<MediaItem> = listOf(
        browsable("home", context.getString(R.string.aa_home), homeCover),
        browsable("library", context.getString(R.string.aa_library), libraryCover),
        browsable("recent", context.getString(R.string.aa_recent), recentCover),
        browsable("liked", context.getString(R.string.collection_liked_songs_title), likedCover),
        browsable("top_songs", context.getString(R.string.aa_top_songs), topCover),
    )

    /** One flat-library entry as a car tile: Liked Songs, a playlist, or a saved album. */
    private fun libraryEntryTile(e: com.music.spotui.data.entity.LibraryEntry): MediaItem? = when {
        e.spotifyId == com.music.spotui.data.api.Api.HomeCache.LIKED_SONGS_ID ->
            browsable("liked", e.name, e.coverUri.takeIf { it.isNotBlank() } ?: LIKED_COVER, subtitle = e.subtitle)
        e.isPlaylist ->
            if (e.spotifyId.isBlank()) null else browsable("pl:${e.spotifyId}", e.name, e.coverUri, subtitle = e.subtitle)
        else ->
            if (e.spotifyId.isBlank()) null else browsable("al:${e.spotifyId}", e.name, e.coverUri, subtitle = e.subtitle.ifBlank { e.artists })
    }

    /** The home feed's shelves, loading (and caching) the feed if needed. */
    private suspend fun homeShelves(): List<HomeSection> {
        val feed = firstResult { Graph.api.getHomeFeed() } ?: return homeSections
        homeSections = feed.sections
        return feed.sections
    }

    /** One home-feed card as a car tile: contexts open, episodes are skipped. */
    private fun homeItemTile(item: HomeItem): MediaItem? = when (item) {
        is HomeItem.Album ->
            if (item.id.isBlank()) null else browsable("al:${item.id}", item.name, item.imageUrl, subtitle = item.subtitle)
        is HomeItem.Artist ->
            if (item.id.isBlank()) null else browsable("ar:${item.id}|${item.name}", item.name, item.imageUrl)
        is HomeItem.Playlist ->
            if (item.id == com.music.spotui.data.api.Api.HomeCache.LIKED_SONGS_ID)
                browsable("liked", item.name, item.imageUrl.ifBlank { LIKED_COVER }, subtitle = item.subtitle)
            else if (item.id.isBlank()) null
            else browsable("pl:${item.id}", item.name, item.imageUrl, subtitle = item.subtitle)
        is HomeItem.Show ->
            if (item.isEpisode || item.id.isBlank()) null
            else browsable("sh:${item.id}", item.name, item.imageUrl, subtitle = item.subtitle)
    }

    /** The account's recents, grouped by context (newest first), cached for the radio seed. */
    private suspend fun recentGroups(): List<app.myspoti.api.RecentGroup> {
        recentGroupsCache?.let { return it }
        val entries = runCatching { MyspotiRuntime.api.recents.recentEntries(0, 50) }.getOrNull()?.items.orEmpty()
        val groups = app.myspoti.api.groupRecents(entries).flatMap { it.groups }
        if (groups.isNotEmpty()) recentGroupsCache = groups
        return groups
    }

    /** A seed for a radio: the newest recent track, else the newest recent artist. */
    private fun recentSeedUri(groups: List<app.myspoti.api.RecentGroup>): String? =
        groups.flatMap { it.plays }.firstOrNull { it.kind == app.myspoti.api.MediaKind.TRACK }?.uri
            ?: groups.map { it.context }.firstOrNull { it.kind == app.myspoti.api.MediaKind.ARTIST }?.uri

    private fun List<app.myspoti.api.RecentGroup>.firstPlayCover(): String? =
        flatMap { it.plays }.firstOrNull()?.cover() ?: firstOrNull()?.context?.cover()

    /** One recent context as a car tile: contexts open, a lone track plays. */
    private fun recentTile(item: app.myspoti.api.RecentItem): MediaItem? {
        val id = item.uri.substringAfterLast(':')
        if (id.isBlank()) return null
        return when (item.kind) {
            app.myspoti.api.MediaKind.PLAYLIST -> browsable("pl:$id", item.title, item.cover(), subtitle = item.subtitle.orEmpty())
            app.myspoti.api.MediaKind.ALBUM -> browsable("al:$id", item.title, item.cover(), subtitle = item.subtitle.orEmpty())
            app.myspoti.api.MediaKind.ARTIST -> browsable("ar:$id|${item.title}", item.title, item.cover())
            app.myspoti.api.MediaKind.SHOW, app.myspoti.api.MediaKind.AUDIOBOOK ->
                browsable("sh:$id", item.title, item.cover(), subtitle = item.subtitle.orEmpty())
            app.myspoti.api.MediaKind.COLLECTION -> browsable("liked", item.title, item.cover()?.takeIf { it.isNotBlank() } ?: LIKED_COVER)
            app.myspoti.api.MediaKind.TRACK -> playable("", item.toSong(), grid = true)
            else -> null
        }
    }

    private fun app.myspoti.api.RecentItem.cover(): String? =
        images.maxByOrNull { it.width ?: 0 }?.url?.takeIf { it.isNotBlank() }

    /** A recent track row as a playable song (its play key is built the usual way). */
    private fun app.myspoti.api.RecentItem.toSong(): SongsModel {
        val singer = subtitle.orEmpty()
        val trackId = uri.substringAfterLast(':')
        return SongsModel(
            id = trackId.hashCode(),
            title = title,
            album = "",
            singer = singer,
            coverUri = cover().orEmpty(),
            url = SongPlayer.buildSpotifyPlayQuery(trackId, title, singer),
            spotifyTrackId = trackId,
        )
    }

    /** [MyspotiRuntime.api.ready], waited for (bounded) — the page loads after the car connects. */
    private suspend fun awaitPlayer(): Boolean =
        MyspotiRuntime.api.ready.value ||
            withTimeoutOrNull(READY_WAIT_MS) { MyspotiRuntime.api.ready.first { it } } != null

    fun play(mediaId: String) {
        val parts = mediaId.split("|", limit = 3)
        if (parts.size < 3 || parts[0] != "t") return
        val contextUri = parts[1].ifBlank { null }
        val key = parts[2]
        val page = contextUri?.let { pages[it] }
        val song = page?.tracks?.firstOrNull { it.url == key }
        if (page != null) {
            Graph.currentSongState.updateQueue(page.tracks)
            if (page.type == "liked") SongPlayer.registerLikedSongs(page.tracks.map { it.url })
        }
        SongPlayer.playSong(key, contextUri = contextUri)
        song?.let { Graph.currentSongState.updateSongState(it.coverUri, it.title, it.singer, true, it.id, 0, it.album) }
    }

    private fun tracksOf(page: ContextPageModel?): List<MediaItem> {
        page ?: return emptyList()
        pages[page.uri] = page
        return page.tracks.take(MAX_ITEMS).map { playable(page.uri, it) }
    }

    private suspend fun <T> firstResult(load: suspend () -> kotlinx.coroutines.flow.Flow<Response<T>>): T? =
        withTimeoutOrNull(TIMEOUT_MS) {
            (load().first { it !is Response.Loading } as? Response.Success)?.data
        }

    private fun browsable(id: String, title: String, cover: String?, subtitle: String = "", grid: Boolean = true): MediaItem {
        val extras = Bundle().apply {
            putInt("android.media.browse.CONTENT_STYLE_BROWSABLE_HINT", if (grid) 2 else 1)
            putInt("android.media.browse.CONTENT_STYLE_PLAYABLE_HINT", 1)
        }
        val d = MediaDescriptionCompat.Builder().setMediaId(id).setTitle(title).setSubtitle(subtitle)
            .setIconUri(ArtworkProvider.uriFor(cover)).setExtras(extras).build()
        return MediaItem(d, MediaItem.FLAG_BROWSABLE)
    }

    private fun playable(contextUri: String, s: SongsModel, grid: Boolean = false): MediaItem {
        val builder = MediaDescriptionCompat.Builder().setMediaId("t|$contextUri|${s.url}")
            .setTitle(s.title).setSubtitle(s.singer).setIconUri(ArtworkProvider.uriFor(s.coverUri))
        if (grid) builder.setExtras(Bundle().apply {
            putInt("android.media.browse.CONTENT_STYLE_PLAYABLE_HINT", 2)
        })
        return MediaItem(builder.build(), MediaItem.FLAG_PLAYABLE)
    }
}

/**
 * Cover art for Android Auto, which only shows local (content://) artwork: serves an
 * image from Spotify's image CDN through Glide's disk cache. Other hosts are refused.
 */
class ArtworkProvider : android.content.ContentProvider() {
    companion object {
        private val AUTHORITY = app.myspoti.engine.BuildConfig.APPLICATION_ID + ".artwork"
        private val allowedHosts = setOf("i.scdn.co", "mosaic.scdn.co", "image-cdn-ak.spotifycdn.com", "image-cdn-fa.spotifycdn.com", "thisis-images.spotifycdn.com", "seeded-session-images.scdn.co", "pickasso.spotifycdn.com", "misc.scdn.co")

        fun uriFor(url: String?): Uri? {
            if (url.isNullOrBlank() || !allowed(url)) return null
            return Uri.Builder().scheme("content").authority(AUTHORITY).appendPath("img").appendQueryParameter("u", url).build()
        }

        /**
         * Only https URLs on the CDN hosts: a `file:` URL whose authority happens to be
         * an allowed host (`file://i.scdn.co/data/data/...`) must never reach Glide,
         * which would open the local path and hand this app's private files to any caller
         * (the provider is exported, for Android Auto).
         */
        private fun allowed(url: String): Boolean {
            val u = Uri.parse(url)
            return u.scheme == "https" && u.host in allowedHosts
        }
    }

    override fun openFile(uri: Uri, mode: String): android.os.ParcelFileDescriptor? {
        val url = uri.getQueryParameter("u") ?: return null
        if (!allowed(url)) return null
        // Bounded: a binder thread must not wait on the network forever.
        val file = runCatching {
            com.bumptech.glide.Glide.with(context!!).asFile().load(url).submit()
                .get(15, java.util.concurrent.TimeUnit.SECONDS)
        }.getOrNull() ?: return null
        return android.os.ParcelFileDescriptor.open(file, android.os.ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun getType(uri: Uri) = "image/jpeg"
    override fun onCreate() = true
    override fun query(uri: Uri, p: Array<out String>?, s: String?, a: Array<out String>?, o: String?) = null
    override fun insert(uri: Uri, values: android.content.ContentValues?) = null
    override fun delete(uri: Uri, s: String?, a: Array<out String>?) = 0
    override fun update(uri: Uri, v: android.content.ContentValues?, s: String?, a: Array<out String>?) = 0
}
