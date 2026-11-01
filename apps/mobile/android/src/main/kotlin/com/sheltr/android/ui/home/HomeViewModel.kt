package com.sheltr.android.ui.home

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import com.sheltr.android.R

/**
 * One hazard the Home screen's grid can show. [hazard] is a `DisasterType` enum name -- the same strings as
 * `tools/publisher`'s `DisasterType`,
 * used verbatim as an entry of `RetrievalQuery.activeHazards` once Search (Task 3) passes a selected hazard
 * along to the shared module's `Retriever`. This module can't depend on the JVM-only publisher module, so the
 * twelve names are copied here by hand -- the same source-of-truth convention the retrieval-robustness plan's
 * `HazardVocabulary` already uses.
 */
data class HazardTile(
    val hazard: String,
    @DrawableRes val icon: Int,
    @StringRes val label: Int,
)

/**
 * Home screen state and user-intent handling.
 *
 * Holds no navigation logic itself: [onSearchSubmit] and [onHazardTapped] only validate/pass through the
 * user's input to the [onNavigate] callback the screen supplies. The screen owns the `NavController` and
 * decides what navigating actually means (see `HomeScreen`, wired into `SheltrNavHost`).
 */
class HomeViewModel : ViewModel() {

    val hazardTiles: List<HazardTile> = listOf(
        HazardTile("EARTHQUAKE", R.drawable.ic_hazard_earthquake, R.string.hazard_earthquake),
        HazardTile("FLOOD", R.drawable.ic_hazard_flood, R.string.hazard_flood),
        HazardTile("TROPICAL_CYCLONE", R.drawable.ic_hazard_tropical_cyclone, R.string.hazard_tropical_cyclone),
        HazardTile("TORNADO", R.drawable.ic_hazard_tornado, R.string.hazard_tornado),
        HazardTile("TSUNAMI", R.drawable.ic_hazard_tsunami, R.string.hazard_tsunami),
        HazardTile("WILDFIRE", R.drawable.ic_hazard_wildfire, R.string.hazard_wildfire),
        HazardTile("EXTREME_HEAT", R.drawable.ic_hazard_extreme_heat, R.string.hazard_extreme_heat),
        HazardTile("WINTER_STORM", R.drawable.ic_hazard_winter_storm, R.string.hazard_winter_storm),
        HazardTile("LANDSLIDE", R.drawable.ic_hazard_landslide, R.string.hazard_landslide),
        HazardTile(
            "GLACIAL_LAKE_FLOOD",
            R.drawable.ic_hazard_glacial_lake_flood,
            R.string.hazard_glacial_lake_flood,
        ),
        HazardTile("VOLCANO", R.drawable.ic_hazard_volcano, R.string.hazard_volcano),
        HazardTile("DROUGHT", R.drawable.ic_hazard_drought, R.string.hazard_drought),
    )

    /** Trims [text]; blank input is "nothing to search" and does not invoke [onNavigate]. */
    fun onSearchSubmit(text: String, onNavigate: (String) -> Unit) {
        val trimmed = text.trim()
        if (trimmed.isNotEmpty()) onNavigate(trimmed)
    }

    /** [hazard] is always one of [hazardTiles]' fixed, already-valid names; passed straight through. */
    fun onHazardTapped(hazard: String, onNavigate: (String) -> Unit) {
        onNavigate(hazard)
    }
}
