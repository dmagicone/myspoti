# Web Player feature inventory

What open.spotify.com offers, and what `:api` exposes for it. One row per feature.

Status:
- **exposed**: in `:api` before this change, through a mechanism that was captured or already used.
- **added now**: new in `:api` with this change. The write path follows the same mechanism as a verified
  neighbour, but was not run against a live page; see the note on the row.
- **needs capture**: the feature exists, but its operation or endpoint is not known well enough to call
  (no response shape, no variables, or no name in the bundle). Not guessed.
- **not possible**: the Web Player does not offer it, or it is refused for this account.
- **needs Premium**: Spotify limits it to Premium. Skipped. No row uses it yet, because no row was verified as Premium-only.
- **not in :api**: documented under `docs/api`, but not exposed by the `:api` interfaces yet.

Operation names are GraphQL operations (`gql`, sent by name and hash from `gql-ops.json`) unless marked
as a page service (`service: Name.method`, through the bridge) or an HTTP endpoint.

## Playback

| Feature | Status | Operation / endpoint |
|---|---|---|
| Play a context, from a track, index or position | exposed | `player.play` (bridge) |
| Play a sorted playlist in a chosen order | exposed | `Player.play(order)` |
| Pause, resume, seek, next, previous | exposed | `player.*` (bridge) |
| Shuffle, repeat | exposed | `player.setShuffle`, `player.setRepeat` (bridge) |
| Queue: add, remove, clear, play next, reorder, jump to | exposed | `queue.*` (bridge) |
| Volume | exposed | `volume.get`, `volume.set` (bridge) |
| Podcast speed | exposed | `Player.setSpeed` (bridge) |
| Smart shuffle | needs capture | `smartShuffle` (not wired; its Premium status is not verified) |

## Search

| Feature | Status | Operation / endpoint |
|---|---|---|
| Top results, tracks, artists, albums, playlists | exposed | `searchTopResultsList`, `searchTracks`, `searchArtists`, `searchAlbums`, `searchPlaylists` |
| Shows, episodes, audiobooks, genres | exposed | `searchPodcasts`, `searchFullEpisodes`, `searchAudiobooks`, `searchGenres` |
| Profile (user) search | exposed | `searchUsers` (`Search.users`) |
| Suggestions while typing | exposed | `searchSuggestions` |
| Recent searches, clear them | exposed | `recentSearches`, `removeRecentSearches` |

## Catalog and artists

| Feature | Status | Operation / endpoint |
|---|---|---|
| Track, album, album tracks | exposed | `getTrack`, `getAlbum`, `queryAlbumTracks` |
| Artist page (overview, top tracks, popular releases, biography) | exposed | `queryArtistOverview` |
| Full discography (all types, newest first) | exposed | `queryArtistDiscographyAll` |
| Discography split into albums, singles, compilations | needs capture | `queryArtistDiscographyAlbums`, `...Singles`, `...Compilations` (variables known; response shape not documented) |
| Appears on | exposed | `queryArtistAppearsOn` |
| Fans also like (related artists) | exposed | `queryArtistRelated` |
| Similar albums ("more like this") | exposed | `similarAlbumsBasedOnThisTrack` |
| Artist playlists | needs capture | `queryArtistPlaylists` (no response shape documented) |
| Featuring | needs capture | `queryArtistFeaturing` (no response shape documented) |
| Discovered on | needs capture | `queryArtistDiscoveredOn` |
| Artist "About" modal (more than the biography) | needs capture | `queryArtistAboutModal` |
| Artist concerts | needs capture | `ArtistConcerts`, `concertFeed`, `concert`, `venue` (variables known for some; no response shape) |
| Related videos (artist) | needs capture | `queryArtistRelatedVideos` (variables known; no response shape) |
| Album merch | needs capture | `queryAlbumMerch` (documented as merch only, no response shape) |
| Monthly listeners, followers, verified badge | exposed | `queryArtistOverview` |
| ISRC of a track | exposed | spclient `metadata/4/track/{hex}` |
| Track credits | exposed | `queryTrackCreditsGroupedModal` |
| Lyrics, line and word sync, colours | exposed | spclient `color-lyrics/v2` |
| Canvas (looping video) | exposed | `canvas` |
| Track radio, artist radio | exposed | spclient `inspiredby-mix/v2/seed_to_playlist` |
| Track page "More like this" albums | exposed | `similarAlbumsBasedOnThisTrack` |
| Now-playing supplemental sections (related videos, related tracks) | needs capture | `npvV2TrackSupplementalSections`, `queryNpvArtist` (variables known; response shape not documented) |

