package com.sheltr.android.ui.answer

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.sheltr.android.R
import com.sheltr.shared.retrieval.Passage
import com.sheltr.shared.retrieval.RetrievalResult
import com.sheltr.shared.store.StoredChunk
import com.sheltr.shared.store.StoredDocument
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

private fun passageFixture(): Passage {
    val document = StoredDocument(
        id = "doc-1",
        title = "Household Water Treatment Guide",
        agency = "WHO",
        publishedDate = "2024-03-15",
        licence = "CC-BY",
        country = null,
        hazards = emptyList(),
        sourceUri = "https://example.org/water-guide",
        sourceType = "pdf",
        checksum = "c",
        fileChecksum = "fc",
    )
    val chunk = StoredChunk(
        id = "chunk-1",
        documentId = "doc-1",
        content = "Boil water for at least one minute before drinking.",
        pageNumber = 3,
        position = 0,
    )
    return Passage(chunk, document, score = 2.0, rankScore = 2.0)
}

/**
 * `@Config(application = Application::class)`: same reasoning as [com.sheltr.android.SheltrNavHostTest] --
 * [AnswerScreen] itself takes no ViewModel or Context, but Robolectric still needs an [Application] to host
 * [ComponentActivity] for `createAndroidComposeRule`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class AnswerScreenTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun refusalShowsLocalizedRefusalMessageNotTheSharedModuleEnglishConstant() {
        compose.setContent {
            AnswerScreen(result = RetrievalResult.Refusal, onReadAloud = {}, readAloudAvailable = true)
        }

        compose.onNodeWithText(RetrievalResult.Refusal.MESSAGE).assertDoesNotExist()
        val refusalBody = compose.activity.getString(R.string.answer_refusal_body)
        compose.onNodeWithText(refusalBody).assertExists()
        val refusalTitle = compose.activity.getString(R.string.answer_refusal_title)
        compose.onNodeWithText(refusalTitle).assertExists()
    }

    @Test
    fun refusalDoesNotShowAReadAloudButton() {
        compose.setContent {
            AnswerScreen(result = RetrievalResult.Refusal, onReadAloud = {}, readAloudAvailable = true)
        }

        compose.onNodeWithTag("answer-read-aloud-button").assertDoesNotExist()
    }

    @Test
    fun passagesShowCitationSourceAndReadAloudButton() {
        val result = RetrievalResult.Passages(listOf(passageFixture()))
        compose.setContent {
            AnswerScreen(result = result, onReadAloud = {}, readAloudAvailable = true)
        }

        compose.onNodeWithText("Household Water Treatment Guide").assertExists()
        compose.onNodeWithText("WHO").assertExists()
        compose.onNodeWithText("Boil water for at least one minute before drinking.").assertExists()
        val readAloudDescription = compose.activity.getString(R.string.answer_read_aloud_description)
        compose.onNodeWithContentDescription(readAloudDescription).assertExists()
    }

    @Test
    fun readAloudButtonDisabledWhenTtsUnavailable() {
        val result = RetrievalResult.Passages(listOf(passageFixture()))
        compose.setContent {
            AnswerScreen(result = result, onReadAloud = {}, readAloudAvailable = false)
        }

        val readAloudDescription = compose.activity.getString(R.string.answer_read_aloud_description)
        compose.onNodeWithContentDescription(readAloudDescription).assertIsNotEnabled()
        // Never silently missing: the button stays visible, with an explanation shown alongside it.
        val unavailableExplanation = compose.activity.getString(R.string.answer_read_aloud_unavailable)
        compose.onNodeWithText(unavailableExplanation).assertExists()
    }

    @Test
    fun readAloudButtonEnabledAndInvokesCallbackWhenTtsAvailable() {
        var tapped = false
        val result = RetrievalResult.Passages(listOf(passageFixture()))
        compose.setContent {
            AnswerScreen(result = result, onReadAloud = { tapped = true }, readAloudAvailable = true)
        }

        val readAloudDescription = compose.activity.getString(R.string.answer_read_aloud_description)
        compose.onNodeWithContentDescription(readAloudDescription).assertIsEnabled()
        compose.onNodeWithContentDescription(readAloudDescription).performClick()

        assertTrue(tapped)
    }

    @Test
    fun rootIsTaggedAnswerScreen() {
        compose.setContent {
            AnswerScreen(result = RetrievalResult.Refusal, onReadAloud = {}, readAloudAvailable = true)
        }

        compose.onNodeWithTag("answer-screen").assertExists()
    }
}
