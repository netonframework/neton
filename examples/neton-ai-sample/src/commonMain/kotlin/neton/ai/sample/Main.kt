package neton.ai.sample

import neton.http.client.create

import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.runBlocking
import neton.ai.AiClient
import neton.ai.AiStreamPart
import neton.ai.AiModelId
import neton.ai.EmbeddingRequest
import neton.ai.cosineSimilarity
import neton.ai.AiException
import neton.http.client.HttpClient

/**
 * neton-ai v0.1 sample. Demonstrates standalone (Mode 1) usage:
 *  - generate (non-streaming)
 *  - stream (streaming parts)
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

    // -- Demo 1: generate (non-streaming) --
    println("\n=== Demo 1: generate ===")
    try {
        val result = ai.generate {
            user("Say hello in exactly three words.")
        }
        println("Model: ${result.model}")
        println("Response: ${result.text}")
        println("Tokens: in=${result.usage.inputTokens} out=${result.usage.outputTokens} (${result.usage.completeness})")
    } catch (e: AiException) {
        println("generate failed: ${e.error::class.simpleName} - ${e.message}")
    }

    // -- Demo 2: stream --
    println("\n=== Demo 2: stream ===")
    ai.stream {
        user("Count from 1 to 5, one per line.")
    }.collect { part ->
        when (part) {
            is AiStreamPart.StepStart -> println("[step ${part.stepNumber}: ${part.model}]")
            is AiStreamPart.TextDelta -> print(part.text)
            is AiStreamPart.Finish -> println("\n[finished; reason=${part.result.finishReason}]")
            is AiStreamPart.Error -> println("\n[failed: ${part.error::class.simpleName}]")
            else -> {}
        }
    }

    // -- Demo 3: embed (OpenAI only — Anthropic has no embedding API) --
    if (openaiKey != null) {
        println("\n=== Demo 3: embed ===")
        try {
            val result = ai.embed(EmbeddingRequest(AiModelId.parse("openai:text-embedding-3-small"), listOf("Hello world.", "Goodbye world.")))
            println("Returned ${result.embeddings.size} embeddings; first vector size=${result.embeddings[0].size}")
            println("Similarity: ${cosineSimilarity(result.embeddings[0], result.embeddings[1])}")
            println("Tokens: in=${result.usage.inputTokens}")
        } catch (e: AiException) {
            println("embed failed: ${e.error::class.simpleName} - ${e.message}")
        }
    }

    ai.close()
    httpClient.close()
}

internal expect fun getEnv(name: String): String?