## Your Library

| Feature | Status | Operation / endpoint |
|---|---|---|
| Library list with filters (playlists, artists, albums, podcasts, audiobooks) | exposed | `libraryV3` (`filters`) |
| Library sort: recents, recently added, alphabetical, creator | exposed | `libraryV3` (`order`) |
| Recently played (the Recents list) | exposed | `RecentlyPlayedAPI.getContexts` (service); `recents` not used |
| Recently played tracks | needs capture | `recents` (variables known; response not documented) |
| Liked Songs: list, add, remove, check | exposed | `fetchLibraryTracks` (list); `Library.add/remove` (`library.add`, `library.remove`); `Library.contains` |
| Follow or save artists, albums, shows, playlists | exposed | `library.add`, `library.remove` (bridge `LibraryAPI`); `Library.contains` |
| Pin and unpin items | exposed | `pinLibraryItem`, `unpinLibraryItem` |
| Folders: create (top level or inside a folder), rename, delete | exposed | `RootlistAPI.createFolder`, `renameFolder`, `removeFolder` (service) |
| Move playlists and folders into a folder | exposed | `RootlistAPI.move`, `moveFolder` (service) |
| Library changes (this device, and others through dealer pushes) | exposed | `Library.changes` flow |
| Filter by a text query inside the library | needs capture | `libraryV3` `textFilter` is sent empty; the Web Player's own search box is not captured |
| Custom order of your own library (drag) | needs capture | rootlist `ops` order (not captured) |

## Playlists

