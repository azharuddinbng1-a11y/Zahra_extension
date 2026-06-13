package com.phisher98

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin  // ← BasePlugin → Plugin

@CloudstreamPlugin
class HindmoviezPlugin : Plugin() {  // ← BasePlugin() → Plugin()
    override fun load() {
        registerMainAPI(Hindmoviez())
    }
}
