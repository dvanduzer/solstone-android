// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.phone

import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.solstone.observer.formfactor.phone.PhonePairingEvent
import app.solstone.observer.formfactor.phone.PhonePairingSession
import app.solstone.observer.formfactor.phone.PhonePairingState
import app.solstone.observer.formfactor.phone.PhonePairingStep
import app.solstone.observer.formfactor.phone.reducePhonePairing
import app.solstone.observer.harness.HarnessController
import app.solstone.observer.scaffold.CameraAsk
import app.solstone.platform.work.SyncStores
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class PhonePairingViewModel : ViewModel() {
    var pairingState by mutableStateOf(PhonePairingState())
        private set

    var cameraAsk by mutableStateOf(CameraAsk.NotAsked)

    fun dispatch(
        event: PhonePairingEvent,
        controller: HarnessController,
        stores: SyncStores,
    ) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            performDispatch(event, controller, stores)
        } else {
            viewModelScope.launch(Dispatchers.Main) {
                performDispatch(event, controller, stores)
            }
        }
    }

    private fun performDispatch(
        event: PhonePairingEvent,
        controller: HarnessController,
        stores: SyncStores,
    ) {
        if (event is PhonePairingEvent.OpenScan && pairingState.session !is PhonePairingSession.Scanning) {
            cameraAsk = CameraAsk.NotAsked
        }
        val step = reducePhonePairing(pairingState, event)
        applyStep(step, controller, stores)
    }

    fun applyStep(
        step: PhonePairingStep,
        controller: HarnessController,
        stores: SyncStores,
    ) {
        pairingState = step.state

        val dial = step.dial
        if (dial != null) {
            val generation = step.state.generation
            viewModelScope.launch(Dispatchers.IO) {
                val result = controller.dispatchPairLink(dial)
                withContext(Dispatchers.Main) {
                    dispatch(PhonePairingEvent.AttemptFinished(generation, result), controller, stores)
                }
            }
        }

        if (step.clearJournal) {
            val generation = step.state.generation
            viewModelScope.launch(Dispatchers.IO) {
                val cleared = performPhoneMismatch(stores)
                withContext(Dispatchers.Main) {
                    dispatch(PhonePairingEvent.MismatchFinished(generation, cleared), controller, stores)
                }
            }
        }
    }
}
