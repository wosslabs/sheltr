package com.sheltr.android.ui.answer

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import com.sheltr.android.R
import com.sheltr.shared.retrieval.Citation
import com.sheltr.shared.retrieval.Passage
import com.sheltr.shared.retrieval.RetrievalResult

/**
 * Renders a resolved [RetrievalResult]: on [RetrievalResult.Passages], each passage's text with its [Citation]
 * shown as a visually distinct source block, plus a read-aloud button for the top passage; on
 * [RetrievalResult.Refusal], Sheltr's own localized refusal copy.
 *
 * Deliberately never shows [RetrievalResult.Refusal.MESSAGE] -- the shared module's English constant, shared
 * with the server's own refusal response, not a UI string resource. [R.string.answer_refusal_title] and
 * [R.string.answer_refusal_body] are this app's own localizable wording instead, so a non-English UI locale
 * gets a real translation rather than hardcoded English leaking out of the domain model into the UI.
 *
 * [readAloudAvailable] gates the read-aloud button's `enabled` state but never hides it: a disabled button with
 * [R.string.answer_read_aloud_unavailable] shown alongside it explains why, so a user whose device has no
 * English-capable TTS engine still sees the control and understands why it doesn't work, instead of a silently
 * missing feature.
 *
 * This composable is deliberately stateless ([result]/[onReadAloud]/[readAloudAvailable] are all passed in) so
 * it is testable without a [AnswerViewModel] or a real `TextToSpeech` engine;
 * [com.sheltr.android.ui.SheltrNavHost] is the only caller that wires it to [AnswerViewModel].
 */
@Composable
fun AnswerScreen(
    result: RetrievalResult,
    onReadAloud: () -> Unit,
    readAloudAvailable: Boolean,
) {
    Column(
        modifier = Modifier
            .testTag("answer-screen")
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
    ) {
        when (result) {
            is RetrievalResult.Passages -> PassagesContent(result, onReadAloud, readAloudAvailable)
            RetrievalResult.Refusal -> RefusalContent()
        }
    }
}

@Composable
private fun PassagesContent(
    result: RetrievalResult.Passages,
    onReadAloud: () -> Unit,
    readAloudAvailable: Boolean,
) {
    val readAloudDescription = stringResource(R.string.answer_read_aloud_description)

    Button(
        onClick = onReadAloud,
        enabled = readAloudAvailable,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .testTag("answer-read-aloud-button")
            .semantics { contentDescription = readAloudDescription },
    ) {
        // Decorative: the visible Text below already carries the accessible label (same pattern as
        // SearchScreen's submit button).
        Icon(imageVector = Icons.Default.PlayArrow, contentDescription = null)
        Spacer(Modifier.width(8.dp))
        Text(readAloudDescription)
    }
    if (!readAloudAvailable) {
        Spacer(Modifier.height(4.dp))
        Text(
            text = stringResource(R.string.answer_read_aloud_unavailable),
            style = MaterialTheme.typography.bodySmall,
        )
    }
    Spacer(Modifier.height(16.dp))

    result.passages.forEachIndexed { index, passage ->
        Text(passage.chunk.content, style = MaterialTheme.typography.bodyLarge)
        Spacer(Modifier.height(8.dp))
        CitationBlock(passage)
        if (index != result.passages.lastIndex) {
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun CitationBlock(passage: Passage) {
    val citation = Citation.of(passage)
    val document = passage.document
    val uriHandler = LocalUriHandler.current

    Card(modifier = Modifier.fillMaxWidth().testTag("answer-citation")) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = stringResource(R.string.answer_source_label),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
            )
            Text(citation.sourceTitle, style = MaterialTheme.typography.bodyMedium)
            Text(document.agency, style = MaterialTheme.typography.bodySmall)
            Text(
                text = stringResource(R.string.answer_source_published, document.publishedDate),
                style = MaterialTheme.typography.bodySmall,
            )
            citation.pageNumber?.let { page ->
                Text(
                    text = stringResource(R.string.answer_source_page, page),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Text(
                text = citation.sourceUrl,
                style = MaterialTheme.typography.bodySmall.copy(textDecoration = TextDecoration.Underline),
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .clickable { uriHandler.openUri(citation.sourceUrl) }
                    .testTag("answer-source-link"),
            )
        }
    }
}

@Composable
private fun RefusalContent() {
    Text(
        text = stringResource(R.string.answer_refusal_title),
        style = MaterialTheme.typography.headlineSmall,
    )
    Spacer(Modifier.height(8.dp))
    Text(
        text = stringResource(R.string.answer_refusal_body),
        style = MaterialTheme.typography.bodyLarge,
    )
}
