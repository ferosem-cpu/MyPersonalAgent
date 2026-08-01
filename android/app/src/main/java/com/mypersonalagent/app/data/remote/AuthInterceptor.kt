package com.mypersonalagent.app.data.remote

import com.mypersonalagent.app.data.repo.SettingsRepository
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.Response
import javax.inject.Inject

class AuthInterceptor @Inject constructor(
    private val settings: SettingsRepository,
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val token = runBlocking { settings.apiToken.firstOrNull() }
        val request = chain.request().newBuilder()
        if (!token.isNullOrBlank()) {
            request.addHeader("X-API-Key", token)
        }
        return chain.proceed(request.build())
    }
}
