package com.mypersonalagent.app.data.remote

import retrofit2.http.Body
import retrofit2.http.POST
import retrofit2.http.Url

/** Uses an absolute @Url per call (bot token is part of the path: bot{token}/sendMessage)
 * so this Retrofit instance needs no BaseUrlInterceptor/AuthInterceptor rewriting - see
 * AppModule's dedicated Telegram Retrofit provider. */
interface TelegramService {
    @POST
    suspend fun sendMessage(@Url url: String, @Body body: TelegramSendMessageRequest): TelegramSendMessageResponse
}
