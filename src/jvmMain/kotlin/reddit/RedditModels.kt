package reddit

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * Reddit API response models using Ktor + kotlinx.serialization
 * Replaces JRAW library models
 */

// === OAuth Token Response ===
@Serializable
data class RedditTokenResponse(
    @SerialName("access_token") val accessToken: String,
    @SerialName("token_type") val tokenType: String,
    @SerialName("expires_in") val expiresIn: Long,
    @SerialName("refresh_token") val refreshToken: String? = null,
    val scope: String
)

// === Generic Listing Response ===
@Serializable
data class RedditListing<T>(
    val kind: String,
    val data: RedditListingData<T>
)

@Serializable
data class RedditListingData<T>(
    val after: String? = null,
    val before: String? = null,
    val children: List<RedditThing<T>>,
    val dist: Int? = null,
    val modhash: String? = null
)

@Serializable
data class RedditThing<T>(
    val kind: String,
    val data: T
)

// === Submission (Post) ===
@Serializable
data class RedditSubmission(
    val id: String,
    val name: String, // fullname like t3_xxxxx
    val author: String,
    val title: String,
    val selftext: String? = null,
    @SerialName("selftext_html") val selftextHtml: String? = null,
    val url: String,
    val permalink: String,
    val subreddit: String,
    @SerialName("subreddit_id") val subredditId: String,
    @SerialName("created_utc") val createdUtc: Double,
    val score: Int,
    @SerialName("num_comments") val numComments: Int,
    @SerialName("is_self") val isSelf: Boolean,
    val nsfw: Boolean? = false,
    @SerialName("over_18") val over18: Boolean = false,
    val spoiler: Boolean = false,
    val domain: String,
    @SerialName("link_flair_text") val linkFlairText: String? = null,
    @SerialName("link_flair_css_class") val linkFlairCssClass: String? = null,
    @SerialName("author_flair_text") val authorFlairText: String? = null,
    val distinguished: String? = null,
    val thumbnail: String? = null,
    @SerialName("post_hint") val postHint: String? = null,
    val preview: JsonElement? = null,
    val media: JsonElement? = null,
    val removed: Boolean? = false,
    @SerialName("removed_by_category") val removedByCategory: String? = null
) {
    val fullName: String get() = name
    val body: String? get() = selftext
    val isNsfw: Boolean get() = over18 || (nsfw == true)
    val isSelfPost: Boolean get() = isSelf
    val isSpoiler: Boolean get() = spoiler
    val isRemoved: Boolean get() = removed == true || removedByCategory != null
    val subredditFullName: String get() = subredditId
    val embeddedMedia: Boolean get() = media != null
    val hasPreview: Boolean get() = preview != null
}

// === Comment ===
@Serializable
data class RedditComment(
    val id: String,
    val name: String, // fullname like t1_xxxxx
    val author: String,
    val body: String,
    @SerialName("body_html") val bodyHtml: String? = null,
    @SerialName("parent_id") val parentId: String,
    @SerialName("link_id") val linkId: String,
    val subreddit: String,
    @SerialName("subreddit_id") val subredditId: String,
    @SerialName("created_utc") val createdUtc: Double,
    val score: Int,
    @SerialName("author_flair_text") val authorFlairText: String? = null,
    val distinguished: String? = null,
    @SerialName("link_title") val linkTitle: String? = null,
    @SerialName("link_url") val linkUrl: String? = null,
    val permalink: String? = null,
    @SerialName("subreddit_type") val subredditType: String? = null,
    val context: String? = null
) {
    val fullName: String get() = name
    val parentFullName: String get() = parentId
    val submissionFullName: String get() = linkId
    val subredditFullName: String get() = subredditId
    val submissionTitle: String? get() = linkTitle
    val url: String? get() = permalink?.let { "https://www.reddit.com$it" }
}

// === Message (Inbox) ===
@Serializable
data class RedditMessage(
    val id: String,
    val name: String, // fullname
    val author: String,
    val body: String,
    @SerialName("body_html") val bodyHtml: String? = null,
    val subject: String,
    val subreddit: String? = null,
    @SerialName("parent_id") val parentId: String? = null,
    @SerialName("created_utc") val createdUtc: Double,
    val new: Boolean = false,
    @SerialName("was_comment") val wasComment: Boolean = false,
    val context: String? = null,
    @SerialName("link_title") val linkTitle: String? = null
) {
    val fullName: String get() = name
    val isComment: Boolean get() = wasComment
}

// === User Info (from /api/v1/me) ===
@Serializable
data class RedditUser(
    val id: String,
    val name: String,
    @SerialName("created_utc") val createdUtc: Double,
    @SerialName("link_karma") val linkKarma: Int = 0,
    @SerialName("comment_karma") val commentKarma: Int = 0,
    @SerialName("is_gold") val isGold: Boolean = false,
    @SerialName("is_mod") val isMod: Boolean = false
) {
    val username: String get() = name
}

// === API Response for posting comments ===
@Serializable
data class RedditCommentResponse(
    val json: RedditCommentResponseJson
)

@Serializable
data class RedditCommentResponseJson(
    val errors: List<List<String>> = emptyList(),
    val data: RedditCommentResponseData? = null
)

@Serializable
data class RedditCommentResponseData(
    val things: List<RedditThing<RedditComment>> = emptyList()
)

// === API Response for info lookup ===
@Serializable
data class RedditInfoResponse(
    val kind: String,
    val data: RedditListingData<JsonElement>
)
