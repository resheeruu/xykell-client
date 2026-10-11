package dev.xykell.client.runtime

import android.content.Context
import java.io.File

/**
 * Persistent home for [Onboarding].
 *
 * Split from the policy so the rules stay host-testable and the platform file
 * lives in one place. The document is written atomically: a half-written
 * onboarding file would restore as "first run", which re-shows the
 * introduction to someone who already dismissed it.
 *
 * There is no gate here and no time limit. "First run" means *show the
 * introduction once*, and that is the whole meaning of the flag.
 */
object OnboardingStore {

    private const val FILE = "onboarding.json"

    fun onboardingFile(context: Context): File = File(context.filesDir, FILE)

    /** Loads, falling back to a fresh first-run state when absent or refused. */
    fun load(context: Context): Onboarding {
        val o = Onboarding()
        val file = onboardingFile(context)
        if (!file.isFile) return o
        val restored = try {
            o.deserialize(file.readText())
        } catch (e: Exception) {
            false
        }
        // A refused document leaves the defaults in place rather than half-applied.
        if (!restored) return Onboarding()
        return o
    }

    fun save(context: Context, onboarding: Onboarding) {
        val file = onboardingFile(context)
        val tmp = File(file.parent, file.name + ".tmp")
        try {
            tmp.writeText(onboarding.serialize())
            if (!tmp.renameTo(file)) {
                file.writeText(onboarding.serialize())
                tmp.delete()
            }
        } catch (e: Exception) {
            // Losing onboarding state is not worth failing the screen over; the
            // worst case is that the introduction is offered again.
            tmp.delete()
        }
    }
}
