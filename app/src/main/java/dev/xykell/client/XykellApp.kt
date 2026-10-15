package dev.xykell.client

import android.app.Application

/**
 * Earliest hook available in this app.
 *
 * Installing the crash handler and opening the startup trace here rather than in
 * `MainActivity.onCreate` matters: auto-initialised ContentProviders run before
 * any activity, so a failure in one of those would previously have produced no
 * evidence at all.
 */
class XykellApp : Application() {
    override fun onCreate() {
        super.onCreate()
        StartupTrace.attach(this)
        CrashReporter.install(this)
    }
}
