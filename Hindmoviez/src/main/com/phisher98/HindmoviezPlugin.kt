package com.phisher98

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.BasePlugin

@CloudstreamPlugin
class HindmoviezPlugin : BasePlugin() {
    override fun load() {
        // Ye line important hai: Ye teri main scraper class ko register karta hai
        // Dhyan de: Hindmoviez() wo class hai jo MainAPI extend karti hai
        registerMainAPI(Hindmoviez())
    }
}
