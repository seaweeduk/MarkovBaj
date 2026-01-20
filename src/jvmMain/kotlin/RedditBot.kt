
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

import reddit.RedditApiClient
import reddit.createRedditClient
import kotlin.concurrent.fixedRateTimer
import kotlin.time.Duration.Companion.hours

private val logger = KotlinLogging.logger("MarkovBaj:Reddit")

suspend fun setupRedditClient(): RedditApiClient {
    val redditClient = createRedditClient(
        username = RuntimeVariables.Reddit.botUsername,
        password = RuntimeVariables.Reddit.botPassword,
        clientId = RuntimeVariables.Reddit.botClientId,
        clientSecret = RuntimeVariables.Reddit.botClientSecret,
        appId = RuntimeVariables.Reddit.botAppId,
        version = BuildInfo.PROJECT_VERSION,
        authorUsername = RuntimeVariables.Reddit.botAuthorRedditUsername
    )

    redditClient.authenticate()
    logger.info { "Connected to Reddit." }

    return redditClient
}

suspend fun setupRedditBot(redditClient: RedditApiClient, markovChain: MarkovChain<String?>, eventFlow: MutableSharedFlow<ApiEvent>) = coroutineScope {
    var alreadyProcessedPostIds = listOf<String>()
    var alreadyProcessedCommentsIds = listOf<String>()

    logger.info { "Bot running." }

    fixedRateTimer(period = RuntimeVariables.Reddit.checkInterval.inWholeMilliseconds) {
        launch {
            try {
                logger.debug { "Timer loop starting - fetching unread messages..." }
                val newInboxMessages = redditClient.getUnreadMessages(limit = 25)
                    .filter {
                        it.subject == "username mention" ||
                        it.subject.startsWith("comment reply") && CommonConstants.triggerKeyword.lowercase() in it.body.lowercase() && it.subreddit != RuntimeVariables.Reddit.activeSubreddit
                    }
                logger.debug { "Fetched ${newInboxMessages.size} relevant inbox messages" }

                logger.debug { "Fetching subreddit posts..." }
                val checkIntervalMs = RuntimeVariables.Reddit.checkInterval.inWholeMilliseconds
                val newPosts = redditClient.getSubredditPosts(
                    subreddit = RuntimeVariables.Reddit.activeSubreddit,
                    sort = "new",
                    limit = 100
                ).filter { 
                    val createdInstant = kotlin.time.Instant.fromEpochSeconds(it.createdUtc.toLong())
                    val cutoffTime = kotlin.time.Clock.System.now() - RuntimeVariables.Reddit.checkInterval * 5
                    createdInstant > cutoffTime && it.id !in alreadyProcessedPostIds 
                }
                logger.debug { "Fetched ${newPosts.size} new posts" }

                logger.debug { "Fetching subreddit comments..." }
                val newComments = redditClient.getSubredditComments(
                    subreddit = RuntimeVariables.Reddit.activeSubreddit,
                    limit = 100
                ).filter {
                    val createdInstant = kotlin.time.Instant.fromEpochSeconds(it.createdUtc.toLong())
                    val cutoffTime = kotlin.time.Clock.System.now() - RuntimeVariables.Reddit.checkInterval * 2
                    createdInstant > cutoffTime &&
                    it.id !in alreadyProcessedCommentsIds &&
                    it.id !in newInboxMessages.filter { message -> message.subreddit == RuntimeVariables.Reddit.activeSubreddit }.map { message -> message.id }
                }
                logger.debug { "Fetched ${newComments.size} new comments - timer loop API calls completed" }

                logger.info { "${newInboxMessages.size} new mention(s), ${newPosts.size} new post(s), ${newComments.size} new comment(s)." }

                eventFlow.tryEmit(
                    ApiEvent.CommentsCollected(
                        comments = newComments.map { comment ->
                            ApiEvent.CommentsCollected.Comment(
                                id = comment.id,
                                created = kotlin.time.Instant.fromEpochSeconds(comment.createdUtc.toLong()),
                                author = comment.author,
                                body = comment.body,
                                url = comment.url,
                                authorFlairText = comment.authorFlairText,
                                submissionFullName = comment.submissionFullName,
                                submissionTitle = comment.submissionTitle,
                                subredditType = comment.subredditType ?: "public",
                                distinguished = comment.distinguished ?: "none",
                                fullName = comment.fullName,
                                parentFullName = comment.parentFullName,
                                subredditFullName = comment.subredditFullName,
                            )
                        }
                    )
                )

                eventFlow.tryEmit(
                    ApiEvent.SubmissionsCollected(
                        submissions = newPosts.map { post ->
                            ApiEvent.SubmissionsCollected.Submission(
                                created = kotlin.time.Instant.fromEpochSeconds(post.createdUtc.toLong()),
                                distinguished = post.distinguished ?: "none",
                                id = post.id,
                                author = post.author,
                                body = post.body,
                                title = post.title,
                                url = post.url,
                                authorFlairText = post.authorFlairText,
                                domain = post.domain,
                                embeddedMedia = post.embeddedMedia,
                                isNsfw = post.isNsfw,
                                isSelfPost = post.isSelfPost,
                                isSpoiler = post.isSpoiler,
                                linkFlairCssClass = post.linkFlairCssClass,
                                linkFlairText = post.linkFlairText,
                                permalink = post.permalink,
                                postHint = post.postHint,
                                preview = post.hasPreview,
                                selfText = post.selftext,
                                thumbnail = post.thumbnail,
                                fullName = post.fullName,
                                subreddit = post.subreddit,
                                subredditFullName = post.subredditFullName,
                            )
                        }
                    )
                )

                alreadyProcessedPostIds = newPosts.map { it.id }
                alreadyProcessedCommentsIds = newInboxMessages.map { it.id }.union(newComments.map { it.id }).toList()

                var commentCounter = 0

                if (RuntimeVariables.Reddit.answerMentions) {
                    for (message in newInboxMessages) {
                        if (commentCounter >= RuntimeVariables.Reddit.maxCommentsPerCheck) {
                            logger.warn { "Hit comment limit, not posting any more replies." }
                            return@launch
                        }

                        if (!message.isComment) {
                            logger.warn { "Username mention with id ${message.id} was not a comment, skipping..." }
                            return@launch
                        }

                        val wordsInTitle = message.body.toWordParts()

                        val relatedReply = if (Math.random() > RuntimeVariables.Common.unrelatedAnswerChance) {
                            markovChain.tryGeneratingReplyFromWords(wordsInTitle, platform = "Reddit")
                        } else {
                            null
                        }

                        val actualReply = if (relatedReply != null) {
                            logger.info { "Replying to mention by ${message.author} in message ${message.id} in ${message.subreddit?.let { "r/$it" } ?: "-"} ('${message.body}') with related answer..." }
                            relatedReply
                        } else {
                            markovChain.generateRandomReply().also {
                                logger.info { "Default replying to mention by ${message.author} in message ${message.id} in ${message.subreddit?.let { "r/$it" } ?: "-"} ('${message.body}')..." }
                            }
                        }

                        safeReply(redditClient, message.fullName, actualReply)
                        redditClient.markMessagesRead(message.fullName)
                        commentCounter++

                        delay(RuntimeVariables.Reddit.delayBetweenComments)
                    }
                }

                for (post in newPosts) {
                    if (commentCounter >= RuntimeVariables.Reddit.maxCommentsPerCheck) {
                        logger.warn { "Hit comment limit, not posting any more replies." }
                        return@launch
                    }

                    if (post.isRemoved) {
                        continue
                    }

                    if (CommonConstants.triggerKeyword.lowercase() in post.title.lowercase()) {
                        val wordsInTitle = post.title.toWordParts()

                        val relatedReply = if (Math.random() > RuntimeVariables.Common.unrelatedAnswerChance) {
                            markovChain.tryGeneratingReplyFromWords(wordsInTitle, platform = "Reddit")
                        } else {
                            null
                        }

                        val actualReply = if (relatedReply != null) {
                            logger.info { "Replying to post ${post.id} ('${post.title}') with related answer..." }
                            relatedReply
                        } else {
                            markovChain.generateRandomReply().also {
                                logger.info { "Default replied to post ${post.id} ('${post.title}')..." }
                            }
                        }

                        safeReply(redditClient, post.fullName, actualReply)
                        commentCounter++

                        delay(RuntimeVariables.Reddit.delayBetweenComments)
                    }
                }

                for (comment in newComments) {
                    if (commentCounter >= RuntimeVariables.Reddit.maxCommentsPerCheck) {
                        logger.warn { "Hit comment limit, not posting any more replies." }
                        return@launch
                    }

                    if (comment.id in newInboxMessages.map { it.id }) {
                        continue
                    }

                    if (CommonConstants.triggerKeyword.lowercase() in comment.body.lowercase()) {
                        val wordsInComment = comment.body.toWordParts()

                        val relatedReply = if (Math.random() > RuntimeVariables.Common.unrelatedAnswerChance) {
                            markovChain.tryGeneratingReplyFromWords(wordsInComment, platform = "Reddit")
                        } else {
                            null
                        }

                        val actualReply = if (relatedReply != null) {
                            logger.info { "Replying to comment ${comment.id} ('${comment.body}') with related answer..." }
                            relatedReply
                        } else {
                            markovChain.generateRandomReply().also {
                                logger.info { "Default replying to comment ${comment.id} ('${comment.body}')..." }
                            }
                        }

                        safeReply(redditClient, comment.fullName, actualReply)
                        commentCounter++

                        delay(RuntimeVariables.Reddit.delayBetweenComments)
                    }
                }
            } catch (e: Exception) {
                logger.error(e) { "Error while running timer loop" }
            }
        }
    }

    // Keep coroutine scope alive
    launch {
        while (isActive) {
            delay(1.hours)
        }
    }
}

private suspend fun safeReply(redditClient: RedditApiClient, parentFullname: String, text: String) {
    if (RuntimeVariables.Reddit.actuallySendReplies) {
        try {
            val success = redditClient.reply(parentFullname, text.take(5000))
            if (success) {
                logger.info { "Replied with '${text.take(5000)}'." }
            } else {
                logger.error { "Reply failed (API returned error)." }
            }
        } catch (e: Exception) {
            logger.error(e) { "Reply failed" }
        }
    } else {
        logger.info { "[NOT ACTUALLY REPLYING] Would have replied with '$text'." }
    }
}
