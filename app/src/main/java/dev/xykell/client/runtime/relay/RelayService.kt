package dev.xykell.client.runtime.relay

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.Handler
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import dev.xykell.client.NativeProfiles
import dev.xykell.client.runtime.cheat.TouchAutomationService
import dev.xykell.client.runtime.modules.ModuleFlags
import dev.xykell.client.runtime.modules.ModuleRuntime
import dev.xykell.client.runtime.modules.ModuleTapRunner

/**
 * Foreground owner of the relay session. Started/stopped ONLY from the Relay
 * screen with explicit upstream settings (no receiver, no boot path, never
 * auto-started).
 *
 * The transport is a terminating [RelaySession]: the relay plays the SERVER role
 * toward the game and the CLIENT role toward the upstream, holding both
 * handshake keys. That is what makes the module hook real — a transparent pipe
 * cannot decrypt, so every module would have been a no-op — and it is also what
 * lets observation work: [RelayObservation] sees plaintext, where the old
 * [BedrockTap] path never received a key and stayed inert.
 *
 * Modules run from the active profile's flags and are applied per packet by
 * [ModuleRuntime]; the tap plans the input-injection ids answer with are
 * replayed by [ModuleTapRunner] through the accessibility service, which is the
 * same gesture surface a finger uses. No payload is logged or stored, and the
 * notification shows endpoints and leg state only.
 */
class RelayService : Service() {

    private var driver: RelaySessionDriver? = null
    private var modules: ModuleRuntime? = null
    private var tapRunner: ModuleTapRunner? = null

