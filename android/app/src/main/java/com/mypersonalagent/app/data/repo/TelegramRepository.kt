package com.mypersonalagent.app.data.repo

import com.mypersonalagent.app.data.remote.TelegramSendMessageRequest
import com.mypersonalagent.app.data.remote.TelegramService
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton

/** Best-effort supplementary reminder channel - the local Android notification (ReminderNotifier)
 * always fires regardless of whether Telegram is configured or reachable. */
@Singleton
class TelegramRepository @Inject constructor(
    @Named("telegram") private val api: TelegramService,
    private val settings: SettingsRepository,
) {
    /** Returns true if a message was actually sent (token + chat id configured and the call succeeded). */
    suspend fun sendMessage(text: String): Boolean {
        val token = settings.telegramBotToken.first()?.trim()
        val chatId = settings.telegramChatId.first()?.trim()
        if (token.isNullOrBlank() || chatId.isNullOrBlank()) return false
        return runCatching {
            val resp = api.sendMessage(
                url = "https://api.telegram.org/bot$token/sendMessage",
                body = TelegramSendMessageRequest(chatId = chatId, text = text),
            )
            resp.ok
        }.getOrDefault(false)
    }
}
