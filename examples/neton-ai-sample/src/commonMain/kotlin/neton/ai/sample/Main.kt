package neton.ai.sample

import neton.http.client.create

import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.runBlocking
import neton.ai.AiClient
import neton.ai.AiStreamEvent
import neton.ai.AiException
import neton.http.client.HttpClient

/**
 * neton-ai v0.1 sample. Demonstrates standalone (Mode 1) usage:
 *  - generateText (non-streaming)
 *  - streamText (SSE streaming)
 *  - embed (vector embeddings)
 *
 * Requires environment variables (skips demos if missing):
 *  - OPENAI_API_KEY (or any OpenAI-compatible service via OPENAI_BASE_URL override)
 *  - ANTHROPIC_API_KEY (optional, for Anthropic demo)
 */
fun main() = runBlocking {
    val openaiKey = getEnv("OPENAI_API_KEY")?.takeIf { it.isNotBlank() }
    val openaiBaseUrl = getEnv("OPENAI_BASE_URL")?.takeIf { it.isNotBlank() } ?: "https://api.openai.com/v1"
    val anthropicKey = getEnv("ANTHROPIC_API_KEY")?.takeIf { it.isNotBlank() }

    if (openaiKey == null && anthropicKey == null) {
        println("neton-ai sample: no API keys found in environment.")
        println("Set OPENAI_API_KEY and/or ANTHROPIC_API_KEY to run live demos.")
        println("(This sample compiles and binds the AiClient even without keys; it just skips live calls.)")
    }

    val httpClient = HttpClient.create { requestMillis = 60_000 }

    val ai = if (openaiKey != null || anthropicKey != null) {
        AiClient.create {
            this.httpClient = httpClient
            providers {
                if (openaiKey != null) {
                    openAiCompatible("openai") {
                        baseUrl = openaiBaseUrl
                        apiKey = openaiKey
                    }
                }
                if (anthropicKey != null) {
                    anthropic("anthropic") {
                        apiKey = anthropicKey
                    }
                }
            }
            routing {
                defaultModel = if (openaiKey != null) "openai:gpt-4o-mini"
                    else "anthropic:claude-sonnet-4-5"
                if (openaiKey != null && anthropicKey != null) {
                    policy("strong") {
                        prefer("anthropic:claude-sonnet-4-5")
                        fallback("openai:gpt-4o")
                    }
                }
            }
            debug = true
            logSink = { line -> println("[ai.debug] $line") }
        }
    } else {
        null
    }

    if (ai == null) {
        println("Skipping live demos (no API keys).")
        httpClient.close()
        return@runBlocking
    }

    // -- Demo 1: generateText (non-streaming) --
    println("\n=== Demo 1: generateText ===")
    try {
        val result = ai.generateText {
            user("Say hello in exactly three words.")
        }
        println("Provider: ${result.providerId}/${result.modelName}")
        println("Response: ${result.text}")
        println("Tokens: in=${result.usage?.inputTokens} out=${result.usage?.outputTokens}")
    } catch (e: AiException) {
        println("generateText failed: ${e.error::class.simpleName} - ${e.message}")
    }

    // -- Demo 2: streamText --
    println("\n=== Demo 2: streamText ===")
    try {
        ai.streamText {
            user("Count from 1 to 5, one per line.")
        }.collect { event ->
            when (event) {
                is AiStreamEvent.Started -> println("[started: ${event.providerId}/${event.modelName}]")
                is AiStreamEvent.TextDelta -> print(event.text)
                is AiStreamEvent.Completed -> println("\n[completed; finish=${event.finishReason}]")
                is AiStreamEvent.Failed -> println("\n[failed: ${event.error::class.simpleName}]")
                else -> {} // ToolCall* / ToolResult* not used in this demo
            }
        }
    } catch (e: AiException) {
        println("streamText failed: ${e.error::class.simpleName} - ${e.message}")
    }

    // -- Demo 3: embed (OpenAI only — Anthropic has no embedding API) --
    if (openaiKey != null) {
        println("\n=== Demo 3: embed ===")
        try {
            val result = ai.embed {
                model = "openai:text-embedding-3-small"
                input("Hello world.")
                input("Goodbye world.")
            }
            println("Provider: ${result.providerId}/${result.modelName}")
            println("Returned ${result.embeddings.size} embeddings; first vector size=${result.embeddings[0].size}")
            println("Tokens: in=${result.usage?.inputTokens} total=${result.usage?.totalTokens}")
        } catch (e: AiException) {
            println("embed failed: ${e.error::class.simpleName} - ${e.message}")
        }
    }

    ai.close()
    httpClient.close()
}

internal expect fun getEnv(name: String): String?
