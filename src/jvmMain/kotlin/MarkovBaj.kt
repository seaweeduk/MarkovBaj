
import backend.setupBackendServer
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

import kotlinx.serialization.json.Json
import java.io.File
import kotlin.time.DurationUnit
import kotlin.time.measureTime

private val logger = KotlinLogging.logger("MarkovBaj:General")

suspend fun main() = coroutineScope {
    logger.info { "Starting MarkovBaj Backend version ${BuildInfo.PROJECT_VERSION}, Build ${kotlin.time.Instant.fromEpochMilliseconds(BuildInfo.PROJECT_BUILD_TIMESTAMP_MILLIS)}..." }


    val json = Json {
        ignoreUnknownKeys = true
    }

    val markovChain = MarkovChain<String?>(CommonConstants.consideredValuesForGeneration) { it?.trim() }

    logger.info { "Building Markov chain..." }

    val chainBuildTime = measureTime {
        readJsonStringArray(File("data.json")).forEach { message ->
            val wordParts = message.toWordParts()
            val chainStarts = listOf(
                wordParts.take(CommonConstants.consideredValuesForGeneration),
                wordParts.drop(1).take(CommonConstants.consideredValuesForGeneration),
            )
            markovChain.addData(listOf(wordParts), chainStarts)
        }
    }

    logger.info { "Building the chain took ${chainBuildTime.toDouble(DurationUnit.SECONDS)}s." }


    val eventFlow = MutableSharedFlow<ApiEvent>(extraBufferCapacity = 1)

    val redditClient = if (RuntimeVariables.Reddit.enabled) setupRedditClient() else null

    if (RuntimeVariables.Reddit.enabled) {
        launch {
            setupRedditBot(redditClient!!, markovChain, eventFlow)
        }
    } else {
        logger.warn { "Reddit bot is not enabled." }
    }

    if (RuntimeVariables.Discord.enabled) {
        launch {
            setupDiscordBot(markovChain)
        }
    } else {
        logger.warn { "Discord bot is not enabled." }
    }

    if (RuntimeVariables.Twitch.enabled) {
        launch {
            setupTwitchBot(markovChain)
        }
    } else {
        logger.warn { "Twitch bot is not enabled." }
    }

    launch {
        setupBackendApiWebsocketServer(redditClient, json, eventFlow)
    }

    withContext(Dispatchers.IO) {
        setupBackendServer(redditClient, json, markovChain)
    }
}
