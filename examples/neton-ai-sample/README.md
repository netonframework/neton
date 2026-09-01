# neton-ai sample

Standalone (Mode 1) demonstration of neton-ai v0.1.

## Build

```bash
./gradlew :examples:neton-ai-sample:build
```

## Run

```bash
export OPENAI_API_KEY="sk-..."          # optional
export ANTHROPIC_API_KEY="sk-ant-..."   # optional
./gradlew :examples:neton-ai-sample:runDebugExecutableMacosArm64
```

If neither key is set, the sample compiles and binds the client but skips live API calls.

## What it demonstrates

- `HttpClient.create { ... }` — standalone HTTP client
- `AiClient.create { httpClient = ...; providers { ... }; routing { ... } }` — standalone AI client (Mode 1, no Neton runtime)
- `ai.generateText { user("...") }` — non-streaming chat
- `ai.streamText { user("...") }.collect { ... }` — SSE streaming
- `ai.embed { model = "..."; input("...") }` — embeddings
