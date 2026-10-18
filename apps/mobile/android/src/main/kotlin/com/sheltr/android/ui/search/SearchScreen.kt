package com.sheltr.android.ui.search

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.sheltr.android.R
import com.sheltr.shared.retrieval.RetrievalResult

/**
 * Maps a `DisasterType` name (see [com.sheltr.android.ui.home.HomeViewModel.HazardTile]) to the same localized
 * label string resource Home's hazard grid uses, so this screen's chip shows "Flood" rather than the raw enum
 * constant "FLOOD" when a user arrives here from a Home hazard tap.
 *
 * Deliberately duplicated from `HomeViewModel.hazardTiles` rather than reused directly: this screen only needs
 * the hazard-name -> label half of that list (not the icon or the ViewModel itself), and importing Task 2's
 * ViewModel here purely for a string lookup would be a worse coupling than twelve duplicated map entries. If a
 * thirteenth hazard is ever added, both this map and `HomeViewModel.hazardTiles` must be updated together --
 * the same constraint `HomeViewModel`'s own doc comment already documents for its hand-copied names.
 */
private val hazardLabels: Map<String, Int> = mapOf(
    "EARTHQUAKE" to R.string.hazard_earthquake,
    "FLOOD" to R.string.hazard_flood,
    "TROPICAL_CYCLONE" to R.string.hazard_tropical_cyclone,
    "TORNADO" to R.string.hazard_tornado,
    "TSUNAMI" to R.string.hazard_tsunami,
    "WILDFIRE" to R.string.hazard_wildfire,
    "EXTREME_HEAT" to R.string.hazard_extreme_heat,
    "WINTER_STORM" to R.string.hazard_winter_storm,
    "LANDSLIDE" to R.string.hazard_landslide,
    "GLACIAL_LAKE_FLOOD" to R.string.hazard_glacial_lake_flood,
    "VOLCANO" to R.string.hazard_volcano,
    "DROUGHT" to R.string.hazard_drought,
)

/**
 * Sheltr's search screen: a pre-focused text field, an optional dismissible chip for a hazard carried over from
 * a Home hazard tap, and a submit button that runs the query against the shared module's [RetrieverPort].
 *
 * [initialHazard]/[initialQuery] come from [com.sheltr.android.ui.SheltrNavHost]'s Search route's nav
 * arguments (Task 2's `Route.Search.destination(hazard, query)`); applied once, on first composition, to
 * [viewModel]. [viewModel] is always already constructed (with the real `country` and [RetrieverPort]) by
 * `SheltrNavHost` -- unlike `HomeScreen`'s trivial no-arg `HomeViewModel`, `SearchViewModel` needs constructor
 * arguments a default `viewModel()` call can't supply, so this screen takes it as a required parameter instead
 * of reaching for a singleton or a composition-local.
 *
 * [onNavigateToAnswer] receives the [RetrievalResult] once [viewModel] finishes retrieving it, plus the exact
 * text/hazard that produced it (see [SearchViewModel.onSubmit]'s doc comment for why both are needed, not just
 * the result); this screen does not navigate itself (same convention as `HomeScreen`) -- `SheltrNavHost`
 * supplies this callback and decides what "navigate to Answer" means (see that file's
 * `Route.Answer`/`RetrievalResultHolder` doc comments for the full mechanism).
 */
@Composable
fun SearchScreen(
    onNavigateToAnswer: (result: RetrievalResult, submittedText: String, submittedHazard: String?) -> Unit,
    viewModel: SearchViewModel,
    initialHazard: String? = null,
    initialQuery: String? = null,
) {
    val query by viewModel.query.collectAsState()
    val activeHazard by viewModel.activeHazard.collectAsState()
    val focusRequester = remember { FocusRequester() }
    val submitDescription = stringResource(R.string.search_submit_description)
    val dismissHazardDescription = stringResource(R.string.search_active_hazard_chip_description)

    LaunchedEffect(Unit) {
        initialHazard?.let { viewModel.onHazardSelected(it) }
        initialQuery?.let { viewModel.onQueryChange(it) }
        focusRequester.requestFocus()
    }

    Column(
        modifier = Modifier
            .testTag("search-screen")
            .fillMaxSize()
            .padding(16.dp),
    ) {
        Text(
            text = stringResource(R.string.search_title),
            style = MaterialTheme.typography.headlineSmall,
        )
        Spacer(Modifier.heightIn(min = 16.dp))

        activeHazard?.let { hazard ->
            val hazardLabelRes = hazardLabels[hazard]
            val hazardLabel = hazardLabelRes?.let { stringResource(it) } ?: hazard
            Row(
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .clickable { viewModel.onHazardSelected(null) }
                    .testTag("search-hazard-chip")
                    .semantics { contentDescription = dismissHazardDescription }
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(hazardLabel)
                Spacer(Modifier.width(8.dp))
                // Decorative: the visible Text above already shows the hazard name, and the row's own
                // explicit contentDescription above already explains what tapping this control does.
                Icon(imageVector = Icons.Default.Close, contentDescription = null)
            }
            Spacer(Modifier.heightIn(min = 16.dp))
        }

        TextField(
            value = query,
            onValueChange = viewModel::onQueryChange,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .testTag("search-query-field")
                .focusRequester(focusRequester),
            placeholder = { Text(stringResource(R.string.search_placeholder)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(
                onSearch = { viewModel.onSubmit(onNavigateToAnswer) },
            ),
        )
        Spacer(Modifier.heightIn(min = 16.dp))

        Button(
            onClick = { viewModel.onSubmit(onNavigateToAnswer) },
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .testTag("search-submit-button")
                .semantics { contentDescription = submitDescription },
        ) {
            // Decorative: the visible Text below already carries the accessible label (also set explicitly
            // above, same pattern as HomeScreen's HazardGridItem).
            Icon(imageVector = Icons.Default.Search, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text(submitDescription)
        }
    }
}
