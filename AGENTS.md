# Agent Notes

## Project

- This is an IntelliJ Platform plugin named `LLM Subscription Usage`.
- Main code is Kotlin under `src/main/kotlin`; tests are under `src/test/kotlin`.
- The plugin tracks LLM subscription quotas, exposes MCP tools, syncs IntelliJ MCP server URLs, and runs a local OpenAI-compatible subscription proxy.
- Build target is JVM 21 with Kotlin API/language version 2.3 to match IntelliJ 2026.1 bundled Kotlin.
- Credentials and API keys belong in IntelliJ Password Safe. Never add secrets, raw tokens, or generated logs to git.

## Validation

- Use focused tests during edits, for example `./gradlew test --tests some.TestName`.
- Before committing broad changes, run `./gradlew test`, `./gradlew verifyPlugin`, `./gradlew buildPlugin`, and `rtk git diff --check`.
- `verifyPlugin` warnings for `IntelliJVirtualThreads.ofVirtual()` are expected unless production runtime actually changed.
- Prefer sequential Gradle runs. If parallel Gradle work corrupts Kotlin incremental state, run `./gradlew --stop` and rerun sequentially.
- `build/`, plugin verifier reports, request logs, and generated plugin ZIPs are artifacts; do not stage them.

## Runtime Boundaries

- Keep IntelliJ-specific production code intact for plugin runtime. Do not replace `IntelliJVirtualThreads.ofVirtual()` with JDK `Thread.ofVirtual()` just to make standalone Gradle tasks work.
- `@Suppress("UnstableApiUsage")` is acceptable around IntelliJ APIs intentionally used by the plugin.
- The IDE supplies IntelliJ classes and bundled libraries such as `com.intellij.*`, Ktor server classes, and SLF4J at plugin runtime.
- Standalone JavaExec proxy tasks must include `sourceSets.main.compileClasspath` and an explicit Kotlin stdlib runtime because `runtimeClasspath` alone is not enough outside the IDE.
- If standalone proxy tasks fail with `NoClassDefFoundError` for IntelliJ, Kotlin, Ktor, or SLF4J classes, fix standalone Gradle classpaths first. Do not change plugin production code or packaging unless IDE runtime is also broken.
- Any IntelliJ-behavior shim must be standalone-only. It must not affect the packaged IntelliJ plugin.

## MCP JSON

- MCP quota and web search tools should return provider JSON directly when the provider response is already usable JSON.
- Do not normalize provider JSON into plugin-owned shapes just to make it prettier. Tests for web search should prefer provider-native response fields.
- If a provider response is not JSON, wrap it as plugin-owned JSON such as `{"raw_response":"..."}` so MCP tools still return valid JSON text.
- Use Kotlin serialization for plugin-owned MCP JSON, including tool status, plugin errors, raw-response wrappers, and request payload DTOs.
- Keep upstream response parsing minimal. Parse only for required error detection, authentication retry decisions, documented non-JSON formats, or unusable responses.

## Proxy Providers

- Keep proxy behavior separate from MCP behavior. The proxy may normalize, transform, or adapt payloads for OpenAI, Anthropic, or LiteLLM compatibility.
- The local proxy should preserve compatibility for `/v1/chat/completions`, `/v1/responses`, `/v1/models`, `/v1/model/info`, and their unprefixed route variants where supported.
- OpenAI/Codex model discovery is not authoritative enough for the advertised proxy list. Keep the curated OpenAI/Codex list aligned with the Codex UI unless a better authoritative endpoint is found.
- For providers with usable official model endpoints, such as SuperGrok/xAI and GitHub Copilot, prefer live discovery over hardcoded model fallbacks.
- `models.dev` may be used as a model catalog only when it explicitly separates subscription providers from API-key providers and the subscription provider has no usable first-party endpoint for discovering current subscription model IDs.
- When no provider-declared default exists, choose a default from advertised models by taking the alphabetically latest model id rather than hardcoding a recommendation.
- New proxy providers usually need settings support, `OpenAiProxyService` wiring, provider enablement defaults, status reporting, and tests.

## Settings And Releases

- Provider state is mostly map/list based in `QuotaSettingsState`; avoid adding per-provider fields unless persistence or migration requires them.
- Legacy settings fields are for migration only. Keep them readable when changing persisted data.
- Add changelog entries under `## [Unreleased]`. Do not edit released changelog sections unless explicitly requested.
- Keep this file concise and update it when project-level agent guidance changes.
- Do not push unless explicitly requested.
