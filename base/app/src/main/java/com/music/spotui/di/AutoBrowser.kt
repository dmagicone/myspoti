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
import com.music.spotui.data.preferences.AutoLayoutPref
import com.music.spotui.data.preferences.layoutMode
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
 * Stock is upstream's tree exactly as shipped (see [stockChildren]); layout A is a
 * home-style root grid (see [rootGrid]); layout B is Spotify-style sections (see
 * [rootSections]). The three are picked in Settings > Display; the Stock path uses
 * no fork code at all, not even the widened artwork hosts. Liked Songs,
 * playlists, albums, followed artists, and search are shared by A and B. Tapping a
 * track plays it in its context, the same way the album / playlist screens do.
 *
 * Media ids: "home", "library", "recent", "recents", "browse", "radio", "radio_recent",
 * "top_songs", "liked", "playlists", "albums", "artists" (lists); "shelf:<i>"
 * (one home-feed shelf); "cat:<uri>" (one browse category); "pl:<id>", "al:<id>",
 * "ar:<id>", "sh:<id>" (contexts);
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
    /** The most of one shelf layout B's Home shows inline; the full shelf is one tap away. */
    private const val INLINE_PER_SHELF = 10
    /** Spotify's own Liked Songs cover (also used by the phone app for the same tile). */
    private const val LIKED_COVER = "https://misc.scdn.co/liked-songs/liked-songs-640.png"

    // Contexts recently listed, so a tap plays with the right queue.
    private val pages = java.util.concurrent.ConcurrentHashMap<String, ContextPageModel>()

    // The last home feed loaded for the car, so "shelf:<i>" can reopen a shelf by index.
    @Volatile private var homeSections: List<HomeSection> = emptyList()

    // The last recents grouping loaded for the car, so the radio tile reuses its seed.
    @Volatile private var recentGroupsCache: List<app.myspoti.api.RecentGroup>? = null

    /**
     * Browse for [parentId]. The root is layout A's home-style grid (see [rootGrid])
     * or layout B's sections (see [rootSections]); if the player is not up yet the
     * root falls back to the same tiles without covers, so the car still draws
     * something at a cold start instead of an empty folder, which Android Auto keeps
     * as the folder's content until it unsubscribes (issue #41). Every other node
     * needs the session and waits for it, for the same reason.
     */
    suspend fun children(context: Context, parentId: String): List<MediaItem> {
        if (layoutMode(context) == AutoLayoutPref.STOCK) return stockChildren(context, parentId)
        if (parentId == ROOT) return if (layoutMode(context) == AutoLayoutPref.B) rootSections(context) else rootGrid(context)
        if (!awaitPlayer()) throw AudioNotReady("browse $parentId")
        return when {
            // Layout B's Home is one scrolling page: the recents, then each shelf
            // with its header and its items inline; layout A's is the shelves alone.
            parentId == "home" -> if (layoutMode(context) == AutoLayoutPref.B) homePage(context) else shelfTiles()
            parentId == "browse" -> browseCategoryTiles()
            parentId.startsWith("cat:") -> {
                // One browse category: its playlists/albums, through the same
                // mapping the phone's category screen uses (shows are dropped
                // there too — see getCategoryPlaylists).
                val uri = android.net.Uri.decode(parentId.removePrefix("cat:"))
                (firstResult { Graph.api.getCategoryPlaylists(uri) } ?: emptyList())
                    .mapNotNull { libraryEntryTile(it) }.take(MAX_ITEMS)
            }
            parentId == "radio" -> radioTiles(context)
            // Layout B's Recents: the recents plus a tile into the top songs, so
            // every node layout A has stays reachable in B.
            parentId == "recents" -> {
                val topCover = (firstResult { Graph.api.getSongs() } ?: emptyList()).firstOrNull()?.coverUri
                recentItems(context) + browsable("top_songs", context.getString(R.string.aa_top_songs), topCover)
            }
            parentId.startsWith("shelf:") -> {
                val sections = if (homeSections.isNotEmpty()) homeSections else homeShelves()
                sections.getOrNull(parentId.removePrefix("shelf:").toIntOrNull() ?: -1)
                    ?.items?.mapNotNull { homeItemTile(it) }.orEmpty()
            }
            parentId == "library" -> if (layoutMode(context) == AutoLayoutPref.B) libraryRows(context) else {
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
            parentId == "recent" -> recentItems(context)
            parentId == "radio_recent" -> {
                val seed = recentSeedUri(recentGroups())
                    ?: (firstResult { Graph.api.getSongs() }?.firstOrNull()?.spotifyTrackId
                        ?.takeIf { it.isNotBlank() }?.let { "spotify:track:$it" })
                    ?: return emptyList()
                val radioId = Graph.api.radioPlaylistId(seed) ?: return emptyList()
                tracksOf(context, firstResult { Graph.api.getPlaylistPage(radioId) })
            }
            parentId == "top_songs" ->
                (firstResult { Graph.api.getSongs() } ?: emptyList()).take(MAX_ITEMS)
                    .map { playable("", it, grid = true) }
            parentId == "playlists" || parentId == "albums" -> {
                val wantPlaylists = parentId == "playlists"
                val entries = (firstResult { Graph.api.getLibrary() } ?: emptyList())
                    .filter { it.isPlaylist == wantPlaylists && it.spotifyId != com.music.spotui.data.api.Api.HomeCache.LIKED_SONGS_ID }
                // Liked Songs heads the playlists, as Spotify's Library does; the
                // flat library keeps it out of this list, so it is pinned here.
                // Shared with layout A, which gains it inside Playlists too.
                val liked = if (wantPlaylists) listOf(
                    browsable("liked", context.getString(R.string.collection_liked_songs_title), LIKED_COVER)
                ) else emptyList()
                liked + entries.take(MAX_ITEMS).map { e ->
                    browsable(if (wantPlaylists) "pl:${e.spotifyId}" else "al:${e.spotifyId}", e.name, e.coverUri, subtitle = e.subtitle)
                }
            }
            parentId == "artists" -> Graph.api.getFollowedArtists().filter { it.id.isNotBlank() }.take(MAX_ITEMS).map { a ->
                browsable("ar:${a.id}|${a.name}", a.name, a.coverUri)
            }
            parentId == "liked" -> tracksOf(context, firstResult { Graph.api.getLikedPage() })
            parentId.startsWith("pl:") -> tracksOf(context, firstResult { Graph.api.getPlaylistPage(parentId.removePrefix("pl:")) })
            parentId.startsWith("al:") -> tracksOf(context, firstResult { Graph.api.getAlbumPage(parentId.removePrefix("al:")) })
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
        if (layoutMode(Graph.app) == AutoLayoutPref.STOCK) return stockSearch(query)
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

    /** One home-feed shelf per tile, as layout A's Home shows them. */
    private suspend fun shelfTiles(): List<MediaItem> =
        homeShelves().mapIndexed { i, s ->
            browsable("shelf:$i", s.title, s.items.firstOrNull()?.imageUrl)
        }

    /**
     * The recent node: the recent-radio station first when it has a seed, then the
     * recent contexts, newest first. Shared by layout A's "recent" and layout B's
     * "recents" (both keep everything, tracks included); layout B's Home uses the
     * shorter context-only [homeRecentsPreview] instead.
     */
    private suspend fun recentItems(context: Context): List<MediaItem> {
        val groups = recentGroups()
        val radio = if (recentSeedUri(groups) != null) listOf(
            browsable("radio_recent", context.getString(R.string.aa_recent_radio), groups.firstPlayCover())
        ) else emptyList()
        return radio + groups.mapNotNull { recentTile(it.context) }
    }

    /**
     * Layout B's Home preview of the recents: the radio tile (as in [recentItems]),
     * then the Liked Songs tile, pinned as Spotify pins it in the Home top grid,
     * plus at most 6 recent CONTEXTS — playlists, albums, artists, shows,
     * audiobooks, collections — never lone tracks, and never the full 50-entry
     * recent list. Spotify's Home top grid shows a handful of recent contexts,
     * not tracks and not the whole history. The pinned tile is skipped when a
     * recent context already maps to it (a COLLECTION recent), so it is never
     * emitted twice. The radio tile is omitted when it would have no cover:
     * [firstPlayCover] falling blank means the first context's cover is blank
     * too, so the fallback scans all recent contexts for the first usable
     * cover, and gives up when there is none.
     */
    private suspend fun homeRecentsPreview(context: Context): List<MediaItem> {
        val groups = recentGroups()
        val radioCover = groups.firstPlayCover()?.takeIf { it.isNotBlank() }
            ?: groups.map { it.context }.firstNotNullOfOrNull { it.cover() }
        val radio = if (recentSeedUri(groups) != null && radioCover != null) listOf(
            browsable("radio_recent", context.getString(R.string.aa_recent_radio), radioCover)
        ) else emptyList()
        val contexts = groups.map { it.context }
            .filter { it.kind != app.myspoti.api.MediaKind.TRACK }
            .take(6)
            .mapNotNull { recentTile(it) }
        val liked = if (contexts.none { it.description.mediaId == "liked" }) listOf(
            browsable("liked", context.getString(R.string.collection_liked_songs_title), LIKED_COVER)
        ) else emptyList()
        return radio + liked + contexts
    }

    /**
     * Layout B's Home, one scrolling page: the recents preview first (see
     * [homeRecentsPreview]), then each home-feed shelf in feed order — a header
     * row (the shelf's title, the shelf's first item's image) that opens the
     * full shelf node, followed by the shelf's first items inline as grid cards,
     * mapped exactly as the shelf node maps them ([homeItemTile]). The header
     * doubles as the shelf's "see all", so the full shelf is always one tap
     * away. The header is a LIST-style item (grid=false) while the shelf items
     * stay grid cards: in the car's grid rendering a header card looks identical
     * to every other card and sections visually merge, so the header renders as
     * a full-width row that breaks the grid into visible sections — if the car
     * honors per-item content-style hints. If it ignores them, headers render
     * as grid cards and sections still merge; that is car-dependent and only
     * the car test can confirm it. The shelf list comes from [homeShelves],
     * the same list the shelf node indexes, so a header's "shelf:<i>" always
     * opens the shelf it heads. A shelf with no item carrying an image is
     * skipped whole: a coverless grid tile renders as a broken-image icon in
     * the car. Capped at [MAX_ITEMS].
     */
    private suspend fun homePage(context: Context): List<MediaItem> {
        val items = ArrayList<MediaItem>()
        items += homeRecentsPreview(context)
        val sections = homeShelves()
        for ((i, s) in sections.withIndex()) {
            if (items.size >= MAX_ITEMS) break
            val cover = s.items.firstOrNull { it.imageUrl.isNotBlank() }?.imageUrl ?: continue
            items += browsable("shelf:$i", s.title, cover, grid = false)
            s.items.mapNotNull { homeItemTile(it) }.take(INLINE_PER_SHELF).forEach { items += it }
        }
        return items.take(MAX_ITEMS)
    }

    /**
     * Layout B's Library: three list rows into the category nodes, as in the
     * photo — Playlists, Albums, Artists, each with a chevron. Each row carries
     * the first usable cover of its category: this car renders a broken-image
     * icon for coverless rows, so the default-glyph assumption does not hold.
     * A category with no usable cover keeps null for that row (an edge case,
     * not a silent fallback). The flat "library" list is not used for B.
     */
    private suspend fun libraryRows(context: Context): List<MediaItem> {
        val library = firstResult { Graph.api.getLibrary() } ?: emptyList()
        val artists = runCatching { Graph.api.getFollowedArtists() }.getOrDefault(emptyList())
        return listOf(
            browsable("playlists", context.getString(R.string.aa_playlists),
                library.firstOrNull { it.isPlaylist && it.coverUri.isNotBlank() }?.coverUri, grid = false),
            browsable("albums", context.getString(R.string.aa_albums),
                library.firstOrNull { !it.isPlaylist && it.coverUri.isNotBlank() }?.coverUri, grid = false),
            browsable("artists", context.getString(R.string.aa_artists),
                artists.firstOrNull { it.coverUri.isNotBlank() }?.coverUri, grid = false),
        )
    }

    /**
     * Layout B's Browse: Spotify's real browse categories (the Web Player's
     * "Browse all" from browseStart, the same list the phone Search screen
     * shows) — not shelves, and not a hardcoded list. Whatever browseStart
     * returns is what shows, in its order, so Podcasts/Audiobooks appear only
     * if Spotify lists them there; nothing is invented. Each tile opens its
     * category node ("cat:<uri>"). Categories whose art the artwork provider
     * cannot load are skipped rather than shown broken. New Releases is not
     * a browseStart category (the phone prepends it as a synthetic tile), so
     * it is not here either.
     */
    private suspend fun browseCategoryTiles(): List<MediaItem> =
        runCatching { MyspotiRuntime.api.home.categories() }.getOrDefault(emptyList())
            .filter { it.title.isNotBlank() && it.uri.isNotBlank() && it.images.firstOrNull()?.url?.let { ArtworkProvider.uriForLayout(it) } != null }
            .take(MAX_ITEMS)
            .map { browsable("cat:" + android.net.Uri.encode(it.uri), it.title, it.images.firstOrNull()?.url) }

    /**
     * Layout B's Radio: the recent-radio station and the top songs, as tiles into
     * the nodes both layouts already have. No new data source: the car has no
     * text-search input here, and there is no Downloaded section (nothing is
     * downloaded) and no Jam (that needs a Premium-hosted session).
     */
    private suspend fun radioTiles(context: Context): List<MediaItem> {
        val groups = recentGroups()
        val radio = if (recentSeedUri(groups) != null) listOf(
            browsable("radio_recent", context.getString(R.string.aa_recent_radio), groups.firstPlayCover())
        ) else emptyList()
        val topCover = (firstResult { Graph.api.getSongs() } ?: emptyList()).firstOrNull()?.coverUri
        return radio + listOf(browsable("top_songs", context.getString(R.string.aa_top_songs), topCover))
    }

    /**
     * Layout B's root: the car's top tabs — Home, Recents, Browse, Library, in
     * that order — instead of layout A's grid. There is no Radio tab: radio lives
     * inside Home as shelves, as in the car photos. Home is the one scrolling
     * page ([homePage]), Recents is the recents plus the top songs, Browse is
     * Spotify's real categories ([browseCategoryTiles]), Library is the three
     * category rows. Cold start: the four tiles without covers, like A's section
     * tiles. A flip of the setting takes effect on the next Android Auto
     * connection: the car keeps the tree it browsed, and this app has no
     * live-invalidation path to push a new root.
     */
    private suspend fun rootSections(context: Context): List<MediaItem> = coroutineScope {
        if (!awaitPlayer()) return@coroutineScope listOf(
            browsable("home", context.getString(R.string.aa_home), null),
            browsable("recents", context.getString(R.string.aa_recents), null),
            browsable("browse", context.getString(R.string.aa_browse), null),
            browsable("library", context.getString(R.string.aa_library), null),
        )
        // In parallel: the root must not keep the car waiting on four loads in a row.
        val groupsD = async { runCatching { recentGroups() }.getOrDefault(emptyList()) }
        val feedD = async { firstResult { Graph.api.getHomeFeed() } }
        val libraryD = async { firstResult { Graph.api.getLibrary() } ?: emptyList() }
        val categoriesD = async { runCatching { MyspotiRuntime.api.home.categories() }.getOrDefault(emptyList()) }
        val groups = groupsD.await()
        val feed = feedD.await()
        if (feed != null) homeSections = feed.sections
        val library = libraryD.await()
        val categories = categoriesD.await()
        listOf(
            browsable("home", context.getString(R.string.aa_home),
                groups.firstOrNull()?.context?.cover() ?: feed?.sections?.firstOrNull()?.items?.firstOrNull()?.imageUrl),
            browsable("recents", context.getString(R.string.aa_recents),
                groups.firstOrNull()?.context?.cover()),
            browsable("browse", context.getString(R.string.aa_browse),
                categories.firstOrNull()?.images?.firstOrNull()?.url
                    ?: feed?.sections?.firstOrNull()?.items?.firstOrNull()?.imageUrl),
            browsable("library", context.getString(R.string.aa_library),
                library.firstOrNull { it.coverUri.isNotBlank() }?.coverUri),
        )
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
        // Play-all row (see tracksOf): play the list's first playable
        // track through the exact single-song path below, so the queue,
        // liked registration and song state behave as if the first song
        // had been tapped. Stock never emits this scheme, so this branch
        // is inert on the Stock path.
        if (mediaId.startsWith("playall:")) {
            val contextUri = mediaId.removePrefix("playall:")
            val first = pages[contextUri]?.tracks?.firstOrNull { it.url.isNotBlank() } ?: return
            play("t|$contextUri|${first.url}")
            return
        }
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

    /**
     * A track list for layout A/B (playlists, albums, Liked Songs, radio):
     * a Play row on top that starts the list, then the songs. The car has
     * no list-level play affordance, so without the row the user must tap
     * the first song. The row's icon is the standard play icon generated
     * by the artwork provider (see [ArtworkProvider.playIconUri]), so it is
     * always available: every non-empty list gets the row. An empty list
     * gets no row — there is no first track to play. Stock never uses
     * this (see stockTracksOf).
     */
    private fun tracksOf(context: Context, page: ContextPageModel?): List<MediaItem> {
        page ?: return emptyList()
        pages[page.uri] = page
        val tracks = page.tracks.take(MAX_ITEMS)
        val playAll = if (tracks.isNotEmpty()) listOf(
            MediaItem(
                MediaDescriptionCompat.Builder()
                    .setMediaId("playall:${page.uri}")
                    .setTitle(context.getString(R.string.notif_action_play))
                    .setIconUri(ArtworkProvider.playIconUri())
                    .build(),
                MediaItem.FLAG_PLAYABLE
            )
        ) else emptyList()
        return playAll + tracks.map { playable(page.uri, it) }
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
            .setIconUri(ArtworkProvider.uriForLayout(cover)).setExtras(extras).build()
        return MediaItem(d, MediaItem.FLAG_BROWSABLE)
    }

    private fun playable(contextUri: String, s: SongsModel, grid: Boolean = false): MediaItem {
        val builder = MediaDescriptionCompat.Builder().setMediaId("t|$contextUri|${s.url}")
            .setTitle(s.title).setSubtitle(s.singer).setIconUri(ArtworkProvider.uriForLayout(s.coverUri))
        if (grid) builder.setExtras(Bundle().apply {
            putInt("android.media.browse.CONTENT_STYLE_PLAYABLE_HINT", 2)
        })
        return MediaItem(builder.build(), MediaItem.FLAG_PLAYABLE)
    }

    /**
     * Stock: upstream's tree verbatim (beta.7's, unchanged in v2.1.0), built with
     * upstream's builders and upstream's strict artwork hosts — no fork code runs
     * on this path. The root is static and answers at once, so the car draws its
     * four folders while the page loads; every other node needs the session, and
     * waits for it instead of returning an empty list, which Android Auto keeps
     * as the folder's content until it unsubscribes (issue #41, cold start).
     */
    private suspend fun stockChildren(context: Context, parentId: String): List<MediaItem> {
        if (parentId == ROOT) return listOf(
            stockBrowsable("liked", context.getString(R.string.collection_liked_songs_title), null, grid = false),
            stockBrowsable("playlists", context.getString(R.string.aa_playlists), null, grid = false),
            stockBrowsable("albums", context.getString(R.string.aa_albums), null, grid = false),
            stockBrowsable("artists", context.getString(R.string.aa_artists), null, grid = false),
        )
        if (!awaitPlayer()) throw AudioNotReady("browse $parentId")
        return when {
            parentId == "playlists" || parentId == "albums" -> {
                val wantPlaylists = parentId == "playlists"
                val entries = (firstResult { Graph.api.getLibrary() } ?: emptyList())
                    .filter { it.isPlaylist == wantPlaylists && it.spotifyId != com.music.spotui.data.api.Api.HomeCache.LIKED_SONGS_ID }
                entries.take(MAX_ITEMS).map { e ->
                    stockBrowsable(if (wantPlaylists) "pl:${e.spotifyId}" else "al:${e.spotifyId}", e.name, e.coverUri, subtitle = e.subtitle)
                }
            }
            parentId == "artists" -> Graph.api.getFollowedArtists().filter { it.id.isNotBlank() }.take(MAX_ITEMS).map { a ->
                stockBrowsable("ar:${a.id}|${a.name}", a.name, a.coverUri)
            }
            parentId == "liked" -> stockTracksOf(firstResult { Graph.api.getLikedPage() })
            parentId.startsWith("pl:") -> stockTracksOf(firstResult { Graph.api.getPlaylistPage(parentId.removePrefix("pl:")) })
            parentId.startsWith("al:") -> stockTracksOf(firstResult { Graph.api.getAlbumPage(parentId.removePrefix("al:")) })
            parentId.startsWith("ar:") -> {
                val (id, name) = parentId.removePrefix("ar:").split("|", limit = 2).let { it[0] to it.getOrElse(1) { "" } }
                val overview = firstResult { Graph.api.getArtistOverview(name, id) }
                overview?.topTracks?.map { stockPlayable("spotify:artist:$id", it.song) }.orEmpty()
            }
            else -> emptyList()
        }
    }

    private suspend fun stockSearch(query: String): List<MediaItem> {
        if (!awaitPlayer()) throw AudioNotReady("search")
        val r = firstResult { Graph.api.searchEverything(query) } ?: return emptyList()
        return r.songs.take(20).map { stockPlayable("", it) } +
            r.albums.filter { it.spotifyId.isNotBlank() }.take(8).map { stockBrowsable("al:${it.spotifyId}", it.name, it.coverUri, subtitle = it.artists) }
    }

    private fun stockTracksOf(page: ContextPageModel?): List<MediaItem> {
        page ?: return emptyList()
        pages[page.uri] = page
        return page.tracks.take(MAX_ITEMS).map { stockPlayable(page.uri, it) }
    }

    private fun stockBrowsable(id: String, title: String, cover: String?, subtitle: String = "", grid: Boolean = true): MediaItem {
        val extras = Bundle().apply {
            putInt("android.media.browse.CONTENT_STYLE_BROWSABLE_HINT", if (grid) 2 else 1)
            putInt("android.media.browse.CONTENT_STYLE_PLAYABLE_HINT", 1)
        }
        val d = MediaDescriptionCompat.Builder().setMediaId(id).setTitle(title).setSubtitle(subtitle)
            .setIconUri(ArtworkProvider.uriFor(cover)).setExtras(extras).build()
        return MediaItem(d, MediaItem.FLAG_BROWSABLE)
    }

    private fun stockPlayable(contextUri: String, s: SongsModel): MediaItem {
        val d = MediaDescriptionCompat.Builder().setMediaId("t|$contextUri|${s.url}")
            .setTitle(s.title).setSubtitle(s.singer).setIconUri(ArtworkProvider.uriFor(s.coverUri)).build()
        return MediaItem(d, MediaItem.FLAG_PLAYABLE)
    }
}

/**
 * Cover art for Android Auto, which only shows local (content://) artwork: serves an
 * image from Spotify's image CDN through Glide's disk cache. Other hosts are refused.
 */
class ArtworkProvider : android.content.ContentProvider() {
    companion object {
        private val AUTHORITY = app.myspoti.engine.BuildConfig.APPLICATION_ID + ".artwork"

        /** Upstream's artwork rule, used by the Stock path exactly as shipped. */
        fun uriFor(url: String?): Uri? {
            if (url.isNullOrBlank() || !allowed(url)) return null
            return Uri.Builder().scheme("content").authority(AUTHORITY).appendPath("img").appendQueryParameter("u", url).build()
        }

        private val allowedHosts = setOf("i.scdn.co", "mosaic.scdn.co", "image-cdn-ak.spotifycdn.com", "image-cdn-fa.spotifycdn.com", "thisis-images.spotifycdn.com", "seeded-session-images.scdn.co", "pickasso.spotifycdn.com")

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

        /** Layouts A/B only: the widened artwork rule (any Spotify image CDN subdomain). */
        fun uriForLayout(url: String?): Uri? {
            if (url.isNullOrBlank() || !allowedLayout(url)) return null
            return Uri.Builder().scheme("content").authority(AUTHORITY).appendPath("img").appendQueryParameter("u", url).build()
        }

        /**
         * Only https URLs on Spotify's image CDNs, by suffix: any host that is
         * scdn.co / spotifycdn.com or ends in .scdn.co / .spotifycdn.com. This
         * covers every previously listed host plus new CDN subdomains, whose art
         * used to be refused and rendered broken in the car. The https-only scheme
         * check is what blocks the local-file attack (see [allowed]).
         */
        private fun allowedLayout(url: String): Boolean {
            val u = Uri.parse(url)
            val host = u.host ?: return false
            return u.scheme == "https" &&
                (host == "scdn.co" || host == "spotifycdn.com" ||
                    host.endsWith(".scdn.co") || host.endsWith(".spotifycdn.com"))
        }

        /**
         * The standard play icon for the A/B Play row: a bright green circle
         * (Spotify green) with a black play triangle, on a transparent
         * background. The car cannot use an app drawable directly — every row
         * icon must come through this provider — so the icon is generated
         * here at runtime (see [ArtworkProvider.playIconFile]) instead of
         * fetched from the network. The path is a fixed literal: it takes no
         * query parameter and never derives a file from caller input, so the
         * exported provider cannot be abused to open arbitrary files.
         */
        fun playIconUri(): Uri =
            Uri.Builder().scheme("content").authority(AUTHORITY).appendPath("play").build()
    }

    /** Lock guarding generation of the cached play icon (openFile runs on binder threads). */
    private val playIconLock = Any()

    /**
     * Generates (once, cached under the app cache dir) the 512x512 PNG play
     * icon: transparent background, a #1ED760 circle, and a centered black
     * triangle pointing right whose height is roughly 40% of the circle's
     * diameter. No network and no Glide on this path.
     */
    private fun playIconFile(): java.io.File? {
        val ctx = context ?: return null
        synchronized(playIconLock) {
            val file = java.io.File(ctx.cacheDir, "play-icon.png")
            if (file.exists()) return file
            val size = 512
            val bitmap = android.graphics.Bitmap.createBitmap(size, size, android.graphics.Bitmap.Config.ARGB_8888)
            val canvas = android.graphics.Canvas(bitmap)
            val cx = size / 2f
            val cy = size / 2f
            val radius = size * 0.47f
            val circle = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                color = android.graphics.Color.parseColor("#1ED760")
                style = android.graphics.Paint.Style.FILL
            }
            canvas.drawCircle(cx, cy, radius, circle)
            val h = radius * 2f * 0.4f
            val w = h * 0.8f
            val triangle = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                color = android.graphics.Color.BLACK
                style = android.graphics.Paint.Style.FILL
            }
            val path = android.graphics.Path().apply {
                moveTo(cx - w / 2f, cy - h / 2f)
                lineTo(cx - w / 2f, cy + h / 2f)
                lineTo(cx + w / 2f, cy)
                close()
            }
            canvas.drawPath(path, triangle)
            runCatching {
                java.io.FileOutputStream(file).use { out ->
                    bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out)
                }
            }.onFailure { file.delete() }
            bitmap.recycle()
            return file.takeIf { it.exists() }
        }
    }

    override fun openFile(uri: Uri, mode: String): android.os.ParcelFileDescriptor? {
        // The generated play icon: fixed literal path only, no "u" parameter
        // is read here, so nothing caller-supplied reaches the filesystem.
        if (uri.pathSegments == listOf("play")) {
            val file = playIconFile() ?: return null
            return android.os.ParcelFileDescriptor.open(file, android.os.ParcelFileDescriptor.MODE_READ_ONLY)
        }
        val url = uri.getQueryParameter("u") ?: return null
        if (!allowed(url) && !allowedLayout(url)) return null
        // Bounded: a binder thread must not wait on the network forever.
        val file = runCatching {
            com.bumptech.glide.Glide.with(context!!).asFile().load(url).submit()
                .get(15, java.util.concurrent.TimeUnit.SECONDS)
        }.getOrNull() ?: return null
        return android.os.ParcelFileDescriptor.open(file, android.os.ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun getType(uri: Uri) =
        if (uri.pathSegments == listOf("play")) "image/png" else "image/jpeg"
    override fun onCreate() = true
    override fun query(uri: Uri, p: Array<out String>?, s: String?, a: Array<out String>?, o: String?) = null
    override fun insert(uri: Uri, values: android.content.ContentValues?) = null
    override fun delete(uri: Uri, s: String?, a: Array<out String>?) = 0
    override fun update(uri: Uri, v: android.content.ContentValues?, s: String?, a: Array<out String>?) = 0
}
