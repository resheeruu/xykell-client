package dev.xykell.client.runtime.observation

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat

/**
 * Explicit user-driven foreground observation owner (Stage 20, Phase 1).
 *
 * Started/stopped ONLY by the Activity (no receiver, no boot path, no
 * auto-start). Owns exactly one LoopbackWebSocket session, one
 * LiveProducer handoff, and forwards Translated items to the native
 * consumer via Observations offers. Session policy lives in
 * ObservationStateMachine (pure, unit-tested): no reconnect — any
 * close/failure ends the session and only another explicit Start begins
 * a new one. Process death leaves nothing behind (no persistence
 * anywhere in this path).
 */
class ObservationService : Service() {

    private var socket: LoopbackWebSocket? = null
    private val producer = LiveProducer()
    private val fsm = ObservationStateMachine()
    private var malformedDropped: Long = 0L
    private var chatCount = 0
    private var travelCount = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                fsm.stop("")
                tearDown()
                stopSelf()
                return START_NOT_STICKY
            }
            else -> beginSession()
        }
        return START_NOT_STICKY // never restart after death: fresh Start only
    }

    private fun beginSession() {
        if (!fsm.start()) return // already running: ignore redundant Start
        // Foreground FIRST: must precede any network work (documented
        // ~5-second startForeground deadline after startForegroundService).
        try {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                statusNotification(ObservationState.STARTING),
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                } else {
                    0
                },
            )
        } catch (e: Exception) {
            fsm.fail("foreground-start-denied")
            syncCompanion()
            stopSelf()
            return
        }
        chatCount = 0
        travelCount = 0
        malformedDropped = 0
        producer.clear()
        Companion.observed.clear()
        syncCompanion()
        socket = LoopbackWebSocket(Handler(), LoopbackWebSocket::defaultClient)
        socket?.start()
    }

    private inner class Handler : LoopbackWebSocket.Events {
        override fun onHandshakeSent() = Unit
        override fun onEstablished() {
            fsm.onEstablished()
            syncCompanion()
            refreshNotification()
        }

        override fun onTranslated(item: Translated) {
            // Bounded handoff stays in the path: offer, then synchronously
            // drain on this callback thread (no extra thread, no backlog).
            producer.offer(item)
            while (true) {
                val next = producer.pollNext()
                if (next.poll != LiveProducer.Poll.ITEM || next.item == null) break
                forward(next.item)
            }
            refreshNotification()
        }

        override fun onDroppedMalformed() {
            malformedDropped++
            refreshNotification()
        }

        override fun onFailed(reason: String) {
            fsm.fail(reason)
            tearDown()
            syncCompanion()
            stopSelf()
        }

        override fun onClosed() {
            // Close without prior failure (e.g. our own stop or remote
            // close): terminal per policy, never reconnect.
            fsm.onClosed()
            tearDown()
            syncCompanion()
            stopSelf()
        }
    }

    private fun forward(item: Translated) {
        when (item) {
            is ChatMessage -> chatCount++
            is Travelled -> travelCount++
            is UnknownFrame -> Unit
        }
        // Kotlin-side state first: pure memory, always available even when the
        // native bridge is missing, so the UI read path never depends on it.
        try {
            when (item) {
                is ChatMessage -> Companion.observed.onPlayerMessage(
                    item.eventId, item.observedAtMs, item.sender, item.message,
                )
                is Travelled -> Companion.observed.onPlayerTravelled(
                    item.eventId, item.observedAtMs,
                    item.x, item.y, item.z, item.yawDegrees,
                    item.metersTravelled, item.travelMethod,
                )
                is UnknownFrame -> Unit
            }
        } catch (_: Exception) {
            // Bounded state rejects malformed input by contract; never let a
            // bad frame stop the session or the notification counters.
        }
        try {
            when (item) {
                is ChatMessage -> Observations.offerPlayerMessage(
                    item.eventId, item.observedAtMs, item.sender, item.message,
                )
                is Travelled -> Observations.offerPlayerTravelled(
                    item.eventId, item.observedAtMs,
                    item.x, item.y, item.z, item.yawDegrees,
                    item.metersTravelled, item.travelMethod,
                )
                is UnknownFrame -> Observations.offerUnknown(
                    item.eventId, item.wireLength.toLong(), item.reason, item.observedAtMs,
                )
            }
        } catch (e: UnsatisfiedLinkError) {
            // Native bridge absent: observations stay local (counts still move).
        }
    }

    private fun tearDown() {
        try {
            socket?.close()
        } catch (_: Exception) {
        }
        socket = null
    }

    private fun syncCompanion() {
        Companion.state = fsm.state
        Companion.statusDetail = fsm.detail
    }

    override fun onDestroy() {
        if (fsm.state == ObservationState.OBSERVING ||
            fsm.state == ObservationState.STARTING
        ) {
            fsm.stop("")
        }
        tearDown()
        syncCompanion()
        super.onDestroy()
    }

    private fun statusNotification(state: ObservationState): Notification {
        ensureChannel()
        // Counts/state only. NEVER message text, names, positions, keys,
        // ciphertext, or diagnostics payloads.
        val text = "State: $state | chat: $chatCount travel: $travelCount" +
            (if (fsm.detail.isNotEmpty()) " | ${fsm.detail}" else "")
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Xykell observation")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setOngoing(true)
            .build()
    }

    private fun refreshNotification() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        try {
            nm.notify(NOTIFICATION_ID, statusNotification(fsm.state))
        } catch (_: Exception) {
        }
    }

    private fun ensureChannel() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        try {
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID, "Xykell observation", NotificationManager.IMPORTANCE_LOW,
                ),
            )
        } catch (_: Exception) {
        }
    }

    companion object {
        const val ACTION_START = "dev.xykell.client.action.OBSERVE_START"
        const val ACTION_STOP = "dev.xykell.client.action.OBSERVE_STOP"
        const val CHANNEL_ID = "xykell_observation"
        const val NOTIFICATION_ID = 41

        @Volatile
        var state: ObservationState = ObservationState.IDLE
            private set
        @Volatile
        var statusDetail: String = ""
            private set

        fun start(context: Context) {
            state = ObservationState.IDLE
            statusDetail = ""
            val intent = Intent(context, ObservationService::class.java).apply {
                action = ACTION_START
            }
            androidx.core.content.ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, ObservationService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }

        fun statusText(): String =
            "Observation: $state" +
                (if (statusDetail.isNotEmpty()) " ($statusDetail)" else "") +
                "\nEndpoint: fixed localhost (no setting)"

        /**
         * Read side of the observation stream for the UI.
         *
         * Exists so the chat/motion state that the translator already produces
         * is reachable without a native round trip. The native consumer is a
         * write-only sink (no read-back API), so before this the state was
         * assembled nowhere in production: ObservedState had test callers only.
         *
         * Cleared when a session starts, matching the counters. Kept after the
         * session ends so a stopped session can still be read.
         */
        private val observed = ObservedState()

        fun observedState(): ObservedState = observed
    }
}
