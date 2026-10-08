package dev.xykell.client.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.FrameLayout
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
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
        view.findViewById<TextView>(R.id.client_title).text = getString(R.string.client_title_label)

        // Body description
        view.findViewById<TextView>(R.id.client_body).text =
            getString(R.string.client_body_label)

        // Quick action buttons
        val container = view.findViewById<LinearLayout>(R.id.client_actions)

        container.addView(action(R.string.nav_versions, R.string.nav_versions_desc) {
            openSub(SubScreen.Versions)
        })
        container.addView(action(R.string.nav_modules, R.string.nav_modules_desc) {
            openSub(SubScreen.Modules)
        })
        container.addView(action(R.string.nav_settings, R.string.nav_settings_desc) {
            openSub(SubScreen.Settings)
        })
        container.addView(action(R.string.nav_keybinds, R.string.nav_keybinds_desc) {
            openSub(SubScreen.Keybinds)
        })
        container.addView(action(R.string.nav_themes, R.string.nav_themes_desc) {
            openSub(SubScreen.Themes)
        })
        container.addView(action(R.string.nav_performance, R.string.nav_performance_desc) {
            openSub(SubScreen.Performance)
        })
        container.addView(action(R.string.nav_chat, R.string.nav_chat_desc) {
            openSub(SubScreen.Chat)
        })
        container.addView(action(R.string.nav_network, R.string.nav_network_desc) {
            openSub(SubScreen.Network)
        })
        container.addView(action(R.string.nav_waypoints, R.string.nav_waypoints_desc) {
            openSub(SubScreen.Waypoints)
        })
        container.addView(action(R.string.nav_friends, R.string.nav_friends_desc) {
            openSub(SubScreen.Friends)
        })
        container.addView(action(R.string.nav_timer, R.string.nav_timer_desc) {
            openSub(SubScreen.Timer)
        })
        container.addView(action(R.string.nav_screenshot, R.string.nav_screenshot_desc) {
            openSub(SubScreen.Screenshot)
        })
        container.addView(action(R.string.nav_autoclicker, R.string.nav_autoclicker_desc) {
            openSub(SubScreen.Autoclicker)
        })
        container.addView(action(R.string.nav_relay, R.string.nav_relay_desc) {
            openSub(SubScreen.Relay)
        })
        container.addView(action(R.string.nav_diagnostics, R.string.nav_diagnostics_desc) {
            openSub(SubScreen.Diagnostics)
        })
        container.addView(action(R.string.nav_about, R.string.nav_about_desc) {
            openSub(SubScreen.About)
        })
    }

    /**
     * Sub-screens reachable from the hub. Each one is a real Fragment hosted in
     * client_sub_container, with a Back control that returns to the hub.
     *
     * Previously every hub button called MainActivity.navigateToX(), which only
     * did showPage(1) -- i.e. it navigated back to this same screen. The hub
     * buttons were reachable; the screens behind them were not.
     */
    enum class SubScreen {
        Versions, Modules, Settings, Keybinds, Themes, Performance,
        Chat, Network, Waypoints, Friends, Timer, Screenshot, Autoclicker,
        Relay, Diagnostics, About;

        fun titleRes(): Int = when (this) {
            Versions -> R.string.nav_versions
            Modules -> R.string.nav_modules
            Settings -> R.string.nav_settings
            Keybinds -> R.string.nav_keybinds
            Themes -> R.string.nav_themes
            Performance -> R.string.nav_performance
            Chat -> R.string.nav_chat
            Network -> R.string.nav_network
            Waypoints -> R.string.nav_waypoints
            Friends -> R.string.nav_friends
            Timer -> R.string.nav_timer
            Screenshot -> R.string.nav_screenshot
            Autoclicker -> R.string.nav_autoclicker
            Relay -> R.string.nav_relay
            Diagnostics -> R.string.nav_diagnostics
            About -> R.string.nav_about
        }
    }

    private fun fragmentFor(screen: SubScreen): Fragment = when (screen) {
        SubScreen.Versions -> VersionsFragment()
        SubScreen.Modules -> ModulesFragment()
        SubScreen.Settings -> SettingsFragment()
        SubScreen.Keybinds -> KeybindsFragment()
        SubScreen.Themes -> ThemesFragment()
        SubScreen.Performance -> PerformanceFragment()
        SubScreen.Chat -> ChatFragment()
        SubScreen.Network -> NetworkFragment()
        SubScreen.Waypoints -> WaypointsFragment()
        SubScreen.Friends -> FriendsFragment()
        SubScreen.Timer -> TimerFragment()
        SubScreen.Screenshot -> ScreenshotFragment()
        SubScreen.Autoclicker -> AutoclickerFragment()
        SubScreen.Relay -> RelayFragment()
        SubScreen.Diagnostics -> DiagnosticsFragment()
        SubScreen.About -> AboutFragment()
    }

    fun openSub(screen: SubScreen) {
        view?.findViewById<FrameLayout>(R.id.client_sub_container) ?: return
        val bar = view?.findViewById<LinearLayout>(R.id.client_sub_bar)
        val actions = view?.findViewById<LinearLayout>(R.id.client_actions)

        // Hide the hub list so the sub-screen owns the screen, and restore it
        // on Back. visibility=gone (not INVISIBLE) keeps the hub off the
        // accessibility tree while a sub-screen is open.
        actions?.visibility = View.GONE
        bar?.visibility = View.VISIBLE
        bar?.removeAllViews()
        // findViewById returned non-null a moment ago via ?: return, so this
        // is the same view; the elvis above proves it exists.
        val barView = bar ?: return

        val density = requireContext().resources.displayMetrics.density
        val back = Button(requireContext(), null, 0, R.style.XykellNavButton).apply {
            text = getString(R.string.action_back, getString(screen.titleRes()))
            minHeight = (48 * density).toInt()
            minimumHeight = (48 * density).toInt()
            contentDescription = getString(R.string.action_back_desc, getString(screen.titleRes()))
            setOnClickListener { closeSub() }
        }
        barView.addView(back)

        childFragmentManager.beginTransaction()
            .replace(R.id.client_sub_container, fragmentFor(screen), screen.name)
            .commit()
    }

    fun closeSub() {
        val container = view?.findViewById<FrameLayout>(R.id.client_sub_container) ?: return
        childFragmentManager.findFragmentById(R.id.client_sub_container)?.let {
            childFragmentManager.beginTransaction().remove(it).commit()
        }
        container.visibility = View.GONE
        view?.findViewById<LinearLayout>(R.id.client_sub_bar)?.visibility = View.GONE
        view?.findViewById<LinearLayout>(R.id.client_actions)?.visibility = View.VISIBLE
    }

    private fun action(titleRes: Int, descRes: Int, onClick: () -> Unit): View {
        val context = requireContext()
        val title = getString(titleRes)
        val description = getString(descRes)
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