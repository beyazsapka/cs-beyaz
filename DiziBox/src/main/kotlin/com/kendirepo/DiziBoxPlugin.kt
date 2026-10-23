package com.kendirepo

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class DiziBoxPlugin : Plugin() {
    override fun load(context: Context) {
        // Bir eklenti birden fazla site (MainAPI) veya extractor kaydedebilir.
        registerMainAPI(DiziBox())
    }
}
