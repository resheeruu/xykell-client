package dev.xykell.client

import android.os.Bundle
import android.widget.Button
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import dev.xykell.client.ui.AboutFragment
import dev.xykell.client.ui.AccountsFragment
import dev.xykell.client.ui.HomeFragment
import dev.xykell.client.ui.ModulesFragment
import dev.xykell.client.ui.PacksFragment
import dev.xykell.client.ui.ProfilesFragment
import dev.xykell.client.ui.ServersFragment
import dev.xykell.client.ui.SettingsFragment
import dev.xykell.client.ui.VersionsFragment
import dev.xykell.client.ui.WorldsFragment

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        wire(R.id.nav_home, HomeFragment())
        wire(R.id.nav_versions, VersionsFragment())
        wire(R.id.nav_modules, ModulesFragment())
        wire(R.id.nav_profiles, ProfilesFragment())
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
    }
}
