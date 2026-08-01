package com.mypersonalagent.app.data.remote

import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Path
import retrofit2.http.Query

interface ApiService {
    @GET("api/v1/health")
    suspend fun health(): HealthDto

    @GET("api/v1/todos")
    suspend fun listTodos(@Query("status") status: String = "all"): List<TodoDto>

    @POST("api/v1/todos")
    suspend fun createTodo(@Body todo: TodoDto): TodoDto

    @PUT("api/v1/todos/{id}")
    suspend fun updateTodo(@Path("id") id: String, @Body todo: TodoDto): TodoDto

    @POST("api/v1/todos/{id}/complete")
    suspend fun completeTodo(@Path("id") id: String): TodoDto

    @DELETE("api/v1/todos/{id}")
    suspend fun deleteTodo(@Path("id") id: String): TodoDto

    @GET("api/v1/entries")
    suspend fun listEntries(@Query("since") since: String? = null): List<EntryDto>

    @POST("api/v1/entries")
    suspend fun createEntry(@Body entry: EntryDto): EntryDto

    @GET("api/v1/memory")
    suspend fun listNotes(): List<NoteDto>

    @GET("api/v1/memory/recall")
    suspend fun recallNotes(@Query("q") query: String): List<NoteDto>

    @POST("api/v1/memory")
    suspend fun createNote(@Body note: NoteDto): NoteDto

    @GET("api/v1/contacts")
    suspend fun listContacts(@Query("q") query: String? = null): List<ContactDto>

    @POST("api/v1/contacts")
    suspend fun createContact(@Body contact: ContactDto): ContactDto

    @POST("api/v1/sync")
    suspend fun sync(@Body req: SyncRequest): SyncResponse

    @POST("api/v1/chat")
    suspend fun chat(@Body req: ChatRequestDto): ChatResponseDto
}