| Feature | Status | Operation / endpoint |
|---|---|---|
| Playlist page and rows (paged, with row ids) | exposed | `fetchPlaylist`, `fetchPlaylistContents` |
| Playlist metadata and permissions | not in :api | `fetchPlaylistMetadata`, `playlistPermissions` (shapes in `LIBRARY.md`) |
| My playlists, playlists I can edit | exposed | rootlist (`RootlistAPI.getContents`); `editablePlaylists` documented |
| Create a playlist (top level or inside a folder) | exposed | `RootlistAPI.createPlaylist` (service) |
| Rename | exposed | `PlaylistAPI.updateDetails` (service), checked with `getMetadata` |
| Description | exposed | `PlaylistAPI.updateDetails` |
| Public or private | added now | `PlaylistAPI.updateDetails` with `public`, checked with `getMetadata`. Not run live: the read-back may not return the field |
| Collaborative | added now | `PlaylistAPI.updateDetails` with `collaborative`, same caveat as public |
| Cover image upload and removal | needs capture | not captured |
| Add tracks | exposed | `addToPlaylist` |
| Remove tracks | exposed | `removeFromPlaylist` (by row uid) |
| Reorder tracks | exposed | `moveItemsInPlaylist` (`BOTTOM_OF_PLAYLIST` and `BEFORE_UID`) |
| Hide a song in a playlist (Spotify's "Hide in this playlist") | not possible | the menu item calls `FeedbackAPI.addContextTrackBan(contextUri, trackUri)`, which the Web Player's `FeedbackAPI` leaves unimplemented (throws "Method not implemented"; `getCapabilities()` is `{canBan:false}`), so the item never shows there |
| Add a whole playlist or album to another playlist, with the duplicate dialog | added now | the page reads the target (`getContents`), splits new from already-there songs and asks ("Add all" / "Add new ones", or "Add anyway" / "Don't add"), then `PlaylistAPI.add`; here `PlaylistMerge` + `addToPlaylist` in 100-song requests |
| Sort the playlist (title, artist, album, date added, duration) with ascending or descending | added now | client side: `Playlists.sortedTracks` reads every page and sorts. The server has no sort parameter |
| Playlist order as the playback order | exposed | `Player.play(order)` |
| Delete a playlist | exposed | rootlist `REM` (`Playlists.delete`); for owned playlists the Web Player may do more, not confirmed |
| Follow and unfollow a playlist | exposed | rootlist changes (`RootlistAPI.add/remove`) |
| Recommended songs under a playlist | exposed | spclient `playlistextender/extendp` |

## Podcasts and audiobooks

| Feature | Status | Operation / endpoint |
|---|---|---|
| Show page, episodes, episode, chapters | exposed | `queryShowMetadataV2`, `queryPodcastEpisodes`, `getEpisodeOrChapter`, `queryBookChapters` |
| Audiobook page | exposed | `queryShowMetadataV2` (union), `getAudiobooksMetadata` not used |
| Follow or unfollow a show | exposed | `library.add`, `library.remove` |
| Saved episodes (Your Episodes) | exposed | `CollectionPlatformAPI.get('listenlater')` |
| Save or unsave an episode | added now | `CollectionPlatformAPI.add/remove('listenlater')`, the set the list reads. Not run live |
| Played state of episodes | exposed | episode `playedState` in the page data |

## Users and social

| Feature | Status | Operation / endpoint |
|---|---|---|
| Me (account, country, plan) | exposed | `profileAttributes`, `accountAttributes` |
| Public profile: name, avatar, follower and following counts, public playlists | exposed | spclient `user-profile-view/v3/profile/{id}` |
| Follow and unfollow users, check follow state | exposed | `followUsers`, `unfollowUsers`, `isFollowingUsers` |
| Followers list and following list of a user | needs capture | no operation or endpoint captured |
| Top artists and tracks (per time range) | exposed | `userTopContent`; `AffinityInput.timeRange` is `SHORT_TERM` (~4 weeks), `MID_TERM` (~6 months), `LONG_TERM` (~1 year), `MEDIUM_TERM` is refused; `sortBy` `AFFINITY`/`PLAY_COUNT`/`LAST_PLAYED`; ranks and `totalCount` only, no play counts |
| Friend activity | exposed (spclient) | the Web Player's `BuddyFeedAPI` is a stub (`isSupported: false`), but the desktop feed's `GET presence-view/v1/buddylist` (guc-spclient, then spclient.wg) answers the page token on both hosts (200, checked 2026-10-23): `friends[] {timestamp, user{uri,name,imageUrl}, track{uri,name,imageUrl,album,artist,context{uri,name,index}}}`; "now" = played within 15 min |
| Minutes listened, play counts, top-listener / early-fan badges | not exposed | the Web Player's listening-stats route only shows an "open the app" modal, and `queryArtistOverview` has no per-user fan data; Myspoti counts minutes on the phone (Recap) and shows "your #N artist" from `userTopContent` |
| Private session | not possible | the Web Player reports `isSupported: false`; `setPrivateSession` throws |
| Comments and reactions on entities | needs capture | `getCommentsForEntity`, `addComment`, `addCommentReaction` and others (not wired) |

## Taste and preferences

| Feature | Status | Operation / endpoint |
|---|---|---|
| Hide a track from recommendations (taste profile exclusion) | exposed | `CollectionPlatformAPI.add/remove('ignoreinrecs')`; check `collection/v2/contains` |
| Don't play this artist | added now | `CollectionPlatformAPI.add/remove('artistban')`, same mechanism as the taste exclusion; check `collection/v2/contains` with set `artistban`. Not run live |
| Home filters (music, podcasts, audiobooks) | exposed | `home` (`facet`) |

## Home and browse

| Feature | Status | Operation / endpoint |
|---|---|---|
| Home feed and shelves | exposed | `home` |
| Made for you and blends | exposed as shelves of the home feed, not checked one by one | `home` |
| What's new (from followed artists) | exposed | `queryWhatsNewFeed` |
| Browse categories and category pages | exposed | `browseAll`, `browsePage` |
| Country hubs | needs capture | `countryHubsPage`, `countryHubContent` |

## Devices and Jam

| Feature | Status | Operation / endpoint |
|---|---|---|
| Connect devices: list, transfer, pull here | exposed | `ConnectDevicesAPI.getDevices`, `ConnectTransferAPI` (service) |
| Jam: start, join, leave, end, invite link, remove member, queue-only | exposed (restricted) | `SocialConnectAPI` (service). Free accounts get `RESTRICTED`, so start is refused |
| Jam on a free account | not possible | Spotify answers `RESTRICTED` |

## Other

| Feature | Status | Operation / endpoint |
|---|---|---|
| Ads | out of scope | the host blocks ad audio (`AdBlock`); no API surface |
| Concert location settings | needs capture | `saveLocation`, `deleteLocation`, `concertLocationsByLatLon` (not wired) |
| Promo codes | needs capture | `requestPromoCode` (not wired) |
