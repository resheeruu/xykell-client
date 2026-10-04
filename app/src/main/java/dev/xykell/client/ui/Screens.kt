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

class PacksFragment : InfoFragment() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "Packs"
        body = "NOT WIRED — pack browser pending Xykell content manager.\n\n" +
            "Planned: browse, import, export, enable, disable, compatibility, " +
            "backup, rollback. Never loads native executables as content."
    }
}

class AccountsFragment : InfoFragment() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "Accounts"
        body = "NOT WIRED — official flows only.\n\nXykell will use official " +
            "Microsoft/Xbox sign-in flows. Never enters passwords, never " +
            "extracts tokens, never bypasses authentication. Until an " +
            "official integration exists, manage accounts in Minecraft itself."
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
