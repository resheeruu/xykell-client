package dev.xykell.client

import android.os.Bundle
import android.view.KeyEvent
import android.widget.Button
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import dev.xykell.client.ui.AboutFragment
import dev.xykell.client.ui.AccountsFragment
import dev.xykell.client.ui.HomeFragment
import dev.xykell.client.ui.HudEditorFragment
import dev.xykell.client.ui.KeybindCapture
import dev.xykell.client.ui.KeybindsFragment
import dev.xykell.client.ui.ModulesFragment
import dev.xykell.client.ui.PacksFragment
import dev.xykell.client.ui.ProfilesFragment
import dev.xykell.client.ui.ServersFragment
import dev.xykell.client.ui.SettingsFragment
import dev.xykell.client.ui.ThemeColors
import dev.xykell.client.ui.ActiveTheme
import dev.xykell.client.ui.ThemesFragment
import dev.xykell.client.ui.VersionsFragment
import dev.xykell.client.ui.WorldsFragment
import org.json.JSONObject

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        applyStoredTheme()
        wire(R.id.nav_home, HomeFragment())
        wire(R.id.nav_versions, VersionsFragment())
        wire(R.id.nav_modules, ModulesFragment())
        wire(R.id.nav_hud, HudEditorFragment())
        wire(R.id.nav_profiles, ProfilesFragment())
        wire(R.id.nav_themes, ThemesFragment())
        wire(R.id.nav_keybinds, KeybindsFragment())
        wire(R.id.nav_settings, SettingsFragment())
        wire(R.id.nav_about, AboutFragment())
        wire(R.id.nav_worlds, WorldsFragment())
        wire(R.id.nav_packs, PacksFragment())
        wire(R.id.nav_servers, ServersFragment())
        wire(R.id.nav_accounts, AccountsFragment())
        if (savedInstanceState == null) {
            show(HomeFragment())
            // Default tab state matches the default screen.
            findViewById<Button>(R.id.nav_home).also {
                it.isSelected = true
                currentNav = it
                findViewById<android.widget.TextView>(R.id.screen_title).text = it.text
            }
            playSplash()
        } else {
            // Rotation: fragment state is restored; hide the splash instantly
            // and rebind currentNav to the chip view-state restored as selected.
            findViewById<android.view.View>(R.id.splash_screen)?.visibility =
                android.view.View.GONE
            val row = findViewById<android.widget.LinearLayout>(R.id.nav_row)
            currentNav = (0 until row.childCount)
                .map { row.getChildAt(it) as Button }
                .firstOrNull { it.isSelected }
        }
    }

    private var currentNav: Button? = null

    private fun wire(buttonId: Int, screen: Fragment) {
        val button = findViewById<Button>(buttonId)
        button.setOnClickListener {
            currentNav?.isSelected = false
            button.isSelected = true
            currentNav = button
            findViewById<android.widget.TextView>(R.id.screen_title).text = button.text
            show(screen)
        }
    }

    /** Startup splash: fixed short fade, no fake loading state — the shell
     *  is ready before the first frame. Only on fresh launch; rotation hides
     *  it instantly in onCreate. */
    private fun playSplash() {
        val splash = findViewById<android.view.View>(R.id.splash_screen) ?: return
        splash.alpha = 1f
        splash.animate().alpha(0f).setStartDelay(350).setDuration(250)
            .withEndAction { splash.visibility = android.view.View.GONE }
            .start()
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

    private fun show(screen: Fragment) {
        supportFragmentManager.beginTransaction()
            .replace(R.id.screen_container, screen)
            .commit()
        // Fresh fragment views are inflated from the compiled palette;
        // re-map them to the active theme after the transaction lands.
        findViewById<android.view.View>(R.id.screen_container).post {
            ActiveTheme.bindFresh(findViewById(R.id.screen_container))
        }
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
