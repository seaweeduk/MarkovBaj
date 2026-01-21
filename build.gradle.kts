plugins {
    kotlin("multiplatform")

    // Common
    kotlin("plugin.serialization")

    // JVM Backend (Bot + Janitor Backend + REST API)
    id("com.gradleup.shadow") version "8.3.5"

    // Website Frontend
    id("org.jetbrains.compose") version "1.10.0"
    id("org.jetbrains.kotlin.plugin.compose")
}

group = "marczeugs.markovbaj"
version = "3.5.3"

repositories {
    google()
    mavenCentral()
    maven("https://maven.pkg.jetbrains.space/public/p/compose/dev")
}

val buildInfoGenerator by tasks.registering(Sync::class) {
    from(
        resources.text.fromString(
            """
            object BuildInfo {
                const val PROJECT_VERSION = "${project.version}"
                const val PROJECT_BUILD_TIMESTAMP_MILLIS = ${System.currentTimeMillis()}
            }
            """.trimIndent(),
        ),
    ) {
        rename { "BuildInfo.kt" }
    }

    into(layout.buildDirectory.dir("generated/kotlin/"))
}

tasks.build {
    dependsOn(buildInfoGenerator)
}

kotlin {
    jvmToolchain(21)

    js(IR) {
        browser {
            commonWebpackConfig {
                devServer?.`open` = false
            }
        }

        binaries.executable()
    }

    jvm {
        @OptIn(org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi::class)
        mainRun {
            mainClass.set("MarkovBajKt")
        }
    }

    val kotlinVersion: String by project
    val ktorVersion: String by project
    val exposedVersion: String by project
    val kotlinXSerializationVersion: String by project
    val kotlinXCoroutinesVersion: String by project
    val kordVersion: String by project
    val composeVersion: String by project

    sourceSets {
        all {
            languageSettings.apply {
                optIn("kotlinx.serialization.ExperimentalSerializationApi")
                optIn("kotlin.ExperimentalStdlibApi")
                optIn("kotlin.time.ExperimentalTime")
            }
        }

        val commonMain by getting {
            kotlin.srcDir(buildInfoGenerator.map { it.destinationDir })

            dependencies {
                implementation("io.github.oshai:kotlin-logging:7.0.14")

                implementation("org.jetbrains.kotlinx:kotlinx-serialization-core:$kotlinXSerializationVersion")
                implementation("org.jetbrains.kotlinx:kotlinx-datetime:0.7.1")
            }
        }

        val jsMain by getting {
            dependencies {
                implementation("org.jetbrains.compose.html:html-core:$composeVersion")
                implementation("org.jetbrains.compose.runtime:runtime:$composeVersion")

                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:$kotlinXCoroutinesVersion")
                implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:$kotlinXSerializationVersion")

                implementation("io.ktor:ktor-client-core:$ktorVersion")
                implementation("io.ktor:ktor-client-js:$ktorVersion")
                implementation("io.ktor:ktor-client-content-negotiation:$ktorVersion")
                implementation("io.ktor:ktor-serialization-kotlinx-json:$ktorVersion")
            }
        }

        val jvmMain by getting {
            dependencies {
                implementation("org.jetbrains.compose.html:html-core:$composeVersion")
                implementation("org.jetbrains.compose.runtime:runtime:$composeVersion")

                implementation("org.jetbrains.kotlin:kotlin-reflect:$kotlinVersion")

                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:$kotlinXCoroutinesVersion")
                implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:$kotlinXSerializationVersion")

                implementation("org.slf4j:slf4j-api:2.0.17")
                implementation("org.slf4j:slf4j-simple:2.0.17")

                implementation("io.ktor:ktor-server-core:$ktorVersion")
                implementation("io.ktor:ktor-server-cio:$ktorVersion")
                implementation("io.ktor:ktor-server-html-builder:$ktorVersion")
                implementation("io.ktor:ktor-server-call-logging:$ktorVersion")
                implementation("io.ktor:ktor-server-status-pages:$ktorVersion")
                implementation("io.ktor:ktor-server-sessions:$ktorVersion")
                implementation("io.ktor:ktor-server-resources:$ktorVersion")
                implementation("io.ktor:ktor-server-auth:$ktorVersion")
                implementation("io.ktor:ktor-server-cors:$ktorVersion")
                implementation("io.ktor:ktor-server-websockets:$ktorVersion")

                implementation("io.ktor:ktor-client-core:$ktorVersion")
                implementation("io.ktor:ktor-client-cio:$ktorVersion")
                implementation("io.ktor:ktor-client-content-negotiation:$ktorVersion")
                implementation("io.ktor:ktor-client-auth:$ktorVersion")
                implementation("io.ktor:ktor-serialization-kotlinx-json:$ktorVersion")
                implementation("io.ktor:ktor-client-logging:$ktorVersion")

                implementation("org.jetbrains.exposed:exposed-core:$exposedVersion")
                implementation("org.jetbrains.exposed:exposed-jdbc:$exposedVersion")
                implementation("org.jetbrains.exposed:exposed-kotlin-datetime:$exposedVersion")
                implementation("org.jetbrains.exposed:exposed-json:$exposedVersion")
                implementation("org.postgresql:postgresql:42.7.9")

                implementation("org.jetbrains.kotlinx:kotlinx-html:0.12.0")
                implementation("org.jetbrains.kotlin-wrappers:kotlin-css:2026.1.10")

                implementation("dev.kord:kord-core:$kordVersion")

                implementation("com.github.twitch4j:twitch4j:1.25.0")

                // For scripts
                implementation(kotlin("script-runtime"))
            }
        }
    }
}

tasks.withType<JavaExec>().configureEach {
    if (name == "jvmRun" || name == "run") {
        val runtimeXmx = System.getenv("MARKOVBAJ_XMX") ?: "1g"
        jvmArgs("-Xmx$runtimeXmx", "-XX:+UseG1GC")
    }
}

// Shadow JAR configuration for the JVM target
tasks.register<com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar>("shadowJar") {
    archiveBaseName.set("MarkovBaj")
    archiveClassifier.set("")
    manifest {
        attributes["Main-Class"] = "MarkovBajKt"
    }
    from(
        kotlin
            .jvm()
            .compilations
            .getByName("main")
            .output,
    )
    configurations = listOf(project.configurations.getByName("jvmRuntimeClasspath"))
}
