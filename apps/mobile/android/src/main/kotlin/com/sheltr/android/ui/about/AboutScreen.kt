package com.sheltr.android.ui.about

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.sheltr.android.R
import com.sheltr.shared.store.StoredDocument

/**
 * What the app is, that it is not an official warning system, what it does with data, and the source and licence
 * of every installed document. The document list comes from the installed bundle, so it stays accurate when the
 * corpus changes.
 */
@Composable
fun AboutScreen(documents: List<StoredDocument>) {
    Column(
        modifier = Modifier
            .testTag("about-screen")
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
    ) {
        Text(stringResource(R.string.about_title), style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.heightIn(min = 12.dp))
        Text(stringResource(R.string.about_description))

        Section(R.string.about_disclaimer_title, "about-disclaimer") {
            Text(stringResource(R.string.about_disclaimer))
        }
        Section(R.string.about_privacy_title, "about-privacy") {
            Text(stringResource(R.string.about_privacy))
        }
        Section(R.string.about_sources_title, "about-sources") {
            if (documents.isEmpty()) {
                Text(stringResource(R.string.about_sources_empty))
            } else {
                Text(stringResource(R.string.about_sources_intro))
                for (document in documents) {
                    Spacer(Modifier.heightIn(min = 12.dp))
                    SourceEntry(document)
                }
            }
        }
        Spacer(Modifier.heightIn(min = 24.dp))
        Text(stringResource(R.string.about_code_licence), style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun Section(titleRes: Int, tag: String, content: @Composable () -> Unit) {
    Spacer(Modifier.heightIn(min = 24.dp))
    Column(Modifier.testTag(tag)) {
        Text(
            stringResource(titleRes),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.semantics { heading() },
        )
        Spacer(Modifier.heightIn(min = 8.dp))
        content()
    }
}

@Composable
private fun SourceEntry(document: StoredDocument) {
    Column(Modifier.testTag("about-source-${document.id}")) {
        Text(document.title, style = MaterialTheme.typography.titleSmall)
        Text(
            stringResource(R.string.about_source_details, document.agency, document.publishedDate, document.country ?: "GLOBAL"),
            style = MaterialTheme.typography.bodySmall,
        )
        Text(stringResource(R.string.about_source_licence, document.licence), style = MaterialTheme.typography.bodySmall)
        Text(stringResource(R.string.about_source_link, document.sourceUri), style = MaterialTheme.typography.bodySmall)
    }
}
