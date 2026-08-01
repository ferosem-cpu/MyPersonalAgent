package com.mypersonalagent.app.data.remote

import com.mypersonalagent.app.data.repo.SettingsRepository
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.Response
import javax.inject.Inject

/**
 * Rewrites every request's scheme/host/port to whatever the user configured in Settings,
 * so the server address can change at runtime without rebuilding Retrofit/OkHttp.
 */
class BaseUrlInterceptor @Inject constructor(
    private val settings: SettingsRepository,
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val configured = runBlocking { settings.serverUrl.firstOrNull() }
        val original = chain.request()
        if (configured.isNullOrBlank()) return chain.proceed(original)

        val configuredUrl = configured.toHttpUrlOrNull() ?: return chain.proceed(original)
        val newUrl = original.url.newBuilder()
            .scheme(configuredUrl.scheme)
            .host(configuredUrl.host)
            .port(configuredUrl.port)
            .build()
        return chain.proceed(original.newBuilder().url(newUrl).build())
    }
}
