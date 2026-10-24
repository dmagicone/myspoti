package app.myspoti.spotify

import app.myspoti.spotify.bridge.AdBlock
import app.myspoti.spotify.bridge.Bridge
import app.myspoti.spotify.bridge.BridgeHost
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Getting past an ad plays the song after it ([SpotifyPlayer.playOn]). On a shuffled list
 * that goes through `queue.skipTo`, which with an ad due steps onto the ad and lands on
 * the song AFTER the target (see skipPastAd): the target was never put back on this path,
 * so the song after every ad break was lost. It is put back now, as after a skip past an ad.
 */
class AdRecoverySkipTest {
    private class FakeHost : BridgeHost {
        override val messages = MutableSharedFlow<String>(extraBufferCapacity = 64)
        /** Every call the player made: method to its arguments (json text). */
        val calls = CopyOnWriteArrayList<Pair<String, String>>()
        override suspend fun evaluate(script: String) {
            val m = Regex("""Myspoti\.call\((\d+), "([^"]+)", (.*)\)\.catch""", RegexOption.DOT_MATCHES_ALL).find(script) ?: return
            calls += m.groupValues[2] to m.groupValues[3]
            messages.emit("""{"type":"result","id":${m.groupValues[1]},"ok":true,"value":null}""")
        }
        suspend fun post(json: String) = messages.emit(json)
    }

    private lateinit var scope: CoroutineScope
    private lateinit var host: FakeHost
    private lateinit var player: SpotifyPlayer

    @BeforeEach fun up() {
        AdBlock.ahead.value = null
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        host = FakeHost()
        player = SpotifyPlayer(Bridge(host, scope), scope)
        // A message posted before the bridge listens is lost (a slow CI machine): wait for it.
        waitFor("bridge listening") { host.messages.subscriptionCount.value > 0 }
    }

    @AfterEach fun down() {
        AdBlock.ahead.value = null
        scope.cancel()
    }

    private fun track(name: String) =
        """{"kind":"track","uri":"spotify:track:$name","uid":"u$name","name":"$name","durationMs":200000}"""

    private fun state(item: String, next: String? = null) =
        """{"type":"state","state":{"track":$item,"contextUri":"spotify:playlist:C","next":${next ?: "null"},"isPaused":false,"isBuffering":false,"shuffle":true,
            |"positionMs":0,"timestamp":${System.currentTimeMillis()},"restrictions":{}}}""".trimMargin()

    private val ad = """{"kind":"ad","uri":"spotify:ad:1","name":"ad","durationMs":30000}"""

    private fun waitFor(what: String, ms: Long = 3_000, cond: () -> Boolean) = runBlocking {
        val end = System.currentTimeMillis() + ms
        while (!cond()) {
            check(System.currentTimeMillis() < end) { "timed out: $what" }
            delay(20)
        }
    }

    private fun calls(method: String) = host.calls.filter { it.first == method }.map { it.second }

    /** Song A playing (shuffle on), B next, C after it; then an ad that plays (muted). */
    private suspend fun adBreakStarts() {
        host.post("""{"type":"ready"}""")
        host.post("""{"type":"queue","queue":{"current":${track("A")},"queued":[],"nextUp":[${track("B")},${track("C")}]}}""")
        host.post(state(track("A"), track("B")))
        waitFor("state A") { player.state.value?.item?.uri == "spotify:track:A" }
        host.post(state(ad))
        waitFor("ad flag") { player.adPlaying.value }
        // The ad recovery: a playing ad is got past after AD_PLAYING_SKIP_MS, the reload
        // has no host here, so it plays on at once — through queue.skipTo (shuffle is on).
        waitFor("the recovery skip to B", ms = 15_000) { calls("queue.skipTo").any { "spotify:track:B" in it } }
    }

    @Test fun `the song after an ad the page overshoots is put back to play next`() = runBlocking {
        adBreakStarts()
        assertTrue(calls("queue.add").isEmpty(), "nothing put back before the page settles: ${host.calls}")
        // The page stepped onto the ad and moved on by itself to the song after the target.
        host.post(state(track("C")))
        waitFor("B put back", ms = 10_000) { calls("queue.add").any { "spotify:track:B" in it } }
    }

    @Test fun `a page that lands on the song after the ad itself needs no put back`() = runBlocking {
        adBreakStarts()
        host.post(state(track("B"), track("C")))
        delay(1_500)
        assertTrue(calls("queue.add").isEmpty(), "B plays: nothing queued behind it: ${host.calls}")
    }
}
