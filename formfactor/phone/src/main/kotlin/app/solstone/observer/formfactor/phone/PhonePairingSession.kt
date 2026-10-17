// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.formfactor.phone

import app.solstone.core.pl.PairLinkAcceptance
import app.solstone.core.pl.acceptPairLink
import app.solstone.observer.harness.PairAttemptOutcome
import app.solstone.observer.harness.PairLinkDispatchResult

data class PhonePairingState(
    val session: PhonePairingSession = PhonePairingSession.Closed,
    val generation: Long = 0,
)

sealed interface PhonePairingSession {
    data object Closed : PhonePairingSession
    data class Entry(
        val stagedCanonical: String?,
        val invalidSubmit: Boolean,
        val outcome: PairAttemptOutcome?,
    ) : PhonePairingSession
    data class Scanning(val stagedCanonical: String?) : PhonePairingSession
    data class Attempting(val stagedCanonical: String, val generation: Long) : PhonePairingSession
    data class Held(
        val stagedCanonical: String,
        val generation: Long,
        val mismatchFailure: Boolean,
    ) : PhonePairingSession
}

sealed interface PhonePairingEvent {
    data class Submit(val acceptance: PairLinkAcceptance) : PhonePairingEvent
    data object OpenScan : PhonePairingEvent
    data object CloseScan : PhonePairingEvent
    data object Connect : PhonePairingEvent
    data class AttemptFinished(val generation: Long, val result: PairLinkDispatchResult) : PhonePairingEvent
    data object Mismatch : PhonePairingEvent
    data class MismatchFinished(val generation: Long, val cleared: Boolean) : PhonePairingEvent
    data object Confirm : PhonePairingEvent
    data object Dismiss : PhonePairingEvent
    data object OpenMark : PhonePairingEvent
}

data class PhonePairingStep(
    val state: PhonePairingState,
    val dial: String? = null,
    val clearJournal: Boolean = false,
    val detachPreview: Boolean = false,
    val returnToShell: Boolean = false,
)

private fun isSuccessfulPair(outcome: PairAttemptOutcome): Boolean =
    outcome is PairAttemptOutcome.Linked &&
        outcome.result.pairStatus in 200..299 &&
        outcome.result.statusStatus in 200..299

