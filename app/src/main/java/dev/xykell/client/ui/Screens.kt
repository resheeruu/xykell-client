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
