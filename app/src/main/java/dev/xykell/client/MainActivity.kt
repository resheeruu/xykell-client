package dev.xykell.client

import android.os.Bundle
import android.widget.Button
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import dev.xykell.client.ui.AboutFragment
import dev.xykell.client.ui.AccountsFragment
import dev.xykell.client.ui.HomeFragment
import dev.xykell.client.ui.HudEditorFragment
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
        wire(R.id.nav_settings, SettingsFragment())
        wire(R.id.nav_about, AboutFragment())
        wire(R.id.nav_worlds, WorldsFragment())
        wire(R.id.nav_packs, PacksFragment())
        wire(R.id.nav_servers, ServersFragment())
        wire(R.id.nav_accounts, AccountsFragment())
        if (savedInstanceState == null) show(HomeFragment())
    }

    private fun wire(buttonId: Int, screen: Fragment) {
        findViewById<Button>(buttonId).setOnClickListener { show(screen) }
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
