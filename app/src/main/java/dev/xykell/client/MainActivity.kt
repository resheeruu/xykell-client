package dev.xykell.client

import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
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
        currentPage = page.coerceIn(0, adapter.count - 1)
        if (usePager) {
            pager.currentItem = currentPage
        }
        updateTitleAndIndicator(currentPage)
    }

    private fun updateTitleAndIndicator(position: Int) {
        screenTitle.text = adapter.getTitle(position)
        pageIndicator.text = "${position + 1} / ${adapter.count}"
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
        val fragments = listOf(
            HomeFragment() to R.string.nav_home,
            VersionsFragment() to R.string.nav_versions,
            ModulesFragment() to R.string.nav_modules,
            HudEditorFragment() to R.string.nav_hud,
            ProfilesFragment() to R.string.nav_profiles,
            ThemesFragment() to R.string.nav_themes,
            KeybindsFragment() to R.string.nav_keybinds,
            SettingsFragment() to R.string.nav_settings,
            AboutFragment() to R.string.nav_about,
            WorldsFragment() to R.string.nav_worlds,
            PacksFragment() to R.string.nav_packs,
            ServersFragment() to R.string.nav_servers,
            AccountsFragment() to R.string.nav_accounts,
        )

        navRow.removeAllViews()
        for ((fragment, stringRes) in fragments) {
            val btn = Button(this, null, 0, R.style.XykellNavButton)
            btn.text = getString(stringRes)
            btn.setOnClickListener {
                val idx = navRow.indexOfChild(btn)
                if (idx >= 0 && idx < adapter.count) {
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
}