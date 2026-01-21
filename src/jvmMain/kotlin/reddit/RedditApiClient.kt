package reddit

import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.plugins.logging.*
import io.ktor.client.request.*
import io.ktor.client.request.forms.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.decodeFromJsonElement
import kotlin.time.Duration.Companion.seconds
import kotlin.time.measureTimedValue

private val logger = KotlinLogging.logger("MarkovBaj:RedditApiClient")

/**
 * Reddit API client using Ktor HTTP client
 * Replaces JRAW library with direct API calls
 * 
 * Uses OAuth2 "script" authentication for bots
 */
class RedditApiClient(
    private val clientId: String,
    private val clientSecret: String,
    private val username: String,
    private val password: String,
    private val userAgent: String,
    var logHttp: Boolean = false
) {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

    private val httpClient = HttpClient(CIO) {
        install(ContentNegotiation) {
            json(json)
        }

        install(HttpTimeout) {
            requestTimeoutMillis = 30_000
            connectTimeoutMillis = 10_000
        }

        install(Logging) {
            logger = object : Logger {
                override fun log(message: String) {
                    KotlinLogging.logger("MarkovBaj:HttpClient").debug { message }
                }
            }
            level = LogLevel.INFO
        }

        defaultRequest {
            header("User-Agent", userAgent)
        }
    }

    private var accessToken: String? = null
    private var tokenExpiration: kotlin.time.Instant = kotlin.time.Instant.DISTANT_PAST
    private val tokenMutex = Mutex()
    private val rateLimitRegex = Regex("""(\d+)\s*(minute|second|millisecond)s?""", RegexOption.IGNORE_CASE)
    private var lastRateLimitInfo: RateLimitInfo? = null

    // === Authentication ===

    /**
     * Authenticate using OAuth2 "script" flow (for personal use bots)
     */
    suspend fun authenticate(): Boolean {
        return tokenMutex.withLock {
            val authUrl = "https://www.reddit.com/api/v1/access_token"
            logger.debug { "[authenticate] Starting authentication request to: $authUrl" }
            
            try {
                val (response, duration) = measureTimedValue {
                    httpClient.submitForm(
                        url = authUrl,
                        formParameters = parameters {
                            append("grant_type", "password")
                            append("username", username)
                            append("password", password)
                        }
                    ) {
                        basicAuth(clientId, clientSecret)
                    }
                }
                
                logger.debug { "[authenticate] Completed in ${duration.inWholeMilliseconds}ms, status: ${response.status}" }

                if (!response.status.isSuccess()) {
                    logger.error { "[authenticate] Failed: ${response.status}" }
                    return@withLock false
                }

                val tokenResponse = response.body<RedditTokenResponse>()
                accessToken = tokenResponse.accessToken
                tokenExpiration = kotlin.time.Clock.System.now() + tokenResponse.expiresIn.seconds - 60.seconds

                logger.debug { "[authenticate] Success, token expires in ${tokenResponse.expiresIn}s" }

                true
            } catch (e: java.net.ConnectException) {
                logger.error { "[authenticate] Connection refused to $authUrl - is the network available? Host may be unreachable." }
                throw e
            } catch (e: java.net.UnknownHostException) {
                logger.error { "[authenticate] DNS resolution failed for $authUrl - check network/DNS settings" }
                throw e
            } catch (e: Exception) {
                logger.error(e) { "[authenticate] Failed with ${e::class.simpleName}: ${e.message}" }
                throw e
            }
        }
    }

    private suspend fun ensureAuthenticated() {
        if (accessToken == null || kotlin.time.Clock.System.now() >= tokenExpiration) {
            authenticate()
        }
    }

    private suspend fun authorizedRequest(
        operationName: String = "unknown",
        maxRetries: Int = 2,
        block: HttpRequestBuilder.() -> Unit
    ): HttpResponse {
        var requestUrl = "unknown"
        var lastException: Exception? = null

        for (attempt in 0..maxRetries) {
            ensureAuthenticated()
            logger.debug { "[$operationName] Starting request (attempt ${attempt + 1}/${maxRetries + 1})..." }

            try {
                val (response, duration) = measureTimedValue {
                    httpClient.request {
                        header("Authorization", "Bearer $accessToken")
                        block()
                        requestUrl = url.buildString()
                        println("DEBUG [$operationName] Request URL: $requestUrl")
                        logger.info { "[$operationName] Request URL: $requestUrl" }
                    }
                }
                logger.debug { "[$operationName] Completed in ${duration.inWholeMilliseconds}ms, status: ${response.status}" }
                updateRateLimitInfo(response)

                if (response.status.value in 500..599 && attempt < maxRetries) {
                    val backoffSeconds = 1 shl attempt
                    logger.warn { "[$operationName] Server error ${response.status}, retrying in ${backoffSeconds}s..." }
                    delay(backoffSeconds.seconds)
                    continue
                }

                return response
            } catch (e: io.ktor.client.plugins.HttpRequestTimeoutException) {
                lastException = e
                if (attempt < maxRetries) {
                    val backoffSeconds = 1 shl attempt
                    logger.warn { "[$operationName] Request timeout to $requestUrl, retrying in ${backoffSeconds}s..." }
                    delay(backoffSeconds.seconds)
                    continue
                }
                logger.error { "[$operationName] Request timeout to $requestUrl" }
                throw e
            } catch (e: java.net.ConnectException) {
                logger.error { "[$operationName] Connection refused to $requestUrl - is the network available? Host may be unreachable." }
                throw e
            } catch (e: java.net.UnknownHostException) {
                logger.error { "[$operationName] DNS resolution failed to $requestUrl - check network/DNS settings" }
                throw e
            } catch (e: Exception) {
                logger.error { "[$operationName] Request failed to $requestUrl: ${e::class.simpleName} - ${e.message}" }
                throw e
            }
        }

        throw lastException ?: IllegalStateException("[$operationName] Request failed after retries")
    }

    private fun updateRateLimitInfo(response: HttpResponse) {
        val remainingHeader = response.headers["x-ratelimit-remaining"]
        if (remainingHeader == null) {
            val previous = lastRateLimitInfo
            if (previous?.remaining != null && previous.used != null) {
                val nextRemaining = (previous.remaining - 1).coerceAtLeast(0.0)
                val nextUsed = previous.used + 1
                lastRateLimitInfo = previous.copy(remaining = nextRemaining, used = nextUsed)
            }
            return
        }

        val rateLimitInfo = RateLimitInfo(
            remaining = remainingHeader.toDoubleOrNull(),
            used = response.headers["x-ratelimit-used"]?.toIntOrNull(),
            resetSeconds = response.headers["x-ratelimit-reset"]?.toIntOrNull()
        )
        lastRateLimitInfo = rateLimitInfo

        val remaining = rateLimitInfo.remaining
        if (remaining != null && remaining < 10) {
            logger.warn { "Rate limit low: ${"%.2f".format(remaining)} remaining, resets in ${rateLimitInfo.resetSeconds}s" }
        }
    }

    fun getRateLimitInfo(): RateLimitInfo? = lastRateLimitInfo

    // === User Info ===

    suspend fun me(): RedditUser {
        val response = authorizedRequest("me") {
            method = HttpMethod.Get
            url.takeFrom("https://oauth.reddit.com/api/v1/me")
        }
        return response.body()
    }

    // === Subreddit Operations ===

    /**
     * Get new posts from a subreddit
     */
    suspend fun getSubredditPosts(
        subreddit: String,
        sort: String = "new",
        limit: Int = 100
    ): List<RedditSubmission> {
        val response = authorizedRequest("getSubredditPosts($subreddit)") {
            method = HttpMethod.Get
            url.takeFrom("https://oauth.reddit.com/r/$subreddit/$sort")
            url.parameters.append("limit", limit.toString())
        }

        val listing = response.body<RedditListing<RedditSubmission>>()
        return listing.data.children.map { it.data }
    }

    /**
     * Get comments from a subreddit (from /r/{sub}/comments endpoint)
     */
    suspend fun getSubredditComments(
        subreddit: String,
        limit: Int = 100
    ): List<RedditComment> {
        val response = authorizedRequest("getSubredditComments($subreddit)") {
            method = HttpMethod.Get
            url.takeFrom("https://oauth.reddit.com/r/$subreddit/comments")
            url.parameters.append("limit", limit.toString())
        }

        val listing = response.body<RedditListing<RedditComment>>()
        return listing.data.children.map { it.data }
    }

    // === Inbox Operations ===

    /**
     * Get unread inbox messages
     */
    suspend fun getUnreadMessages(limit: Int = 25): List<RedditMessage> {
        val response = authorizedRequest("getUnreadMessages") {
            method = HttpMethod.Get
            url.takeFrom("https://oauth.reddit.com/message/unread")
            url.parameters.append("limit", limit.toString())
        }

        val listing = response.body<RedditListing<RedditMessage>>()
        return listing.data.children.map { it.data }
    }

    /**
     * Mark message(s) as read
     */
    suspend fun markMessagesRead(vararg fullnames: String) {
        authorizedRequest("markMessagesRead") {
            method = HttpMethod.Post
            url.takeFrom("https://oauth.reddit.com/api/read_message")
            setBody(FormDataContent(parameters {
                append("id", fullnames.joinToString(","))
            }))
        }
    }

    // === Comment Operations ===

    /**
     * Reply to a comment or submission
     * @param parentFullname The fullname of the parent (t1_ for comment, t3_ for submission)
     * @param text The reply text
     */
    suspend fun reply(parentFullname: String, text: String, maxRetryWaitSeconds: Int = 300): Boolean {
        return try {
            repeat(3) { attempt ->
                when (val result = tryReplyOnce(parentFullname, text)) {
                    is ReplyResult.Success -> return true
                    is ReplyResult.RateLimited -> {
                        val waitSeconds = result.waitSeconds.coerceAtLeast(1)
                        if (waitSeconds <= maxRetryWaitSeconds) {
                            logger.warn { "Rate limited, waiting ${waitSeconds}s (attempt ${attempt + 1}/3)" }
                            delay((waitSeconds + 1).seconds)
                        } else {
                            logger.error { "Rate limit wait ${waitSeconds}s exceeds max ${maxRetryWaitSeconds}s" }
                            return false
                        }
                    }
                    is ReplyResult.Failed -> {
                        logger.error { "Reply failed: ${result.errors}" }
                        return false
                    }
                }
            }
            false
        } catch (e: Exception) {
            logger.error(e) { "Failed to post reply" }
            false
        }
    }

    private suspend fun tryReplyOnce(parentFullname: String, text: String): ReplyResult {
        val response = authorizedRequest("reply($parentFullname)") {
            method = HttpMethod.Post
            url.takeFrom("https://oauth.reddit.com/api/comment")
            setBody(FormDataContent(parameters {
                append("thing_id", parentFullname)
                append("text", text)
                append("api_type", "json")
            }))
        }

        val result = response.body<RedditCommentResponse>()
        val errors = result.json.errors.map { error ->
            RedditApiError(
                errorType = error.getOrNull(0) ?: "UNKNOWN",
                message = error.getOrNull(1) ?: "",
                field = error.getOrNull(2)
            )
        }

        val rateLimitError = errors.firstOrNull { it.errorType.equals("RATELIMIT", ignoreCase = true) }
        if (rateLimitError != null) {
            val waitSeconds = parseRateLimitSeconds(rateLimitError.message)
            return ReplyResult.RateLimited(waitSeconds, rateLimitError.message)
        }

        if (errors.isNotEmpty()) {
            return ReplyResult.Failed(errors)
        }

        val commentFullname = result.json.data?.things?.firstOrNull()?.data?.fullName
        return ReplyResult.Success(commentFullname)
    }

    private fun parseRateLimitSeconds(message: String): Int {
        val match = rateLimitRegex.find(message) ?: return 60
        val value = match.groupValues[1].toIntOrNull() ?: return 60
        val unit = match.groupValues[2].lowercase()
        return when {
            unit.startsWith("minute") -> value * 60
            unit.startsWith("millisecond") -> 1
            else -> value
        }
    }

    /**
     * Delete a comment
     * @param commentId The comment ID (without t1_ prefix)
     */
    suspend fun deleteComment(commentId: String): Boolean {
        return try {
            authorizedRequest("deleteComment($commentId)") {
                method = HttpMethod.Post
                url.takeFrom("https://oauth.reddit.com/api/del")
                setBody(FormDataContent(parameters {
                    append("id", "t1_$commentId")
                }))
            }
            true
        } catch (e: Exception) {
            logger.error(e) { "Failed to delete comment" }
            false
        }
    }

    // === Lookup Operations ===

    /**
     * Get info about things by fullname
     */
    suspend fun getInfo(vararg fullnames: String): RedditListing<JsonElement> {
        val response = authorizedRequest("getInfo") {
            method = HttpMethod.Get
            url.takeFrom("https://oauth.reddit.com/api/info")
            url.parameters.append("id", fullnames.joinToString(","))
        }
        return response.body()
    }

    /**
     * Look up a comment by fullname
     */
    suspend fun lookupComment(fullname: String): RedditComment? {
        val info = getInfo(fullname)
        return info.data.children.firstOrNull()?.let {
            try {
                json.decodeFromJsonElement<RedditComment>(it.data)
            } catch (e: Exception) {
                logger.warn(e) { "Failed to decode comment from lookup" }
                null
            }
        }
    }

    fun close() {
        httpClient.close()
    }
}

/**
 * Create a RedditApiClient configured for bot use
 */
fun createRedditClient(
    username: String,
    password: String,
    clientId: String,
    clientSecret: String,
    appId: String,
    version: String,
    authorUsername: String
): RedditApiClient {
    val userAgent = "JVM:$appId:$version (by /u/$authorUsername)"
    return RedditApiClient(
        clientId = clientId,
        clientSecret = clientSecret,
        username = username,
        password = password,
        userAgent = userAgent
    )
}
