package dev.xykell.client.ui

import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import androidx.fragment.app.FragmentStatePagerAdapter

/**
 * Adapter for the 7 primary Xykell screens using ViewPager v1.
 * Order: Home, Client, HUD, Worlds, Servers, Content, Profile
 */
class ScreenPagerAdapter(fm: FragmentManager, behavior: Int) : FragmentStatePagerAdapter(fm, behavior) {

    private val screenFactories: List<() -> Fragment> = listOf(
        { HomeFragment() },           // 0: Home
        { ClientFragment() },         // 1: Client
        { HudEditorFragment() },      // 2: HUD
        { WorldsFragment() },         // 3: Worlds
        { ServersFragment() },        // 4: Servers
        { PacksFragment() },          // 5: Content
        { ProfilesFragment() },       // 6: Profile
        { UpdateFragment() },         // 7: Update
    )

    private val screenTitles = listOf(
        "Home", "Client", "HUD", "Worlds", "Servers", "Content", "Profile", "Update"
    )

    override fun getCount(): Int = screenFactories.size

    override fun getItem(position: Int): Fragment = screenFactories[position]()

    fun getTitle(position: Int): String = screenTitles[position]

    override fun getPageTitle(position: Int): CharSequence? = screenTitles[position]
}