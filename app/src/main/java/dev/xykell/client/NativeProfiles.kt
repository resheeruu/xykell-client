package dev.xykell.client

import android.content.Context
import java.io.File

/** JNI facade over the shared native ProfileManager (same C++ as the game
 *  module). Store root is the launcher's own sandbox; the game-process store
 *  is separate (see LAUNCHER-INTEGRATION.md) — sync travels via export files. */
object NativeProfiles {
    init {
        System.loadLibrary("xykellcore")
    }

    fun root(context: Context): String =
        File(context.filesDir, "xykell").absolutePath

    external fun listProfiles(root: String): Array<String>
    external fun getActive(root: String): String
    external fun setActive(root: String, name: String): Boolean
    external fun getProfileJson(root: String, name: String): String?
    external fun importProfileJson(root: String, name: String, json: String): Boolean
    external fun createProfile(root: String, name: String): Boolean
    external fun resetProfile(root: String, name: String): Boolean
    external fun deleteProfile(root: String, name: String): Boolean

    /** Bounded lifecycle op with the native error surfaced (Batch 11):
     *  op 0=activate, 1=create, 2=reset, 3=delete. "" means success,
     *  anything else is the exact native rejection reason. Missing
     *  bridge reports that honestly — never a fabricated reason. */
    fun profileOp(root: String, name: String, op: Int): String = guard {
        profileOpRaw(root, name, op)
    } ?: "native bridge unavailable"

    private external fun profileOpRaw(root: String, name: String, op: Int): String

    /** Shared version verdict ("STATE|reason") from native VersionAdapter. */
    external fun checkVersion(version: String, abi: String): String

    /** Shared install verdict ("STATE|reason"). queriesGranted must reflect
     *  whether this app declares <queries> visibility for the package. */
    external fun checkInstall(
        found: Boolean, version: String, abi: String,
        enabled: Boolean, queriesGranted: Boolean
    ): String

    private inline fun <T> guard(block: () -> T): T? {
        return try {
            block()
        } catch (e: UnsatisfiedLinkError) {
            null
        }
    }
}
