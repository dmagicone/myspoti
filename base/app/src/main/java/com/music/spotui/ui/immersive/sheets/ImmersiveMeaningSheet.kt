package com.music.spotui.ui.immersive.sheets

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.dp
import app.myspoti.engine.R
import com.music.spotui.data.api.GeniusMeaning
import com.music.spotui.ui.components.readerLanguage
import com.music.spotui.ui.components.rememberShown
import com.music.spotui.ui.components.showOriginal
import com.music.spotui.ui.immersive.Immersive
import com.music.spotui.ui.immersive.components.immersiveClickable

/** What the sheet has to show: still being read, nothing on Genius, Genius out of reach, or the meaning. */
private sealed interface MeaningLoad {
    data object Loading : MeaningLoad
    data object None : MeaningLoad
    data object Failed : MeaningLoad
    class Ready(val meaning: GeniusMeaning.Meaning) : MeaningLoad
}

/**
 * Genius's words on the Immersive glass sheet, from the same [GeniusMeaning] the Classic look
 * reads (nothing new on the network: the look-up begun when the song started, or the cached one).
 * [noteIndex] set: the explanation of one lyric line; null: the song as a whole ("About").
 * The text is read off the main thread and keyed on [trackId].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImmersiveMeaningSheet(
    trackId: String,
    title: String,
    artist: String,
    noteIndex: Int?,
    onDismiss: () -> Unit,
) {
    val load by produceState<MeaningLoad>(MeaningLoad.Loading, trackId, title, artist) {
        val ready = GeniusMeaning.current.value?.takeIf { it.first == trackId }?.second
            ?: GeniusMeaning.cached(title, artist)
        value = if (ready != null) MeaningLoad.Ready(ready) else try {
            // find() runs on IO and keeps its own small cache; it throws when Genius can't be reached.
            GeniusMeaning.find(title, artist)?.let { MeaningLoad.Ready(it) } ?: MeaningLoad.None
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            MeaningLoad.Failed
        }
    }
    ImmersiveGlassSheet(onDismiss = onDismiss) {
        val maxH = LocalConfiguration.current.screenHeightDp.dp * 0.7f
        Column(Modifier.fillMaxWidth().heightIn(max = maxH).verticalScroll(rememberScrollState()).navigationBarsPadding()) {
            when (val l = load) {
                MeaningLoad.Loading -> {
                    ImmersiveSheetTitle(stringResource(R.string.lyrics_meaning_title), subtitle = "$title · $artist")
                    Row(Modifier.fillMaxWidth().padding(vertical = 28.dp), horizontalArrangement = Arrangement.Center) {
                        CircularProgressIndicator(color = Immersive.TextSecondary, strokeWidth = 2.dp, modifier = Modifier.height(24.dp))
                    }
                }
                MeaningLoad.None -> {
                    ImmersiveSheetTitle(stringResource(R.string.lyrics_meaning_title), subtitle = "$title · $artist")
                    ImmersiveSheetNote(stringResource(R.string.immersive_genius_none))
                }
                MeaningLoad.Failed -> {
                    ImmersiveSheetTitle(stringResource(R.string.lyrics_meaning_title), subtitle = "$title · $artist")
                    ImmersiveSheetNote(stringResource(R.string.immersive_genius_error))
                }
                is MeaningLoad.Ready -> MeaningBody(l.meaning, noteIndex)
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun MeaningBody(original: GeniusMeaning.Meaning, noteIndex: Int?) {
    val note = noteIndex?.let { original.notes.getOrNull(it) }
    val shown = rememberShown(original, listOfNotNull(if (note != null) note.explanation else original.about))
    val m = shown.meaning
    val shownNote = noteIndex?.let { m.notes.getOrNull(it) }
    val context = LocalContext.current
    if (shownNote != null) {
        ImmersiveSheetTitle(shownNote.fragment, subtitle = "${m.title} · ${m.artist}")
        ImmersiveSheetDivider()
        LinkedText(shownNote.explanation)
    } else {
        ImmersiveSheetTitle(stringResource(R.string.lyrics_meaning_about), subtitle = "${m.title} · ${m.artist}")
        ImmersiveSheetDivider()
        if (!m.about.isNullOrBlank()) LinkedText(m.about!!)
        else ImmersiveSheetNote(stringResource(R.string.immersive_genius_none))
    }
    Row(Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        when {
            shown.translating -> Text(stringResource(R.string.lyrics_meaning_translating), style = Immersive.Subhead)
            shown.canTranslate -> Text(
                stringResource(if (showOriginal.value) R.string.lyrics_meaning_translate else R.string.lyrics_meaning_original),
                style = Immersive.Subhead.copy(color = Immersive.Text),
                modifier = Modifier.clip(RoundedCornerShape(50))
                    .immersiveClickable(onClick = { showOriginal.value = !showOriginal.value })
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            )
        }
        Spacer(Modifier.weight(1f))
        if (m.url.isNotBlank()) Text(
            stringResource(R.string.immersive_genius_view),
            style = Immersive.BodyStrong.copy(color = LocalImmersiveSheetTint.current.accent),
            modifier = Modifier.clip(RoundedCornerShape(50))
                .immersiveClickable(onClick = {
                    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(m.url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                })
                .padding(horizontal = 12.dp, vertical = 8.dp),
        )
    }
}

private val urlRegex = Regex("""https?://[^\s<>"')]+""")

/** The annotation as body text; the web addresses in it open when tapped. */
@Composable
private fun LinkedText(text: String) {
    val accent = LocalImmersiveSheetTint.current.accent
    val styled: AnnotatedString = buildAnnotatedString {
        var last = 0
        for (hit in urlRegex.findAll(text)) {
            append(text.substring(last, hit.range.first))
            val url = hit.value.trimEnd('.', ',', ';', ':', '!', '?')
            withLink(LinkAnnotation.Url(url, TextLinkStyles(SpanStyle(color = accent, textDecoration = TextDecoration.Underline)))) { append(url) }
            last = hit.range.first + url.length
        }
        append(text.substring(last))
    }
    Text(styled, style = Immersive.Body.copy(color = Immersive.Text), modifier = Modifier.padding(horizontal = 22.dp, vertical = 14.dp))
}
