package dev.xykell.client.ui

import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.fragment.app.Fragment
import dev.xykell.client.R

/** Generic title + body screen for Versions/Profiles/Settings/About. */
open class InfoFragment : Fragment(R.layout.fragment_info) {
    var title: String = ""
    var body: String = ""

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        view.findViewById<TextView>(R.id.info_title).text = title
        view.findViewById<TextView>(R.id.info_body).text = body
    }
}

class VersionsFragment : InfoFragment() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "Versions"
        body = dev.xykell.client.runtime.VersionManager.statusText() +
            "\n\nInstalled versions will list here once wired."
    }
}

class ProfilesFragment : InfoFragment() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "Profiles"
        val pm = dev.xykell.client.runtime.ProfileManager
        body = "Current: ${pm.currentProfile}\nAvailable: ${pm.profiles.joinToString()}\n\n" +
            "Native profiles (Config/ProfileManager) live in the game-process " +
            "store — no shared-storage bridge yet (RESEARCH_REQUIRED, no " +
            "duplicate store here). This screen mirrors them once wired."
    }
}

class SettingsFragment : InfoFragment() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "Settings"
        body = "NOT WIRED — launcher settings pending.\n\nSafe mode lives in " +
            "native CrashGuard (game process). When active the launcher will " +
            "show: SAFE MODE / Reason / Disabled modules. Bridge: NOT WIRED."
    }
}
class AboutFragment : InfoFragment() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val i = dev.xykell.client.runtime.XykellInfo
        title = "About"
        body = "Xykell Client ${i.XYKELL_VERSION} (shell)\n" +
            "Native core ${i.NATIVE_VERSION}\n" +
            "Levi target ${i.LEVI_TARGET}, preloader ${i.PRELOADER_PIN}\n\n" +
            "Original implementation. Not affiliated with Mojang/Microsoft.\n" +
            "See docs/LICENSES.md in the repository."
    }
}

class WorldsFragment : InfoFragment() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "Worlds"
        body = "NOT WIRED — world browser pending Levi runtime integration.\n\n" +
            "Planned: browse, backup, restore, import, export, profile " +
            "association, version compatibility. Uses official/local " +
            "Minecraft data pathways only."
    }
}

class PacksFragment : InfoFragment() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "Packs"
        body = "NOT WIRED — resource-pack browser pending Levi runtime integration.\n\n" +
            "Planned: browse, import, export, enable, disable, compatibility, " +
            "backup, rollback. Never loads native executables as content."
    }
}

class ServersFragment : InfoFragment() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "Servers"
        body = "NOT WIRED — server browser pending network layer.\n\n" +
            "Planned: saved servers, status, latency where measurable, " +
            "profiles, connection history. No credentials collected."
    }
}
