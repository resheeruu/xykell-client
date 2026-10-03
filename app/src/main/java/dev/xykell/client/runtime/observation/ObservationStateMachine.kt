package dev.xykell.client.runtime.observation

/**
 * Explicit observation session states (Stage 20, Phase 1). Pure logic,
 * no Android dependency: the service delegates every transition here
 * so host unit tests cover the policy. No automatic reconnect exists
 * anywhere in this model — only an explicit Start leaves IDLE/STOPPED/
 * FAILED, and only a fresh session follows a terminal state.
 */
enum class ObservationState { IDLE, STARTING, OBSERVING, STOPPED, FAILED }

class ObservationStateMachine {
    var state: ObservationState = ObservationState.IDLE
        private set
    var detail: String = ""
        private set

    /** Explicit user Start. Fresh session from any resting state. */
    fun start(): Boolean {
        if (state != ObservationState.IDLE &&
            state != ObservationState.STOPPED &&
            state != ObservationState.FAILED
        ) {
            return false
        }
        state = ObservationState.STARTING
        detail = ""
        return true
    }

    fun onEstablished() {
        if (state == ObservationState.STARTING) state = ObservationState.OBSERVING
    }

    fun stop(reason: String = "") {
        state = ObservationState.STOPPED
        detail = reason
    }

    fun fail(reason: String) {
        state = ObservationState.FAILED
        detail = reason
    }

    fun onClosed() {
        if (state == ObservationState.OBSERVING || state == ObservationState.STARTING) {
            state = ObservationState.STOPPED
            if (detail.isEmpty()) detail = "closed"
        }
    }
}
