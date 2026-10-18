package com.sheltr.android.ui.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.sheltr.android.R

/**
 * Sheltr's start screen: a quick-search field and a grid of the twelve hazard types, each a large (>=64dp),
 * icon-plus-label tile (never icon-only -- the Global Constraint on tappable controls).
 *
 * [onHazardTapped] and [onSearchSubmit] are already-validated navigation intents (a known hazard name, or
 * non-blank trimmed search text) -- [HomeViewModel.onHazardTapped]/[HomeViewModel.onSearchSubmit] do that
 * validation before either callback runs. [onSettingsTapped] needs no such validation -- it is a plain
 * navigation intent with no user input to check -- so it is invoked directly, not through [viewModel]. This
 * screen does not navigate itself: [SheltrNavHost][com.sheltr.android.ui.SheltrNavHost] supplies these
 * callbacks and owns the `NavController`.
 */
@Composable
fun HomeScreen(
    onHazardTapped: (String) -> Unit,
    onSearchSubmit: (String) -> Unit,
    onSettingsTapped: () -> Unit = {},
    viewModel: HomeViewModel = viewModel(),
) {
    var searchText by rememberSaveable { mutableStateOf("") }
    val searchHintDescription = stringResource(R.string.home_search_hint_description)
    val settingsLabel = stringResource(R.string.home_settings_button_label)

    Column(
        modifier = Modifier
            .testTag("home-screen")
            .fillMaxSize()
            .padding(16.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.home_title),
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.weight(1f),
            )
            Row(
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .clickable(onClick = onSettingsTapped)
                    .testTag("home-settings-button")
                    .semantics { contentDescription = settingsLabel }
                    .padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Decorative: the visible Text below already carries the accessible label (same pattern as
                // HazardGridItem/SearchScreen's submit button).
                Icon(imageVector = Icons.Default.Settings, contentDescription = null)
                Spacer(Modifier.width(4.dp))
                Text(settingsLabel)
            }
        }
        Spacer(Modifier.heightIn(min = 16.dp))
        TextField(
            value = searchText,
            onValueChange = { searchText = it },
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .testTag("home-search-field")
                .semantics { contentDescription = searchHintDescription },
            placeholder = { Text(stringResource(R.string.home_search_placeholder)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(
                onSearch = {
                    viewModel.onSearchSubmit(searchText) { trimmed -> onSearchSubmit(trimmed) }
                },
            ),
        )
        Spacer(Modifier.heightIn(min = 16.dp))
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 96.dp),
            modifier = Modifier.testTag("home-hazard-grid"),
            contentPadding = PaddingValues(vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(viewModel.hazardTiles) { tile ->
                HazardGridItem(
                    tile = tile,
                    onClick = {
                        viewModel.onHazardTapped(tile.hazard) { hazard -> onHazardTapped(hazard) }
                    },
                )
            }
        }
    }
}

@Composable
private fun HazardGridItem(tile: HazardTile, onClick: () -> Unit) {
    val label = stringResource(tile.label)
    Column(
        modifier = Modifier
            .sizeIn(minWidth = 64.dp, minHeight = 64.dp)
            .clickable(onClick = onClick)
            .semantics { contentDescription = label }
            .padding(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            painter = painterResource(tile.icon),
            // Decorative: the visible Text below already carries this tile's label, and the clickable
            // container above carries the explicit contentDescription TalkBack announces.
            contentDescription = null,
            modifier = Modifier.sizeIn(minWidth = 32.dp, minHeight = 32.dp),
        )
        Spacer(Modifier.heightIn(min = 4.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
        )
    }
}
