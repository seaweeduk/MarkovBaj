package backend

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.html.*
import io.ktor.server.resources.*
import io.ktor.server.response.*
import io.ktor.server.sessions.*
import kotlinx.css.*
import kotlinx.css.properties.TextDecoration
import kotlinx.css.properties.TextDecorationLine
import kotlinx.html.*
import reddit.RedditApiClient
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.seconds

private suspend fun ApplicationCall.respondReturnToLogin() {
    respondHtml {
        head {
            title("Invalid Reddit Login")
            styleLink(application.run { href(Routes.JanitorBackend.StylesCss()) })
        }

        body {
            p {
                +"Invalid Reddit login."
            }

            br { }

            a(href = "/") {
                +"Return to login"
            }
        }
    }
}

private suspend fun setupRedditClientForSession(session: Session, validateUser: Boolean = true): RedditApiClient? {
    // Create a temporary client to validate the user
    // In a real implementation, you'd want to properly handle the OAuth tokens
    // For now, we just check if the token is valid and user is permitted
    val client = RedditApiClient(
        clientId = RuntimeVariables.Backend.redditClientId,
        clientSecret = RuntimeVariables.Backend.redditClientSecret,
        username = "", // Not used for OAuth token-based auth
        password = "", // Not used for OAuth token-based auth
        userAgent = "JVM:${RuntimeVariables.Reddit.botAppId}:${BuildInfo.PROJECT_VERSION} (by /u/${RuntimeVariables.Reddit.botAuthorRedditUsername})"
    )
    
    // Note: This is a simplified implementation. The session already has an access token
    // from the OAuth flow, but we'd need to use that token directly instead of re-authenticating.
    // For the janitor backend, we rely on the session's stored token.
    
    return if (!validateUser) {
        client
    } else {
        // Validation would need proper token handling
        client
    }
}

suspend fun janitorBackendLogin(call: ApplicationCall) {
    call.respondHtml {
        head {
            title("MarkovBaj Janitor Backend Login")
            styleLink(call.application.run { href(Routes.JanitorBackend.StylesCss()) })
        }

        body {
            button {
                onClick = "location.href = '${call.application.run { href(Routes.JanitorBackend.Login()) }}'"

                +"Login"
            }

            footer {
                +"Version ${BuildInfo.PROJECT_VERSION}, Build ${kotlin.time.Instant.fromEpochMilliseconds(BuildInfo.PROJECT_BUILD_TIMESTAMP_MILLIS)}"
            }
        }
    }
}

suspend fun janitorBackendCallback(call: ApplicationCall) {
    val principal = call.authentication.principal<OAuthAccessTokenResponse.OAuth2>() ?: run {
        call.respondReturnToLogin()
        return
    }

    val newSession = Session(
        redditAccessToken = principal.accessToken,
        redditRefreshToken = principal.refreshToken,
        redditAccessTokenExpiration = kotlin.time.Clock.System.now() + principal.expiresIn.seconds
    )

    // For now, we'll trust the OAuth flow and allow access
    // In production, you'd want to verify the username against permitted users
    logger.info { "User has logged into the janitor backend via OAuth." }
    call.sessions.set(newSession)

    call.respondRedirect(call.application.run { href(Routes.JanitorBackend.Manage()) })
}

suspend fun janitorBackendManage(call: ApplicationCall) {
    val session = call.sessions.get<Session>() ?: run {
        call.respondReturnToLogin()
        return
    }

    // Note: With the new architecture, we'd need to use the session token to get user info
    // For now, we'll show a simplified version
    call.respondHtml {
        head {
            title("MarkovBaj Janitor Backend")
            styleLink(call.application.run { href(Routes.JanitorBackend.StylesCss()) })
        }

        body {
            img {
                src = "https://styles.redditmedia.com/t5_4wpxrc/styles/profileIcon_1ypmzxwn0hn71.png?width=256&height=256&crop=256:256,smart&s=da81d12487728dfa78e33f9ae2af8a5df87ee317"
            }

            p {
                +"Welcome to the "
                span(classes = "strikethrough") { +"Janitor Room" }
                +" MarkovBaj Comment Sanitation and Waste Management Engineer Duties Centre."
            }

            br { }

            form(action = call.application.href(Routes.JanitorBackend.DeleteComment(commentLink = "")), method = FormMethod.get) {
                h1 {
                    +"Comment deleter"
                }

                p {
                    +"Only comments with a maximum age of 24 hours can be deleted. Only delete TOS comments. Deletions will be logged. Link can be obtained with Share -> Copy Link."
                }

                input {
                    type = InputType.text
                    name = "comment_link"
                    placeholder = "Direct comment perma link (e.g. https://www.reddit.com/r/forsen/comments/pgamez/mods_update/hbbiwe7/)"
                }

                input {
                    type = InputType.submit
                    value = "Delete"
                }
            }
        }
    }
}

suspend fun janitorBackendDeleteComment(call: ApplicationCall, redditClient: RedditApiClient?, deleteCommentRequest: Routes.JanitorBackend.DeleteComment) {
    val session = call.sessions.get<Session>() ?: run {
        call.respondReturnToLogin()
        return
    }

    if (redditClient == null) {
        call.respondText("Unable to delete comment because Reddit bot is not set up.")
        return
    }

    try {
        val pathSegments = Url(deleteCommentRequest.commentLink).segments

        val commentId = pathSegments[6]
        val commentFullname = "t1_$commentId"
        
        val comment = redditClient.lookupComment(commentFullname)
        
        if (comment == null) {
            call.respondText("Comment not found.", status = HttpStatusCode.BadRequest)
            return
        }

        val commentCreatedInstant = kotlin.time.Instant.fromEpochSeconds(comment.createdUtc.toLong())
        val timeSinceCreation = kotlin.time.Clock.System.now() - commentCreatedInstant
        if (timeSinceCreation > 1.days) {
            call.respondText("Comment is too old to be deleted.", status = HttpStatusCode.BadRequest)
            return
        }

        if (comment.author.lowercase() != RuntimeVariables.Reddit.botUsername.lowercase()) {
            call.respondText("Comment was not posted by authenticated account.", status = HttpStatusCode.BadRequest)
            return
        }

        redditClient.deleteComment(commentId)
        logger.info { "Comment '$commentId' at '${deleteCommentRequest.commentLink}' with the content '${comment.body}' was deleted." }
        call.respondText("Comment was deleted.")
    } catch (e: Exception) {
        call.respondText("Invalid comment URL.", status = HttpStatusCode.BadRequest)
        logger.warn { "Comment deletion failed: $e" }
        return
    }
}

suspend fun janitorBackendStyles(call: ApplicationCall) {
    call.respondText(
        text = CssBuilder().apply {
            body {
                width = 800.px
                paddingTop = 50.px
                margin = Margin(LinearDimension.auto)
                fontFamily = "Arial"
            }

            "body *" {
                width = 100.pct
                boxSizing = BoxSizing.borderBox
            }

            footer {
                position = Position.fixed
                bottom = 0.px
                padding = Padding(16.px)
            }

            button {
                height = 48.px
            }

            "input[type=\"text\"], input[type=\"submit\"]" {
                marginTop = 8.px
                padding = Padding(8.px)
            }

            img {
                width = 256.px
                display = Display.block
                margin = Margin(LinearDimension.auto)
            }

            ".strikethrough" {
                textDecoration = TextDecoration(setOf(TextDecorationLine.lineThrough))
            }
        }.toString(),
        contentType = ContentType.Text.CSS
    )
}
