package com.mypersonalagent.app.di

import android.content.Context
import androidx.room.Room
import com.mypersonalagent.app.data.local.AppDatabase
import com.mypersonalagent.app.data.local.EntryDao
import com.mypersonalagent.app.data.local.MIGRATION_1_2
import com.mypersonalagent.app.data.local.TodoDao
import com.mypersonalagent.app.data.remote.ApiService
import com.mypersonalagent.app.data.remote.AuthInterceptor
import com.mypersonalagent.app.data.remote.BaseUrlInterceptor
import com.mypersonalagent.app.data.repo.SettingsRepository
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
import javax.inject.Singleton

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

    @Provides
    @Singleton
    fun provideAppDatabase(@ApplicationContext context: Context): AppDatabase =
        Room.databaseBuilder(context, AppDatabase::class.java, "mypersonalagent.db")
            .addMigrations(MIGRATION_1_2)
            .build()

    @Provides
    fun provideTodoDao(db: AppDatabase): TodoDao = db.todoDao()

    @Provides
    fun provideEntryDao(db: AppDatabase): EntryDao = db.entryDao()
}
