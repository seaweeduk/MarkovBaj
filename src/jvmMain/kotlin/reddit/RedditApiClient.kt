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
        block: HttpRequestBuilder.() -> Unit
    ): HttpResponse {
        ensureAuthenticated()
        
        logger.debug { "[$operationName] Starting request..." }
        
        var requestUrl = "unknown"
        return try {
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
            response
        } catch (e: java.net.ConnectException) {
            logger.error { "[$operationName] Connection refused to $requestUrl - is the network available? Host may be unreachable." }
            throw e
        } catch (e: java.net.UnknownHostException) {
            logger.error { "[$operationName] DNS resolution failed to $requestUrl - check network/DNS settings" }
            throw e
        } catch (e: io.ktor.client.plugins.HttpRequestTimeoutException) {
            logger.error { "[$operationName] Request timeout to $requestUrl" }
            throw e
        } catch (e: Exception) {
            logger.error { "[$operationName] Request failed to $requestUrl: ${e::class.simpleName} - ${e.message}" }
            throw e
        }
    }

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
    suspend fun reply(parentFullname: String, text: String): Boolean {
        return try {
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
            if (result.json.errors.isNotEmpty()) {
                logger.error { "Reply failed with errors: ${result.json.errors}" }
                false
            } else {
                true
            }
        } catch (e: Exception) {
            logger.error(e) { "Failed to post reply" }
            false
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
