package dev.xykell.client

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentStatePagerAdapter
import androidx.viewpager.widget.ViewPager
import dev.xykell.client.ui.AboutFragment
import dev.xykell.client.ui.AccountsFragment
import dev.xykell.client.ui.ClientFragment
import dev.xykell.client.ui.HomeFragment
import dev.xykell.client.ui.HudEditorFragment
import dev.xykell.client.ui.KeybindCapture
import dev.xykell.client.ui.KeybindsFragment
import dev.xykell.client.ui.ModulesFragment
import dev.xykell.client.ui.NonSwipeableViewPager
import dev.xykell.client.ui.PacksFragment
import dev.xykell.client.ui.ProfilesFragment
import dev.xykell.client.ui.ScreenPagerAdapter
import dev.xykell.client.ui.ServersFragment
import dev.xykell.client.ui.SessionFragment
import dev.xykell.client.ui.SettingsFragment
import dev.xykell.client.ui.SplashFragment
import dev.xykell.client.ui.ThemesFragment
import dev.xykell.client.ui.UpdateFragment
import dev.xykell.client.ui.VersionsFragment
import dev.xykell.client.ui.WorldsFragment
import dev.xykell.client.ui.ActiveTheme
import dev.xykell.client.ui.ThemeColors
import org.json.JSONObject

class MainActivity : AppCompatActivity() {

    private lateinit var pager: dev.xykell.client.ui.NonSwipeableViewPager
    private lateinit var adapter: ScreenPagerAdapter
    private lateinit var screenTitle: TextView
    private lateinit var pageIndicator: TextView
    private lateinit var navRowContainer: ViewGroup
    private var currentPage = 0
    private var usePager = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        applyStoredTheme()

        pager = findViewById(R.id.pager)
        screenTitle = findViewById(R.id.screen_title)
        pageIndicator = findViewById(R.id.page_indicator)
        navRowContainer = findViewById(R.id.nav_row_container)

        // Check reduced motion preference
        usePager = !isReducedMotionEnabled()

        adapter = ScreenPagerAdapter(supportFragmentManager, FragmentStatePagerAdapter.BEHAVIOR_RESUME_ONLY_CURRENT_FRAGMENT)
        pager.adapter = adapter

        if (usePager) {
            pager.setSwipeEnabled(true)
            pager.addOnPageChangeListener(object : ViewPager.OnPageChangeListener {
                override fun onPageScrolled(position: Int, positionOffset: Float, positionOffsetPixels: Int) = Unit
                override fun onPageSelected(position: Int) {
                    currentPage = position
                    updateTitleAndIndicator(position)
                }
                override fun onPageScrollStateChanged(state: Int) = Unit
            })
            // Hide the fallback chip row
            navRowContainer.visibility = View.GONE
        } else {
            // Reduced motion: disable pager swiping, show chip fallback
            pager.setSwipeEnabled(false)
            setupChipNavigation()
            navRowContainer.visibility = View.VISIBLE
        }

        if (savedInstanceState == null) {
            // Show animated splash fragment first
            supportFragmentManager.beginTransaction()
                .replace(R.id.screen_container, SplashFragment())
                .commit()
        } else {
            // Rotation: restore state
            val savedPage = savedInstanceState.getInt("current_page", 0)
            showPage(savedPage)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt("current_page", currentPage)
    }

    internal fun showPage(page: Int) {
        // SplashFragment is added into screen_container, which is the same
        // FrameLayout that holds the pager. Setting pager.currentItem does not
        // remove it, so without this the splash stays on top forever showing
        // its last status line ("Ready") with the whole UI hidden underneath.
        val existing = supportFragmentManager.findFragmentById(R.id.screen_container)
        if (existing is SplashFragment) {
            supportFragmentManager.beginTransaction().remove(existing).commitNow()
        }

        currentPage = page.coerceIn(0, adapter.count - 1)
        if (usePager) {
            pager.currentItem = currentPage
        }
        updateTitleAndIndicator(currentPage)
        requestNotificationPermissionOnce()
    }

