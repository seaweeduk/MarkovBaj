package backend
import InstantSerializer
import MarkovChain
import RuntimeVariables
import TableDefinition
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.client.*
import io.ktor.http.*
import io.ktor.resources.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.cio.*
import io.ktor.server.engine.*
import io.ktor.server.plugins.calllogging.*
import io.ktor.server.plugins.cors.routing.*
import io.ktor.server.plugins.statuspages.*
import io.ktor.server.resources.*
import io.ktor.server.resources.Resources
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.sessions.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.kotlin.datetime.KotlinInstantColumnType
import org.jetbrains.exposed.sql.transactions.transaction
import org.slf4j.event.Level
import reddit.RedditApiClient
import java.io.PrintWriter
import java.io.StringWriter
import kotlin.time.Duration.Companion.minutes

@Serializable
data class Session(
    val redditAccessToken: String,
    val redditRefreshToken: String?,
    @Serializable(with = InstantSerializer::class) val redditAccessTokenExpiration: kotlin.time.Instant,
)

object Routes {
    @Resource("/janitorbackend")
    @Serializable
    class JanitorBackend {
        @Resource("login")
        @Serializable
        data class Login(val janitorBackend: JanitorBackend = JanitorBackend())

        @Resource("callback")
        @Serializable
        data class Callback(val janitorBackend: JanitorBackend = JanitorBackend())

        @Resource("manage")
        @Serializable
        data class Manage(val janitorBackend: JanitorBackend = JanitorBackend())

        @Resource("deletecomment")
        @Serializable
        data class DeleteComment(val janitorBackend: JanitorBackend = JanitorBackend(), @SerialName("comment_link") val commentLink: String)

        @Resource("styles.css")
        @Serializable
        data class StylesCss(val janitorBackend: JanitorBackend = JanitorBackend())
    }

    @Resource("/lidlboards")
    @Serializable
    class Lidlboards

    @Resource("/api/v1")
    @Serializable
    class Api {
        @Resource("query")
        @Serializable
        data class Query(val api: Api = Api(), val input: String? = null)
    }
}

val logger = KotlinLogging.logger("MarkovBaj:Backend")

val redditLoginRedirectUrl = "${RuntimeVariables.Backend.serverUrl}/janitorbackend/callback"

fun setupBackendServer(redditClient: RedditApiClient?, json: Json, markovChain: MarkovChain<String?>) {
    embeddedServer(
        factory = CIO,
        configure = {
            connector {
                host = "0.0.0.0"
                port = RuntimeVariables.Backend.serverPort
            }
        }
    ) {
        backendModule(redditClient, json, markovChain)
    }.start(wait = true)
}

fun Application.backendModule(redditClient: RedditApiClient?, json: Json, markovChain: MarkovChain<String?>) {
    Database.connect(
        url = "jdbc:postgresql://${RuntimeVariables.Backend.databaseUrl}",
        driver = "org.postgresql.Driver",
        user = RuntimeVariables.Backend.databaseUser,
        password = RuntimeVariables.Backend.databasePassword
    )

    val tables = RuntimeVariables.Backend.databaseTables.associate { table ->
        table.displayName to (object : Table(table.name) { }).apply {
            table.columns.forEach { col ->
                when (col.type) {
                    TableDefinition.Column.Type.VarChar32 -> registerColumn<String>(col.name, VarCharColumnType())
                    TableDefinition.Column.Type.Text -> registerColumn<String>(col.name, TextColumnType())
                    TableDefinition.Column.Type.Integer -> registerColumn<Int>(col.name, IntegerColumnType())
                    TableDefinition.Column.Type.Boolean -> registerColumn<Boolean>(col.name, BooleanColumnType())
                    TableDefinition.Column.Type.Timestamp -> registerColumn<kotlin.time.Instant>(col.name, KotlinInstantColumnType())
                }
            }
         }
    }

    var tableValues: Map<String, Pair<List<String>, List<List<String>>>> = mapOf()
    var latestTableValuesUpdateInstant: kotlin.time.Instant? = null

    launch {
        while (isActive) {
            tableValues = transaction {
                RuntimeVariables.Backend.databaseTables.associate { tableDefinition ->
                    val actualTable = tables[tableDefinition.displayName]!!

                    tableDefinition.displayName to (
                        tableDefinition.columns.map { it.displayName } to
                        actualTable.selectAll().limit(RuntimeVariables.Backend.databaseTableRowLimit).map { row ->
                            actualTable.columns.map { row[it]?.toString() ?: "" }
                        }
                    )
                }
            }

            latestTableValuesUpdateInstant = kotlin.time.Clock.System.now()

            delay(10.minutes)
        }
    }

    install(CallLogging) {
        level = Level.TRACE
    }

    install(StatusPages) {
        exception<Exception> { call, cause ->
            call.respond(
                HttpStatusCode.InternalServerError,
                "Internal server error:\n${StringWriter().also { cause.printStackTrace(PrintWriter(it)) }}"
            )
        }
    }

    install(Sessions) {
        cookie<Session>("reddit_login") {
            serializer = object : SessionSerializer<Session> {
                override fun deserialize(text: String) = json.decodeFromString<Session>(text)
                override fun serialize(session: Session) = json.encodeToString(session)
            }
        }
    }

    install(CORS) {
        anyHost()
        allowHeaders { true }
        allowMethod(HttpMethod.Get)
    }

    install(Resources)

    val redditOAuthName = "reddit-oauth"

    authentication {
        oauth(redditOAuthName) {
            urlProvider = { redditLoginRedirectUrl }
            providerLookup = {
                OAuthServerSettings.OAuth2ServerSettings(
                    name = "reddit",
                    authorizeUrl = "https://www.reddit.com/api/v1/authorize",
                    accessTokenUrl = "https://www.reddit.com/api/v1/access_token",
                    accessTokenRequiresBasicAuth = true,
                    requestMethod = HttpMethod.Post,
                    clientId = RuntimeVariables.Backend.redditClientId,
                    clientSecret = RuntimeVariables.Backend.redditClientSecret,
                    defaultScopes = listOf("identity")
                )
            }
            client = HttpClient(io.ktor.client.engine.cio.CIO)
        }
    }

    routing {
        get<Routes.JanitorBackend> {
            janitorBackendLogin(call)
        }

        authenticate(redditOAuthName) {
            get<Routes.JanitorBackend.Login> {
                // Redirects to `authorizeUrl` automatically
            }

            get<Routes.JanitorBackend.Callback> {
                janitorBackendCallback(call)
            }
        }

        get<Routes.JanitorBackend.Manage> {
            janitorBackendManage(call)
        }

        get<Routes.JanitorBackend.DeleteComment> {
            janitorBackendDeleteComment(call, redditClient, it)
        }

        get<Routes.JanitorBackend.StylesCss> {
            janitorBackendStyles(call)
        }

        get<Routes.Api.Query> { queryInput ->
            apiQuery(call, queryInput, markovChain)
        }

        get<Routes.Lidlboards> {
            lidlboards(call, latestTableValuesUpdateInstant, tableValues)
        }
    }
}
