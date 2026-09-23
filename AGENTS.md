# Agent Notes

## Project

- This is an IntelliJ Platform plugin named `LLM Subscription Usage`.
- Main code is Kotlin under `src/main/kotlin`; tests are under `src/test/kotlin`.
- The plugin tracks LLM subscription quotas, exposes MCP tools, syncs IntelliJ MCP server URLs, and runs a local OpenAI-compatible subscription proxy.
- Build target is JVM 21 with Kotlin API/language version 2.3 to match IntelliJ 2026.1 bundled Kotlin.
- Credentials and API keys belong in IntelliJ Password Safe. Never add secrets, raw tokens, or generated logs to git.
- Auth is self-maintained: browser login or keys the user enters in this plugin. Never read, write, watch, or import auth files other tools put on disk (Codex `auth.json`, ChatGPT local, OS stores of other apps). Do not add “import from ~/.codex” or similar.
- Antigravity is a CLI exception: quota-only through the official `agy` CLI (1.1.11+), using its own login. Invoke the documented JSON usage report; never inspect its credentials, internal APIs, or conversation files. One current CLI account only.
- Azure is a separate CLI exception: one plugin entry, through documented `az account list` and `az account get-access-token` only. Never read `~/.azure`. Do not call `az account set`. Do not extend CLI-backed auth to other providers or build a general CLI-provider framework.

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

## Providers

- Register each provider once in `ProviderCatalog` (`ProviderDescriptor`: quota factory, snapshot codec, settings/UI factories, MCP quota export, capability flags, credential probes).
- `QuotaProviderRegistry`, `ProviderSettingsRegistry`, `ProviderUiRegistry`, and `UsageQuotaMcpRegistry` are facades over the catalog — do not add parallel per-provider maps.
- Keep `QuotaProviderType` as the catalog / MCP type id. Runtime logins are `ProviderAccount` rows (`id`, `typeId`, `name`, `isDefault`, `allowFailover`). Migrated first accounts keep `id == type.id`.
- Settings list is add/remove accounts, not a fixed type inventory. Names are unique per type; Apply blocks duplicates/blanks. Show the name only when that type has two or more accounts.
- Capability flags drive proxy-supported lists and MCP status. Keep MCP param enums (`ListSearchProvider`, `WebFetchProvider`, `ImageGenerationProvider`, `SpeechToTextProvider`, `TextToSpeechProvider`, `DocumentToMarkdownProvider`) in sync with capabilities (`ProviderCatalogTest` enforces this).
- New provider checklist: `QuotaProviderType` (+ `QuotaIndicatorSource` if shown in the status bar), one `ProviderCatalog` entry (including `ideProxyFactory` when `subscriptionProxy`), icon/`QuotaIcons`, MCP enum + tool dispatch arm if needed, standalone env wiring if proxy CLI, tests, `plugin.xml` + `README` table, changelog.

## Proxy Providers

- Keep proxy behavior separate from MCP behavior. The proxy may normalize, transform, or adapt payloads for OpenAI, Anthropic, or LiteLLM compatibility.
- The local proxy should preserve compatibility for `/v1/chat/completions`, `/v1/responses`, `/v1/completions` (FIM adapter, on when the proxy is on), `/v1/models`, `/v1/model/info`, and their unprefixed route variants where supported.
- OpenAI/Codex model discovery is not authoritative enough for the advertised proxy list. Keep the curated OpenAI/Codex list aligned with the Codex UI unless a better authoritative endpoint is found.
- For providers with usable official model endpoints, such as SuperGrok/xAI and GitHub Copilot, prefer live discovery over hardcoded model fallbacks.
- `models.dev` may be used as a model catalog only when it explicitly separates subscription providers from API-key providers and the subscription provider has no usable first-party endpoint for discovering current subscription model IDs.
- When no provider-declared default exists, choose a default from advertised models by taking the alphabetically latest model id rather than hardcoding a recommendation.
- Proxy enablement defaults come from `ProviderCapabilities.subscriptionProxy` via the catalog; IDE construction uses `ideProxyFactory` / `ProviderCatalog.createIdeProxyProviders` (`IdeProxyFactories`). Standalone CLI proxy wiring stays env-based in `StandaloneSubscriptionProxy` (no IntelliJ services).

## Settings And Releases

- Provider state is mostly map/list based in `QuotaSettingsState`; avoid adding per-provider fields unless persistence or migration requires them.
- Do not keep backwards-compat decode paths just to revive old cached quota snapshots. If the cache shape changes, drop unreadable entries and wait for the next live refresh.
- Add changelog entries under `## [Unreleased]`. Do not edit released changelog sections unless explicitly requested.
- Keep this file concise and update it when project-level agent guidance changes.
- Do not push unless explicitly requested.
