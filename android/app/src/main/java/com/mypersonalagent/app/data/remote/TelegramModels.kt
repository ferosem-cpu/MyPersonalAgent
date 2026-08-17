package com.mypersonalagent.app.data.remote

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Direct client for the Telegram Bot API (https://api.telegram.org) - no bridge process,
 * called straight from the phone. Standalone pivot, Phase C. */
@Serializable
data class TelegramSendMessageRequest(
    @SerialName("chat_id") val chatId: String,
    val text: String,
)

@Serializable
data class TelegramSendMessageResponse(
    val ok: Boolean = false,
    val description: String? = null,
)