    /**
     * Ask for notification permission once the splash has handed over.
     *
     * Android 13+ withholds notifications until this is granted, and the relay
     * runs as a foreground service whose notification is the only signal that
     * it is live. Requesting it from showPage rather than onCreate means the
     * prompt appears over real content instead of while the splash is still up.
     */
    private fun requestNotificationPermissionOnce() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
        if (granted) return
        // Only prompt once per install: a denial is remembered here so the
        // system does not keep interrupting the user on every launch.
        if (getSharedPreferences(PREFS_PERMS, MODE_PRIVATE)
                .getBoolean(KEY_NOTIF_ASKED, false)
        ) {
            return
        }
        getSharedPreferences(PREFS_PERMS, MODE_PRIVATE)
            .edit().putBoolean(KEY_NOTIF_ASKED, true).apply()
        ActivityCompat.requestPermissions(
            this,
            arrayOf(Manifest.permission.POST_NOTIFICATIONS),
            REQ_POST_NOTIFICATIONS,
        )
    }

    private fun updateTitleAndIndicator(position: Int) {
        screenTitle.text = adapter.getTitle(position)
        pageIndicator.text = getString(R.string.page_indicator, position + 1, adapter.count)
        pageIndicator.visibility = View.VISIBLE
    }

    /** Navigation methods for sub-screens (Client hub buttons) */
    fun navigateToVersion() = showPage(1) // Client screen handles this internally
    fun navigateToModules() = showPage(1)
    fun navigateToSettings() = showPage(1)
    fun navigateToKeybinds() = showPage(1)
    fun navigateToThemes() = showPage(1)
    fun navigateToAbout() = showPage(1)

    private fun isReducedMotionEnabled(): Boolean {
        val scale = resources.configuration.fontScale
        // Use system reduced motion setting if available (API 29+)
        // For now, check a simple heuristic
        return resources.configuration.fontScale > 1.3f
    }

    private fun setupChipNavigation() {
        val navRow = findViewById<LinearLayout>(R.id.nav_row)
        // Titles, not fragments: each chip indexes ScreenPagerAdapter by
        // position, so this list must stay exactly as long as the adapter and
        // in the same order. A longer list leaves trailing chips dead; that bug
        // hid Worlds/Packs/Servers/Accounts behind 13 chips over 9 pages.
        // Secondary screens stay reachable from the Client hub.
        val titles = listOf(
            R.string.nav_home,
            R.string.nav_client,
            R.string.nav_hud,
            R.string.nav_worlds,
            R.string.nav_servers,
            R.string.nav_packs,
            R.string.nav_profiles,
            R.string.nav_update,
            R.string.nav_session,
            R.string.nav_scripts,
        )
        if (titles.size != adapter.count) {
            throw IllegalStateException(
                "nav chips (${titles.size}) must match pager pages (${adapter.count})",
            )
        }

        navRow.removeAllViews()
        for (stringRes in titles) {
            val btn = Button(this, null, 0, R.style.XykellNavButton)
            btn.text = getString(stringRes)
            btn.setOnClickListener {
                val idx = navRow.indexOfChild(btn)
                if (idx in 0 until adapter.count) {
                    showPage(idx)
                }
            }
            navRow.addView(btn)
        }

        // Set initial selected state
        navRow.getChildAt(currentPage)?.let {
            it.isSelected = true
        }
    }

    /** Startup splash is now handled by SplashFragment with animated sequence.
     *  This method is kept for compatibility but no longer used. */
    @Deprecated("Use SplashFragment instead")
    private fun playSplash() {
        // Legacy splash kept for reference; SplashFragment handles animation now.
    }

    /** Keybind capture bridge (Batch 13): while the keybind editor holds a
     *  one-shot sink, the next discrete host keycode is consumed and bound
     *  — no keystroke content is recorded, only the abstract code. With no
     *  sink registered this is the default dispatch, unchanged. */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val sink = KeybindCapture.sink
        if (sink != null && event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
            return sink(event.keyCode)
        }
        return super.dispatchKeyEvent(event)
    }

    /** Startup theme restore: read the validated client.theme setting and
     *  apply its tokens. Unreadable setting or missing bridge keeps the
     *  compiled default palette — never a crash, never a fake theme. */
    private fun applyStoredTheme() {
        try {
            val values = JSONObject(NativeSettings.getValues(NativeSettings.root(this)))
            val name = values.optJSONObject("client")?.optString("theme", "").orEmpty()
            if (name.isEmpty() || name == ThemeColors.DEFAULT.name) return
            val tokens = NativeThemes.tokens(name)?.let { ThemeColors.from(it) } ?: return
            ActiveTheme.apply(findViewById(android.R.id.content), tokens)
        } catch (e: Exception) {
            // Bridge unavailable: default palette stays.
        }
    }

    companion object {
        private const val PREFS_PERMS = "xykell_perms"
        private const val KEY_NOTIF_ASKED = "notif_asked"
        private const val REQ_POST_NOTIFICATIONS = 4201
    }
}
