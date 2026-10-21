# Agent Notes

## Project and credentials

- `LLM Subscription Usage`: IntelliJ plugin for subscription quotas, MCP tools/server-URL sync, and a local subscription proxy. Production/tests are Kotlin-only in `src/main/kotlin` and `src/test/kotlin`; Java libraries are usable via interop. Target JVM 21, Kotlin API/language 2.3 (IntelliJ 2026.1).
- Store credentials in IntelliJ Password Safe. Authentication belongs to this plugin: browser login or user-entered keys. Never access other tools' auth files/stores, including Codex `auth.json` and ChatGPT local credentials.
- CLI exceptions only: Antigravity uses the documented `agy` 1.1.11+ JSON usage report, quota-only, one current CLI login; never inspect credentials, internal APIs, or conversations. Azure uses one plugin entry and documented `az account list`/`az account get-access-token`; never read `~/.azure` or call `az account set`. Do not generalize CLI-backed auth.

## Validation and boundaries

- Format with `./gradlew ktfmtFormat`; verify with `./gradlew ktfmtCheck` (also in `check`/CI). During edits use focused `./gradlew test --tests some.TestName`. Before broad commits run `./gradlew test verifyPlugin buildPlugin --no-parallel` and `rtk git diff --check`. Keep Gradle runs sequential.
- `ArchitectureBoundariesTest` uses ArchUnit/JUnit 5 on production bytecode, including generated classes. Exceptions must name documented class pairs; do not substitute source/import scans. Shared quota code may depend on libraries/shared code, never provider/proxy/IDE implementations. Shared PDF helpers belong in `quota.shared.document`.
- Use coroutine virtual time for polling/timeouts. Stop proxy test servers with `gracePeriodMillis = 0` after assertions; keep bounded waits for genuinely concurrent work.
- Preserve IntelliJ runtime APIs, including `IntelliJVirtualThreads.ofVirtual()`; its verifier warnings are expected. Intentional `@Suppress("UnstableApiUsage")` is acceptable.
- Standalone JavaExec needs `sourceSets.main.compileClasspath` plus explicit Kotlin stdlib; `runtimeClasspath` lacks IDE-supplied libraries (including Ktor/SLF4J). Fix standalone classpaths/shims, not plugin production code or packaging, unless IDE runtime also fails. Shims must stay out of the plugin.

## Provider responses and MCP

- **Settings:** retain complete raw responses in any format, including plain text/HTML and bodies the UI cannot parse.
- **MCP:** pass usable provider JSON through directly. Preserve unknown fields, non-reusable key hashes/IDs, metadata, and quota pools absent from the UI. This includes LiteLLM `/key/info`. Redact actual credentials/auth headers; never use quota-field allowlists or reconstruct responses from UI summaries. Test provider-native fields.
- MCP output must be valid JSON. Parse documented non-JSON formats into meaningful structured JSON; never use a `raw_response` envelope. Unparseable responses produce JSON errors; original diagnostics remain in settings. Parsing failure in the UI must not hide usable provider JSON from MCP.
- Multiple sources get a minimal, separately named JSON wrapper: e.g. Mistral `session` contains billing/Vibe/subscription; `api_key` contains identity/rate limits. Preserve complete segments, parse non-JSON sources such as subscription HTML, and keep usable segments when optional sources fail. Do not flatten or merge away provider fields.
- Use Kotlin serialization for plugin-owned JSON. Parse upstream data only as needed for errors/auth retries, documented non-JSON formats, or unusable responses. MCP annotations stay in the facade; execution in `idea.operations`, shared auth/account policy in `AccountOperations`. Preserve cancellation before adapting errors.

## Providers and proxy

- `ProviderCatalog` is the sole registration source (factories, codecs, UI, MCP, capabilities, credential probes). Registries are facades, not parallel maps. Proxy-only providers need no quota registration. Keep MCP enums synchronized with capabilities (`ProviderCatalogTest`); local `PDFBOX` has no catalog entry.
- `QuotaProviderType` identifies provider types; `ProviderAccount` identifies logins. Migrated first accounts retain `id == type.id`. Settings add/remove accounts; names must be nonblank/unique per type and appear only for multiple accounts of that type.
- OpenAI-compatible/LiteLLM accounts share transport/settings, retain stable account-scoped model IDs, and never fail over across endpoints. Unknown capabilities/prices stay unset; vision/native PDF are opt-in. Every provider's vision model defaults to `-` (off); document defaults are provider-specific.
- New providers need type/indicator IDs, one catalog entry, icon, applicable MCP enum/dispatch and standalone env wiring, tests, `plugin.xml`, README table, and Unreleased changelog entry.
- Proxy payload adaptation is separate from MCP pass-through. Preserve `/v1/chat/completions`, `/v1/responses`, `/v1/completions` (FIM enabled with proxy), `/v1/models`, `/v1/model/info`, and supported unprefixed variants.
- Prefer official live model discovery. Keep OpenAI/Codex's curated list aligned with the Codex UI until discovery is authoritative. Use `models.dev` only for explicitly separated subscription catalogs lacking usable first-party discovery. Without a provider default, choose the alphabetically latest advertised model ID.
- Proxy defaults use `subscriptionProxy`; IDE construction uses catalog `ideProxyFactory`/`IdeProxyFactories`. Resolve accounts once per request; bind refresh/save, endpoint/region, and rate-limit attribution to that account. `StandaloneSubscriptionProxy` stays env-based, without IntelliJ services. Kimi device identity is injected from `KimiDeviceIdStore` in IDE wiring, process-stable standalone.

## Settings and commits

- UI text belongs in `messages/OpenAiUsageQuotaBundle.properties` via `QuotaBundle.message`; reuse shared help keys. Protocol values, model IDs, and provider JSON stay out of the bundle.
- Use map/list-based `QuotaSettingsState`, synchronized quota snapshot writes, and `QuotaSettingsDraft`. `SettingsApplyCoordinator` owns commit/follow-up actions and one settings event. URL/model/key edits remain drafts until Apply.
- Document/vision/FIM dialogs share `TestDialogTask`/`TestDialogUi` and generation-check queued results. Tests capture draft account/model/target/keys on the EDT without Apply or temporary global settings. Completion tests call shared provider/FIM logic without a proxy server.
- Drop unreadable cached quotas after schema changes; do not add compatibility decoders for disposable snapshots.
- Changelog changes go under `## [Unreleased]`; released sections require explicit permission. Never stage secrets, logs, `build/`, verifier reports, or generated ZIPs. Do not push unless requested.
- Keep these notes focused on lasting project constraints; update them when those constraints change.
