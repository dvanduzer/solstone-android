// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.formfactor.phone

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

internal enum class JournalSetupButtonVariant { Filled, Outlined }

internal val JournalSetupButtonVariantKey = SemanticsPropertyKey<JournalSetupButtonVariant>(
    name = "JournalSetupButtonVariant",
)

@Composable
fun PhoneJournalSetup(
    onSetOneUp: () -> Unit,
    onSendLink: () -> Unit,
    onAlreadyHaveOne: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            text = "today, your journal lives on a computer you own.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Button(
            onClick = onSetOneUp,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .testTag("journalSetupSetOneUp")
                .semantics { this[JournalSetupButtonVariantKey] = JournalSetupButtonVariant.Filled },
        ) {
            Text("set one up")
        }
        OutlinedButton(
            onClick = onSendLink,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .testTag("journalSetupSendLink")
                .semantics { this[JournalSetupButtonVariantKey] = JournalSetupButtonVariant.Outlined },
        ) {
            Text("send yourself the link")
        }
        OutlinedButton(
            onClick = onAlreadyHaveOne,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .testTag("journalSetupAlreadyHave")
                .semantics { this[JournalSetupButtonVariantKey] = JournalSetupButtonVariant.Outlined },
        ) {
            Text("i already have one")
        }
        Text(
            text = "what you share stays on this device and isn't processed until you connect a journal.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}
