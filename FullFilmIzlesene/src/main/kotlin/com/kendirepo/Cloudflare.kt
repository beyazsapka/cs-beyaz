package com.kendirepo

import com.lagradost.cloudstream3.network.CloudflareKiller
import okhttp3.Interceptor
import okhttp3.Response

/**
 * Sayfa Cloudflare dogrulama ekrani dondururse WebView tabanli
 * CloudflareKiller ile istegi tekrar dener. Normal yanitlarda ek maliyet yok.
 */
class CloudflareInterceptor(private val killer: CloudflareKiller) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val response = chain.proceed(chain.request())
        val body = runCatching { response.peekBody(2L * 1024 * 1024).string() }.getOrDefault("")
        val challenged = body.contains("Just a moment") ||
            body.contains("Attention Required") ||
            body.contains("cf-mitigated") ||
            body.contains("Checking your browser")
        return if (challenged) killer.intercept(chain) else response
    }
}
