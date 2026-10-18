// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.formfactor.phone

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PhoneJournalSetupComposeTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun setupButtonsPublishVariantSemanticsAndHandleClicks() {
        var setOneUpClicks = 0
        var sendLinkClicks = 0
        var alreadyHaveOneClicks = 0

        composeRule.setContent {
            MaterialTheme {
                PhoneJournalSetup(
                    onSetOneUp = { setOneUpClicks++ },
                    onSendLink = { sendLinkClicks++ },
                    onAlreadyHaveOne = { alreadyHaveOneClicks++ },
                )
            }
        }

        val setOneUpNode = composeRule.onNodeWithTag("journalSetupSetOneUp")
        val sendLinkNode = composeRule.onNodeWithTag("journalSetupSendLink")
        val alreadyHaveNode = composeRule.onNodeWithTag("journalSetupAlreadyHave")

        assertEquals(
            JournalSetupButtonVariant.Filled,
            setOneUpNode.fetchSemanticsNode().config[JournalSetupButtonVariantKey],
        )
        assertEquals(
            JournalSetupButtonVariant.Outlined,
            sendLinkNode.fetchSemanticsNode().config[JournalSetupButtonVariantKey],
        )
        assertEquals(
            JournalSetupButtonVariant.Outlined,
            alreadyHaveNode.fetchSemanticsNode().config[JournalSetupButtonVariantKey],
        )

        setOneUpNode.performClick()
        assertEquals(1, setOneUpClicks)
        assertEquals(0, sendLinkClicks)
        assertEquals(0, alreadyHaveOneClicks)

        sendLinkNode.performClick()
        assertEquals(1, setOneUpClicks)
        assertEquals(1, sendLinkClicks)
        assertEquals(0, alreadyHaveOneClicks)

        alreadyHaveNode.performClick()
        assertEquals(1, setOneUpClicks)
        assertEquals(1, sendLinkClicks)
        assertEquals(1, alreadyHaveOneClicks)
    }
}
