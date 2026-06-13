package com.phisher98

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

data class Domains(val hindmoviez: String)

@CloudstreamPlugin
class HindmoviezPlugin : Plugin() {
    
    companion object {
        fun getDomains(): Domains {
            return Domains(hindmoviez = "https://kmmovies.life/")
        }
    }
    
    override fun load() {
        registerMainAPI(Hindmoviez())
    }
}
