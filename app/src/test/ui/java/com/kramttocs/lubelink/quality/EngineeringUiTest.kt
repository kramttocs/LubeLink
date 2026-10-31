package com.kramttocs.lubelink.quality

import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kramttocs.lubelink.core.EditorRules
import com.kramttocs.lubelink.ui.EditChangeReview
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Uses only fabricated values; no connected server or real account. */
@RunWith(AndroidJUnit4::class)
class EngineeringUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun editReviewUsesFriendlyDatesHourMetersAndBooleanLabels() {
        val changes = listOf(
            EditorRules.PersistedChange(EditorRules.PersistedField.DATE, "2026-10-01", "2026-10-02"),
            EditorRules.PersistedChange(EditorRules.PersistedField.READING, "1200", "1300"),
            EditorRules.PersistedChange(EditorRules.PersistedField.FILLED, "true", "false")
        )
        compose.setContent { EditChangeReview(changes, useHours = true) }
        compose.onNodeWithText("Date: Oct 1, 2026 → Oct 2, 2026").assertExists()
        compose.onNodeWithText("Hours: 1,200 → 1,300").assertExists()
        compose.onNodeWithText("Filled tank: Yes → No").assertExists()
    }
}
