@file:OptIn(ExperimentalSerializationApi::class)

package com.nodeloc.app.core.network

import android.os.Build
import android.util.Log
import com.nodeloc.app.BuildConfig
import com.nodeloc.app.core.util.rethrowIfCancellation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNamingStrategy
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Dispatcher
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resumeWithException

/**
 * Transport for the site's native `/api/v1` interface.
 *
 * Owns the HTTP clients, credentials and the error envelope; knows nothing about
 * any particular endpoint's shape. [DiscourseClient] sits on top and turns these
 * answers back into the models the screens were written against, which is what
 * lets the UI stay as it is while everything underneath it changed.
 */
class NodelocApi(
    val auth: DiscourseAuth,
    val cookieJar: PersistentCookieJar,
    val baseUrl: String = DiscourseConfig.BASE_URL,
) {
    private val baseHost: String = baseUrl.toHttpUrl().host

    /**
     * The server tells generations of this app apart by the User-Agent: OkHttp's
     * default (`okhttp/…`) is how it recognises a 1.x build and answers 410
     * "please update" to anything that looks like a Discourse path. A 2.x build
     * sending the default would be told the same.
     */
    private val identify = Interceptor { chain ->
        val original = chain.request()
        val builder = original.newBuilder()
        // Sign-in answers carry the token only when the request does not look
        // like a browser's; OkHttp never adds these, and nothing here may.
        builder.removeHeader("Sec-Fetch-Mode").removeHeader("Sec-Fetch-Site").removeHeader("Sec-Fetch-Dest")
        if (original.header("User-Agent").isNullOrEmpty() || original.header("User-Agent")!!.startsWith("okhttp/")) {
            builder.header("User-Agent", USER_AGENT)
        }
        // The token goes only to the site itself. Coil and the video player share
        // this client, and an image hosted elsewhere must never see it.
        val token = auth.token
        if (token != null && original.url.host == baseHost && original.header("Authorization") == null) {
            builder.header("Authorization", "Bearer $token")
        }
        if (original.header("Accept-Language") == null) {
            builder.header("Accept-Language", Locale.getDefault().toLanguageTag())
        }
        chain.proceed(builder.build())
    }

    val http: OkHttpClient = OkHttpClient.Builder()
        .cookieJar(cookieJar)
        .addInterceptor(identify)
        // Every request in the app, plus all of Coil's image loads, goes to one
        // host; the default of 5 per host throttles the whole app.
        .dispatcher(Dispatcher().apply { maxRequestsPerHost = 15 })
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        // readTimeout only bounds the gap between bytes; a slow trickle could
        // otherwise hang a screen indefinitely.
        .callTimeout(45, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    /**
     * Writes never replay: OkHttp resends after a connection reset when it judges
     * it recoverable, which for a POST means a reply posted twice or a tip paid
     * twice. The flag is client-wide, so writes get a client of their own.
     */
    private val writeHttp: OkHttpClient = http.newBuilder()
        .retryOnConnectionFailure(false)
        .writeTimeout(60, TimeUnit.SECONDS)
        .callTimeout(120, TimeUnit.SECONDS)
        .build()

    /**
     * The event stream is meant to stay open, so it must not hold one of the
     * foreground dispatcher's slots, and its read timeout has to outlast the
     * server's 25-second heartbeat.
     */
    val streamHttp: OkHttpClient = http.newBuilder()
        .readTimeout(70, TimeUnit.SECONDS)
        .callTimeout(0, TimeUnit.SECONDS)
        .dispatcher(Dispatcher())
        .build()

    enum class Channel { Read, Write }

    fun url(path: String, query: List<Pair<String, String>> = emptyList()): HttpUrl {
        val builder = "$baseUrl/${path.trimStart('/')}".toHttpUrl().newBuilder()
        query.forEach { (name, value) -> builder.addQueryParameter(name, value) }
        return builder.build()
    }

    fun requestBuilder(url: HttpUrl): Request.Builder = Request.Builder()
        .url(url)
        .header("Accept", "application/json")
        // Which credential this request went out under, so a late 401 about a
        // session already replaced cannot sign the new one out.
        .tag(AuthGeneration::class.java, AuthGeneration(auth.generation))

    suspend fun perform(request: Request, channel: Channel = Channel.Write): String {
        val client = if (channel == Channel.Read) http else writeHttp
        val (response, body) = try {
            client.newCall(request).awaitWithBody()
        } catch (error: IOException) {
            throw DiscourseError.Transport(error)
        }
        if (!response.isSuccessful) {
            val error = classify(response, body, request)
            if (error.isSessionGone && (request.header("Authorization") != null || auth.isAuthenticated)) {
                val stamped = request.tag(AuthGeneration::class.java)?.value
                if (stamped != null) auth.noteSessionRejected(request.url.encodedPath, stamped)
            }
            throw error
        }
        return body
    }

    suspend fun getRaw(path: String, query: List<Pair<String, String>> = emptyList()): String =
        perform(requestBuilder(url(path, query)).get().build(), Channel.Read)

    suspend inline fun <reified T> get(path: String, query: List<Pair<String, String>> = emptyList()): T =
        decode(getRaw(path, query))

    /** A JSON write. Go's decoder rejects an empty body, so a bare verb still sends `{}`. */
    suspend fun sendRaw(
        method: String,
        path: String,
        body: JsonElement? = null,
        query: List<Pair<String, String>> = emptyList(),
        /** A credential other than the current one, for the last calls of a signed-out session. */
        bearer: String? = null,
        headers: Map<String, String> = emptyMap(),
    ): String {
        val payload = (body ?: JsonObject(emptyMap())).toString().toRequestBody(JSON_MEDIA)
        val builder = requestBuilder(url(path, query)).method(method, if (method == "GET") null else payload)
        bearer?.let { builder.header("Authorization", "Bearer $it") }
        headers.forEach { (name, value) -> builder.header(name, value) }
        return perform(builder.build(), Channel.Write)
    }

    suspend inline fun <reified T> send(method: String, path: String, body: JsonElement? = null): T =
        decode(sendRaw(method, path, body))

    suspend fun multipart(
        path: String,
        fields: Map<String, String>,
        fileName: String,
        mimeType: String,
        body: RequestBody,
    ): String {
        val builder = MultipartBody.Builder().setType(MultipartBody.FORM)
        fields.forEach { (name, value) -> builder.addFormDataPart(name, value) }
        builder.addFormDataPart("file", fileName, body)
        return perform(requestBuilder(url(path)).post(builder.build()).build(), Channel.Write)
    }

    fun fileBody(file: java.io.File, mimeType: String, onProgress: ((Float) -> Unit)?): RequestBody =
        if (onProgress == null) file.asRequestBody(mimeType.toMediaType())
        else ProgressRequestBody(file, mimeType.toMediaType(), onProgress)

    fun bytesBody(data: ByteArray, mimeType: String): RequestBody = data.toRequestBody(mimeType.toMediaType())

    /** Raw bytes from an absolute URL, e.g. an uploaded image fetched back for editing. */
    suspend fun fetchBytes(absoluteUrl: String): ByteArray {
        val request = Request.Builder().url(absoluteUrl).get().build()
        val response = try {
            http.newCall(request).await()
        } catch (error: IOException) {
            throw DiscourseError.Transport(error)
        }
        return response.use {
            if (!it.isSuccessful) throw DiscourseError.BadResponse(it.code)
            withContext(Dispatchers.IO) { it.body.bytes() }
        }
    }

    /** External hosts (the GIF provider) go through the same cancellable path. */
    suspend fun getExternal(target: HttpUrl): String =
        perform(Request.Builder().url(target).header("Accept", "application/json").get().build(), Channel.Read)

    @PublishedApi
    internal inline fun <reified T> decode(body: String): T = try {
        json.decodeFromString<T>(body)
    } catch (error: Throwable) {
        error.rethrowIfCancellation()
        if (BuildConfig.DEBUG) Log.e(TAG, "decode ${T::class.simpleName} failed: ${error.message}")
        throw DiscourseError.Decoding(error)
    }

    /**
     * The server's envelope is `{"error": {"code", "message", "fields"}}`; the
     * message is already in the reader's language (Accept-Language above), so a
     * rejected write can show it as-is.
     */
    private fun classify(response: Response, body: String, request: Request): DiscourseError {
        val cfMitigated = response.header("cf-mitigated")
        val server = response.header("Server")?.lowercase().orEmpty()
        val contentType = response.header("Content-Type")?.lowercase().orEmpty()
        val challenged = cfMitigated != null ||
            (response.code == 403 && server.contains("cloudflare") && contentType.contains("text/html"))
        if (BuildConfig.DEBUG) {
            Log.w(TAG, "${response.code} ${request.method} ${request.url}\n  body: ${body.take(200)}")
        }
        if (challenged) return DiscourseError.Challenged
        val retryAfter = response.header("Retry-After")?.trim()?.toIntOrNull()
        return DiscourseError.BadResponse(response.code, body.take(200), parseFault(response.code, body, retryAfter))
    }

    internal fun parseFault(code: Int, body: String, retryAfter: Int?): ServerFault? {
        val root = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull()
        val envelope = root?.get("error") as? JsonObject
        val type = (envelope?.get("code") as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotEmpty() }
        val quotable = code in 400..499 && type !in NON_QUOTABLE_FAULTS
        val messages = if (!quotable || envelope == null) {
            emptyList()
        } else {
            val main = listOfNotNull((envelope["message"] as? JsonPrimitive)?.contentOrNull)
            val fields = (envelope["fields"] as? JsonObject)?.values
                ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
                .orEmpty()
            (main + fields).mapNotNull { it.trim().takeIf(String::isNotEmpty) }.distinct().take(3).map { it.take(300) }
        }
        // Kept by name as well, so a form can put each sentence under its own field.
        val fieldMessages = if (!quotable) emptyMap() else (envelope?.get("fields") as? JsonObject)
            ?.mapNotNull { (key, value) -> (value as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf(String::isNotEmpty)?.let { key to it.take(300) } }
            ?.toMap()
            .orEmpty()
        if (messages.isEmpty() && type == null && retryAfter == null) return null
        return ServerFault(messages, type, retryAfter, fieldMessages)
    }

    /** Headers only; the caller consumes the body itself (the event stream does). */
    suspend fun Call.await(): Response = suspendCancellableCoroutine { continuation ->
        continuation.invokeOnCancellation { runCatching { cancel() } }
        enqueue(object : Callback {
            override fun onResponse(call: Call, response: Response) {
                continuation.resume(response) { _, value, _ -> runCatching { value.close() } }
            }

            override fun onFailure(call: Call, e: IOException) {
                if (!continuation.isCancelled) continuation.resumeWithException(e)
            }
        })
    }

    /**
     * The body is read on OkHttp's callback thread rather than afterwards: only
     * `invokeOnCancellation` fires the moment a screen goes away, so this is
     * what lets a cancelled request actually stop reading.
     */
    private suspend fun Call.awaitWithBody(): Pair<Response, String> =
        suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation { runCatching { cancel() } }
            enqueue(object : Callback {
                override fun onResponse(call: Call, response: Response) {
                    try {
                        val body = response.use { it.body.string() }
                        continuation.resume(response to body) { _, _, _ -> }
                    } catch (error: Throwable) {
                        // OkHttp will not call onFailure after onResponse, so
                        // anything escaping here would strand the coroutine.
                        runCatching { response.close() }
                        if (!continuation.isCancelled) continuation.resumeWithException(error)
                    }
                }

                override fun onFailure(call: Call, e: IOException) {
                    if (!continuation.isCancelled) continuation.resumeWithException(e)
                }
            })
        }

    companion object {
        @PublishedApi internal const val TAG = "NodelocAPI"

        val JSON_MEDIA = "application/json".toMediaType()

        val USER_AGENT: String =
            "NodeLoc/${BuildConfig.VERSION_NAME} (Android ${Build.VERSION.RELEASE ?: "?"}; ${BuildConfig.VERSION_CODE})"

        /** Our own strings already say these better, in the device's language. */
        private val NON_QUOTABLE_FAULTS = setOf("unauthorized", "not_found")

        val json = Json {
            ignoreUnknownKeys = true
            explicitNulls = false
            isLenient = true
            coerceInputValues = true
            namingStrategy = JsonNamingStrategy.SnakeCase
        }
    }
}