fun reducePhonePairing(state: PhonePairingState, event: PhonePairingEvent): PhonePairingStep {
    val session = state.session
    val gen = state.generation
    return when (event) {
        is PhonePairingEvent.Submit -> when (event.acceptance) {
            is PairLinkAcceptance.Accepted -> when (session) {
                is PhonePairingSession.Closed,
                is PhonePairingSession.Entry -> PhonePairingStep(
                    state = state.copy(
                        session = PhonePairingSession.Entry(
                            stagedCanonical = event.acceptance.canonicalHttps,
                            invalidSubmit = false,
                            outcome = null,
                        ),
                    ),
                )
                is PhonePairingSession.Scanning -> PhonePairingStep(
                    state = state.copy(
                        session = PhonePairingSession.Entry(
                            stagedCanonical = event.acceptance.canonicalHttps,
                            invalidSubmit = false,
                            outcome = null,
                        ),
                    ),
                    detachPreview = true,
                )
                is PhonePairingSession.Attempting,
                is PhonePairingSession.Held -> PhonePairingStep(state)
            }
            PairLinkAcceptance.Rejected -> when (session) {
                is PhonePairingSession.Closed -> PhonePairingStep(
                    state = state.copy(
                        session = PhonePairingSession.Entry(
                            stagedCanonical = null,
                            invalidSubmit = true,
                            outcome = null,
                        ),
                    ),
                )
                is PhonePairingSession.Entry -> PhonePairingStep(
                    state = state.copy(
                        session = PhonePairingSession.Entry(
                            stagedCanonical = session.stagedCanonical,
                            invalidSubmit = true,
                            outcome = null,
                        ),
                    ),
                )
                is PhonePairingSession.Scanning -> PhonePairingStep(state)
                is PhonePairingSession.Attempting,
                is PhonePairingSession.Held -> PhonePairingStep(state)
            }
        }
        PhonePairingEvent.OpenScan -> when (session) {
            is PhonePairingSession.Closed -> PhonePairingStep(
                state = state.copy(session = PhonePairingSession.Scanning(stagedCanonical = null)),
            )
            is PhonePairingSession.Entry -> PhonePairingStep(
                state = state.copy(session = PhonePairingSession.Scanning(stagedCanonical = session.stagedCanonical)),
            )
            is PhonePairingSession.Scanning,
            is PhonePairingSession.Attempting,
            is PhonePairingSession.Held -> PhonePairingStep(state)
        }
        PhonePairingEvent.CloseScan -> when (session) {
            is PhonePairingSession.Scanning -> PhonePairingStep(
                state = state.copy(
                    session = PhonePairingSession.Entry(
                        stagedCanonical = session.stagedCanonical,
                        invalidSubmit = false,
                        outcome = null,
                    ),
                ),
                detachPreview = true,
            )
            is PhonePairingSession.Closed,
            is PhonePairingSession.Entry,
            is PhonePairingSession.Attempting,
            is PhonePairingSession.Held -> PhonePairingStep(state)
        }
        PhonePairingEvent.Connect -> when (session) {
            is PhonePairingSession.Entry -> {
                if (session.stagedCanonical != null) {
                    val nextGen = gen + 1
                    PhonePairingStep(
                        state = PhonePairingState(
                            session = PhonePairingSession.Attempting(
                                stagedCanonical = session.stagedCanonical,
                                generation = nextGen,
                            ),
                            generation = nextGen,
                        ),
                        dial = session.stagedCanonical,
                    )
                } else {
                    PhonePairingStep(state)
                }
            }
            is PhonePairingSession.Closed,
            is PhonePairingSession.Scanning,
            is PhonePairingSession.Attempting,
            is PhonePairingSession.Held -> PhonePairingStep(state)
        }
        is PhonePairingEvent.AttemptFinished -> {
            if (session is PhonePairingSession.Attempting && session.generation == event.generation && gen == event.generation) {
                when (event.result) {
                    is PairLinkDispatchResult.Busy -> PhonePairingStep(
                        state = state.copy(
                            session = PhonePairingSession.Entry(
                                stagedCanonical = session.stagedCanonical,
                                invalidSubmit = false,
                                outcome = null,
                            ),
                        ),
                    )
                    is PairLinkDispatchResult.Attempted -> {
                        if (isSuccessfulPair(event.result.outcome)) {
                            PhonePairingStep(
                                state = state.copy(
                                    session = PhonePairingSession.Held(
                                        stagedCanonical = session.stagedCanonical,
                                        generation = gen,
                                        mismatchFailure = false,
                                    ),
                                ),
                            )
                        } else {
                            PhonePairingStep(
                                state = state.copy(
                                    session = PhonePairingSession.Entry(
                                        stagedCanonical = session.stagedCanonical,
                                        invalidSubmit = false,
                                        outcome = event.result.outcome,
                                    ),
                                ),
                            )
                        }
                    }
                    is PairLinkDispatchResult.NoLink,
                    is PairLinkDispatchResult.InvalidLink -> PhonePairingStep(
                        state = state.copy(
                            session = PhonePairingSession.Entry(
                                stagedCanonical = session.stagedCanonical,
                                invalidSubmit = true,
                                outcome = null,
                            ),
                        ),
                    )
                }
            } else {
                PhonePairingStep(state)
            }
        }
        PhonePairingEvent.Mismatch -> when (session) {
            is PhonePairingSession.Held -> PhonePairingStep(
                state = state,
                clearJournal = true,
            )
            is PhonePairingSession.Closed,
            is PhonePairingSession.Entry,
            is PhonePairingSession.Scanning,
            is PhonePairingSession.Attempting -> PhonePairingStep(state)
        }
        is PhonePairingEvent.MismatchFinished -> {
            if (session is PhonePairingSession.Held && session.generation == event.generation && gen == event.generation) {
                if (event.cleared) {
                    PhonePairingStep(
                        state = state.copy(
                            session = PhonePairingSession.Entry(
                                stagedCanonical = null,
                                invalidSubmit = false,
                                outcome = null,
                            ),
                        ),
                    )
                } else {
                    PhonePairingStep(
                        state = state.copy(
                            session = session.copy(mismatchFailure = true),
                        ),
                    )
                }
            } else {
                PhonePairingStep(state)
            }
        }
        PhonePairingEvent.Confirm -> when (session) {
            is PhonePairingSession.Held -> PhonePairingStep(
                state = state.copy(session = PhonePairingSession.Closed),
                returnToShell = true,
            )
            is PhonePairingSession.Closed,
            is PhonePairingSession.Entry,
            is PhonePairingSession.Scanning,
            is PhonePairingSession.Attempting -> PhonePairingStep(state)
        }
        PhonePairingEvent.Dismiss -> when (session) {
            is PhonePairingSession.Scanning -> PhonePairingStep(
                state = state.copy(session = PhonePairingSession.Closed),
                returnToShell = true,
                detachPreview = true,
            )
            is PhonePairingSession.Entry,
            is PhonePairingSession.Attempting,
            is PhonePairingSession.Held -> PhonePairingStep(
                state = state.copy(session = PhonePairingSession.Closed),
                returnToShell = true,
            )
            is PhonePairingSession.Closed -> PhonePairingStep(
                state = state,
                returnToShell = true,
            )
        }
        PhonePairingEvent.OpenMark -> when (session) {
            is PhonePairingSession.Closed,
            is PhonePairingSession.Entry -> {
                val nextGen = gen + 1
                PhonePairingStep(
                    state = PhonePairingState(
                        session = PhonePairingSession.Held(
                            stagedCanonical = "",
                            generation = nextGen,
                            mismatchFailure = false,
                        ),
                        generation = nextGen,
                    ),
                )
            }
            is PhonePairingSession.Scanning,
            is PhonePairingSession.Attempting,
            is PhonePairingSession.Held -> PhonePairingStep(state)
        }
    }
}

fun routePhonePairIntent(state: PhonePairingState, data: String?): PhonePairingStep {
    if (data == null) return PhonePairingStep(state)
    val acceptance = acceptPairLink(data)
    return reducePhonePairing(state, PhonePairingEvent.Submit(acceptance))
}