    /** One handler for the whole tick loop; a per-tick Handler would allocate 20/s. */
    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }

    /** Ticks seen since Start, for the periodic leg-state poll. */
    private var tickCount = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                tearDown()
                stopSelf()
                return START_NOT_STICKY
            }
            else -> begin(intent)
        }
        return START_NOT_STICKY
    }

    private fun begin(intent: Intent?) {
        if (driver != null) return // already running: ignore redundant Start
        // Foreground FIRST: must precede any socket work (~5s deadline).
        try {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                statusNotification(),
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                } else {
                    0
                },
            )
        } catch (e: Exception) {
            detail = "foreground-start-denied"
            syncCompanion()
            stopSelf()
            return
        }
        val host = intent?.getStringExtra(EXTRA_HOST)
        val upstreamPort = intent?.getIntExtra(EXTRA_UPSTREAM_PORT, -1) ?: -1
        val listenPort = intent?.getIntExtra(EXTRA_LISTEN_PORT, -1) ?: -1
        if (host.isNullOrEmpty() || upstreamPort !in 1..65535 || listenPort !in 0..65535) {
            detail = "invalid settings"
            syncCompanion()
            stopSelf()
            return
        }
        try {
            val runtime = ModuleRuntime(
                isEnabled = ModuleFlags.predicate(activeProfileJson()),
            )
            // Shares the runtime's entity table, so the population observation
            // counts the same live entities the ESP modules read.
            val observation = RelayObservation(runtime, entities = runtime.ctx.entities)
            val started = RelaySessionDriver(
                upstreamHost = host,
                upstreamPort = upstreamPort,
                listenPort = listenPort,
                listener = observation,
                onStopped = { reason ->
                    // The session's own reason, not a generic "stopped": a
                    // refused upstream login and a closed game socket are
                    // different problems for the user, and only the session can
                    // tell them apart.
                    detail = reason ?: ""
                    syncCompanion()
                    refreshNotification()
                },
            )
            started.start()
            tickCount = 0
            driver = started
            modules = runtime
            tapRunner = ModuleTapRunner(
                runtime = runtime,
                play = { steps -> TouchAutomationService.instance?.playPlan(steps) },
                // The tick loop doubles as the leg-state poller: the driver
                // owns the session on its own thread, so without this the
                // HANDSHAKING -> ONLINE transition would only surface on the
                // next Start/Stop.
                postDelayed = { r, delay ->
                    mainHandler.postDelayed(r, delay)
                    if (++tickCount % STATUS_POLL_TICKS == 0) {
                        syncOnline(driver?.bothLegsOnline() ?: false)
                    }
                },
            ).also { it.start() }
            target = "$host:$upstreamPort"
            detail = ""
            syncCompanion()
            refreshNotification()
        } catch (e: Exception) {
            detail = "failed to bind: ${e.message}"
            syncCompanion()
            stopSelf()
        }
    }

    /**
     * The active profile's JSON, or null when no profile or bridge is available.
     *
     * Null leaves every module off: an unreadable store must never mean "run
     * everything". Read per Start, not per packet, so the JNI call stays off the
     * relay's hot path; a module switched mid-session takes effect on the next
     * Start.
     */
    private fun activeProfileJson(): String? = try {
        val root = NativeProfiles.root(this)
        val profile = NativeProfiles.getActive(root)
        if (profile.isEmpty()) null else NativeProfiles.getProfileJson(root, profile)
    } catch (e: Exception) {
        null
    }

    private fun tearDown() {
        tapRunner?.stop()
        tapRunner = null
        modules?.reset()
        modules = null
        driver?.close()
        driver = null
        syncCompanion()
    }

    override fun onDestroy() {
        tearDown()
        super.onDestroy()
    }

    private fun statusNotification(): Notification {
        ensureChannel()
        // Endpoints only. NEVER payloads, player names, or server chat.
        val text = when {
            !running -> detail.ifEmpty { "stopped" }
            online -> "127.0.0.1:$localPort → $target"
            else -> "handshaking 127.0.0.1:$localPort → $target"
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Xykell relay")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setOngoing(running)
            .build()
    }

    private fun refreshNotification() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        try {
            nm.notify(NOTIFICATION_ID, statusNotification())
        } catch (_: Exception) {
        }
    }

    private fun ensureChannel() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        try {
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID, "Xykell relay", NotificationManager.IMPORTANCE_LOW,
                ),
            )
        } catch (_: Exception) {
        }
    }

    private fun syncCompanion() {
        running = driver != null
        localPort = driver?.port ?: 0
        // Read through a volatile: the driver owns the session state on its own
        // thread, and a stale false here would report a live relay as down.
        syncOnline(driver?.bothLegsOnline() ?: false)
    }

    companion object {
        const val ACTION_START = "dev.xykell.client.action.RELAY_START"
        const val ACTION_STOP = "dev.xykell.client.action.RELAY_STOP"
        const val CHANNEL_ID = "xykell_relay"
        const val NOTIFICATION_ID = 42
        const val EXTRA_HOST = "host"
        const val EXTRA_UPSTREAM_PORT = "upstream_port"
        const val EXTRA_LISTEN_PORT = "listen_port"
        /** 20 Hz tick x 20 = one leg-state poll per second. */
        const val STATUS_POLL_TICKS = 20

        @Volatile
        var running: Boolean = false
            private set
        @Volatile
        var localPort: Int = 0
            private set
        @Volatile
        var target: String = ""
            private set
        @Volatile
        var detail: String = ""
            private set
        /** True only once both legs are up and packets can actually flow. */
        @Volatile
        var online: Boolean = false
            private set

        internal fun syncOnline(value: Boolean) {
            online = if (running) value else false
        }

        fun start(context: Context, host: String, upstreamPort: Int, listenPort: Int) {
            detail = ""
            val intent = Intent(context, RelayService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_HOST, host)
                putExtra(EXTRA_UPSTREAM_PORT, upstreamPort)
                putExtra(EXTRA_LISTEN_PORT, listenPort)
            }
            androidx.core.content.ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, RelayService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }

        /**
         * RUNNING means the sockets are bound. ONLINE means both legs completed
         * their handshake and packets are flowing — the distinction matters
         * because a bound relay that never completes the upstream login looks
         * identical to a working one otherwise.
         */
        fun statusText(): String = when {
            !running -> "Relay: STOPPED" + if (detail.isNotEmpty()) " ($detail)" else ""
            online -> "Relay: ONLINE (127.0.0.1:$localPort \u2192 $target)"
            else -> "Relay: HANDSHAKING (127.0.0.1:$localPort \u2192 $target)"
        }
    }
}
