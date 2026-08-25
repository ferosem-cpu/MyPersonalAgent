package com.mypersonalagent.app.di

import android.content.Context
import androidx.room.Room
import com.mypersonalagent.app.data.local.AppDatabase
import com.mypersonalagent.app.data.local.ContactDao
import com.mypersonalagent.app.data.local.EntryDao
import com.mypersonalagent.app.data.local.MIGRATION_1_2
import com.mypersonalagent.app.data.local.MIGRATION_2_3
import com.mypersonalagent.app.data.local.FileDao
import com.mypersonalagent.app.data.local.MIGRATION_3_4
import com.mypersonalagent.app.data.local.NoteDao
import com.mypersonalagent.app.data.local.TodoDao
import com.mypersonalagent.app.data.remote.ApiService
import com.mypersonalagent.app.data.remote.AuthInterceptor
import com.mypersonalagent.app.data.remote.BaseUrlInterceptor
import com.mypersonalagent.app.data.remote.TelegramService
import com.mypersonalagent.app.data.repo.SettingsRepository
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.Scope
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.util.concurrent.TimeUnit
import javax.inject.Named
import javax.inject.Singleton

/** drive.file - least-privilege scope: the app only ever sees files it creates itself,
 * never the rest of the user's Drive. */
const val DRIVE_FILE_SCOPE = "https://www.googleapis.com/auth/drive.file"

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideSettingsRepository(@ApplicationContext context: Context): SettingsRepository =
        SettingsRepository(context)

    @Provides
    @Singleton
    fun provideOkHttpClient(
        authInterceptor: AuthInterceptor,
        baseUrlInterceptor: BaseUrlInterceptor,
    ): OkHttpClient {
        val logging = HttpLoggingInterceptor().apply { level = HttpLoggingInterceptor.Level.BASIC }
        return OkHttpClient.Builder()
            .addInterceptor(baseUrlInterceptor)
            .addInterceptor(authInterceptor)
            .addInterceptor(logging)
            // Chat can run several tool-call rounds against a slow LLM provider - the default
            // 10s read timeout would kill a normal reply before it finishes.
            .readTimeout(120, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    @Provides
    @Singleton
    fun provideRetrofit(client: OkHttpClient): Retrofit {
        val json = Json { ignoreUnknownKeys = true }
        return Retrofit.Builder()
            // Placeholder host - BaseUrlInterceptor rewrites it per-request from Settings.
            .baseUrl("http://localhost/")
            .client(client)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
    }

    @Provides
    @Singleton
    fun provideApiService(retrofit: Retrofit): ApiService = retrofit.create(ApiService::class.java)

    // --- Telegram: a fully separate OkHttp/Retrofit stack, deliberately not sharing the
    // agent-server OkHttpClient above (which rewrites host via BaseUrlInterceptor and
    // attaches AuthInterceptor - neither is relevant or safe to apply to Telegram calls). ---

    @Provides
    @Singleton
    @Named("telegram")
    fun provideTelegramOkHttpClient(): OkHttpClient {
        val logging = HttpLoggingInterceptor().apply { level = HttpLoggingInterceptor.Level.BASIC }
        return OkHttpClient.Builder().addInterceptor(logging).build()
    }

    @Provides
    @Singleton
    @Named("telegram")
    fun provideTelegramRetrofit(@Named("telegram") client: OkHttpClient): Retrofit {
        val json = Json { ignoreUnknownKeys = true }
        return Retrofit.Builder()
            .baseUrl("https://api.telegram.org/") // unused per-call (absolute @Url), but required by Retrofit
            .client(client)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
    }

    @Provides
    @Singleton
    @Named("telegram")
    fun provideTelegramService(@Named("telegram") retrofit: Retrofit): TelegramService =
        retrofit.create(TelegramService::class.java)

    // --- Anthropic: same isolation reasoning as Telegram above - own OkHttpClient, no
    // BaseUrlInterceptor/AuthInterceptor. Raw OkHttp (not Retrofit) since ChatRepository
    // builds/parses the request/response as dynamic JSON (Anthropic's content blocks are
    // polymorphic - text / tool_use / tool_result - which doesn't map cleanly to fixed
    // @Serializable data classes without a lot of ceremony for little benefit here). ---

    @Provides
    @Singleton
    @Named("anthropic")
    fun provideAnthropicOkHttpClient(): OkHttpClient {
        val logging = HttpLoggingInterceptor().apply { level = HttpLoggingInterceptor.Level.BASIC }
        return OkHttpClient.Builder()
            .addInterceptor(logging)
            // Tool-use loops can take several LLM round trips; default 10s read timeout is too short.
            .readTimeout(120, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    // --- Google Drive backup (Phase E): drive.file scope only, obtained via classic
    // GoogleSignInClient + GoogleAuthUtil (not the newer Credential Manager - that API is
    // for authentication/ID tokens, not OAuth *authorization* scopes like Drive access).
    // Talks to the Drive v3 REST API directly over OkHttp, same lightweight pattern as
    // Telegram/Anthropic above - no heavy google-api-client dependency needed. ---

    @Provides
    @Singleton
    fun provideGoogleSignInClient(@ApplicationContext context: Context): GoogleSignInClient {
        val options = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestScopes(Scope(DRIVE_FILE_SCOPE))
            .requestEmail()
            .build()
        return GoogleSignIn.getClient(context, options)
    }

    @Provides
    @Singleton
    @Named("drive")
    fun provideDriveOkHttpClient(): OkHttpClient {
        val logging = HttpLoggingInterceptor().apply { level = HttpLoggingInterceptor.Level.BASIC }
        return OkHttpClient.Builder().addInterceptor(logging).build()
    }

    @Provides
    @Singleton
    fun provideAppDatabase(@ApplicationContext context: Context): AppDatabase =
        Room.databaseBuilder(context, AppDatabase::class.java, "mypersonalagent.db")
            .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
            .build()

    @Provides
    fun provideTodoDao(db: AppDatabase): TodoDao = db.todoDao()

    @Provides
    fun provideEntryDao(db: AppDatabase): EntryDao = db.entryDao()

    @Provides
    fun provideNoteDao(db: AppDatabase): NoteDao = db.noteDao()

    @Provides
    fun provideContactDao(db: AppDatabase): ContactDao = db.contactDao()

    @Provides
    fun provideFileDao(db: AppDatabase): FileDao = db.fileDao()
}
