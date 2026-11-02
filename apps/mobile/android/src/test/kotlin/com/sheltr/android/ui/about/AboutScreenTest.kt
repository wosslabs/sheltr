package com.sheltr.android.ui.about

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import com.sheltr.android.R
import com.sheltr.shared.store.StoredDocument
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [30])
class AboutScreenTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private fun string(id: Int, vararg args: Any) = compose.activity.getString(id, *args)

    private val document = StoredDocument(
        id = "doc-1",
        title = "Prepare",
        agency = "UK Government",
        publishedDate = "2024-02-02",
        licence = "Open Government Licence v3.0",
        country = "GB",
        hazards = listOf("FLOOD"),
        sourceUri = "https://prepare.campaign.gov.uk/",
        sourceType = "html",
        checksum = "c",
        fileChecksum = "f",
    )

    @Test
    fun showsDisclaimerAndPrivacyStatement() {
        compose.setContent { AboutScreen(documents = emptyList()) }

        compose.onNodeWithText(string(R.string.about_disclaimer_title)).assertIsDisplayed()
        compose.onNodeWithText(string(R.string.about_disclaimer)).assertIsDisplayed()
        compose.onNodeWithText(string(R.string.about_privacy_title)).assertIsDisplayed()
        compose.onNodeWithText(string(R.string.about_privacy)).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun listsTitleAgencyLicenceAndOriginalLinkOfEveryInstalledDocument() {
        compose.setContent { AboutScreen(documents = listOf(document)) }

        compose.onNodeWithTag("about-source-doc-1").performScrollTo()
        compose.onNodeWithText("Prepare").assertIsDisplayed()
        compose.onNodeWithText(string(R.string.about_source_details, "UK Government", "2024-02-02", "GB")).assertIsDisplayed()
        compose.onNodeWithText(string(R.string.about_source_licence, "Open Government Licence v3.0")).assertIsDisplayed()
        compose.onNodeWithText(string(R.string.about_source_link, "https://prepare.campaign.gov.uk/")).assertIsDisplayed()
    }

    @Test
    fun saysSoWhenNoContentIsInstalled() {
        compose.setContent { AboutScreen(documents = emptyList()) }

        compose.onNodeWithText(string(R.string.about_sources_empty)).performScrollTo().assertIsDisplayed()
    }
}
