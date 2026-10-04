package dev.xykell.client.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.Fragment
import dev.xykell.client.MainActivity
import dev.xykell.client.R

/**
 * Client screen: composite hub for version management, modules,
 * and client-level settings. This is the "Client" tab in the pager.
 */
class ClientFragment : Fragment(R.layout.fragment_client) {

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Title
        view.findViewById<TextView>(R.id.client_title).text = "CLIENT"

        // Body description
        view.findViewById<TextView>(R.id.client_body).text =
            "Client configuration: versions, modules, and core settings."

        // Quick action buttons
        val container = view.findViewById<LinearLayout>(R.id.client_actions)

        // Versions
        val versionsBtn = createActionButton(
            "Versions",
            "Manage Minecraft versions and compatibility",
            R.string.nav_versions
        ) {
            (activity as? MainActivity)?.navigateToVersion()
        }
        container.addView(versionsBtn)

        // Modules
        val modulesBtn = createActionButton(
            "Modules",
            "Enable, configure, and organize modules",
            R.string.nav_modules
        ) {
            (activity as? MainActivity)?.navigateToModules()
        }
        container.addView(modulesBtn)

        // Settings
        val settingsBtn = createActionButton(
            "Settings",
            "Configure client behavior and appearance",
            R.string.nav_settings
        ) {
            (activity as? MainActivity)?.navigateToSettings()
        }
        container.addView(settingsBtn)

        // Keybinds
        val keybindsBtn = createActionButton(
            "Keybinds",
            "Configure input bindings and controls",
            R.string.nav_keybinds
        ) {
            (activity as? MainActivity)?.navigateToKeybinds()
        }
        container.addView(keybindsBtn)

        // Themes
        val themesBtn = createActionButton(
            "Themes",
            "Select and preview visual themes",
            R.string.nav_themes
        ) {
            (activity as? MainActivity)?.navigateToThemes()
        }
        container.addView(themesBtn)

        // About
        val aboutBtn = createActionButton(
            "About",
            "Version info, licenses, and credits",
            R.string.nav_about
        ) {
            (activity as? MainActivity)?.navigateToAbout()
        }
        container.addView(aboutBtn)
    }

    private fun createActionButton(
        title: String,
        description: String,
        navStringRes: Int,
        onClick: () -> Unit
    ): View {
        val context = requireContext()
        val density = context.resources.displayMetrics.density

        val card = LinearLayout(context)
        card.orientation = LinearLayout.VERTICAL
        card.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        )
        card.setPadding(
            (16 * density).toInt(), (16 * density).toInt(),
            (16 * density).toInt(), (16 * density).toInt(),
        )
        card.setBackgroundResource(R.drawable.client_action_card_bg)
        card.setOnClickListener { onClick() }
        card.isClickable = true
        card.contentDescription = title

        val titleView = TextView(context)
        titleView.text = title
        titleView.textSize = 18f
        titleView.setTypeface(null, android.graphics.Typeface.BOLD)
        titleView.setTextColor(
            androidx.core.content.ContextCompat.getColor(context, R.color.xykell_accent)
        )

        val descView = TextView(context)
        descView.text = description
        descView.textSize = 14f
        descView.setTextColor(
            androidx.core.content.ContextCompat.getColor(context, R.color.xykell_muted)
        )
        descView.setPadding(0, (4 * density).toInt(), 0, 0)

        card.addView(titleView)
        card.addView(descView)

        return card
    }
}