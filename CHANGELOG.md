# LLM Subscription Usage Changelog

## [Unreleased]

## [1.6.8] - 2026-07-23
- Codex/team accounts no longer fail with “Request failed (200)” when OpenAI returns the newer spend-limit object shape; weekly usage and individual spend caps are shown again.

## [1.6.7] - 2026-07-21
- SuperGrok keeps the last real usage reading when Grok’s billing API returns period/plan config without usage numbers (instead of blanking the quota bar).

## [1.6.6] - 2026-07-19
- Claude popup and tooltip now show model-specific limits (for example a separate weekly cap for one model) as their own percentage bars when Claude reports them.
- The “Last used” status bar source no longer gets stuck on an idle provider: activity is now detected when any usage window grows, so work on a provider whose smaller limit (for example Claude’s 5-hour window) moves while a larger window stays put correctly switches the indicator.

## [1.6.5] - 2026-07-16
- OpenAI/Codex proxy now reports a current Codex CLI version to the upstream when the local Codex binary or npm lookup is unavailable, so GPT-5.6 models no longer fail with “requires a newer version of Codex”.
- SuperGrok billing parsing is now lenient: unknown fields and missing usage numbers are ignored instead of showing “Grok billing response changed.”, and the last good reading is kept when no usage is reported.

## [1.6.4] - 2026-07-15
- OpenAI/Codex proxy model list aligned with Codex UI GPT-5.6 family (`gpt-5.6-sol`, `gpt-5.6-terra`, `gpt-5.6-luna`) including `max`/`ultra` reasoning tiers where supported; default model is Sol.
- SuperGrok status bar no longer shows 100% / “limit reached” when weekly usage is only reported as a percent (used/limit both 0). Incomplete Grok billing payloads keep the last good reading instead of flashing an error.

## [1.6.3] - 2026-07-14
- Updated plugin description and README to cover Claude quota tracking, SuperGrok/xAI image and video generation, and the multi-provider subscription proxy.
- Local proxy request logging now redacts well-known secret fields (API keys, tokens, cookies, passwords) inside logged JSON bodies, matching the existing header redaction.

## [1.6.2] - 2026-07-11
- Claude quota refresh now silently keeps the last good reading when the Anthropic usage API returns HTTP 429 instead of showing a rate-limit error.

## [1.6.1] - 2026-07-10
- SuperGrok quota now uses the unified weekly billing endpoint (`billing?format=credits`) for weekly usage percent and reset, replacing the legacy monthly credits meter for unified-billing accounts.

## [1.6.0] - 2026-07-10
- Added Claude (Anthropic) subscription usage tracking with self-contained OAuth login (browser + paste callback) and the Claude OAuth usage API.
- Consolidated subscription image generation into one MCP tool (`subscription_image_generation`) for OpenAI/Codex and SuperGrok, with optional `targetFile` so agents can avoid large base64 payloads.
- SuperGrok image generation returns a single image URL by default, and uses b64 only when writing `targetFile`.
- Added SuperGrok/xAI Imagine video generation (`supergrok_video_generation`) using the existing SuperGrok login.
- `subscription_tools_status` now reports `image_generation_available` and `video_generation_available` per provider.
- Quota status now keeps the last good reading during short network hiccups instead of going blank.
- OpenAI and SuperGrok usage tracking recover more reliably after an expired login session.
- Quota refresh is more consistent when the status bar and chat tools refresh at the same time.
- Quota numbers stay up to date after restarting the IDE, even when usage grows slowly.
- OpenCode workspace selection is remembered automatically after the first successful lookup.
- Codex web search error messages from chat tools are easier for agents to parse.
- Local subscription proxy model lists and GitHub Copilot models stay more stable between requests and restarts.
- Local proxy requests time out more safely if an upstream provider hangs.
- Safer handling of remote images in GitHub Copilot Claude chat requests.

## [1.5.0] - 2026-07-02
- Consolidated provider-specific MCP quota tools into one `subscription_quota` tool with a provider parameter to reduce MCP tool context size.
- Consolidated per-provider list-search MCP tools (Kimi, Z.ai, MiniMax, Ollama) into one `subscription_web_search` tool with a `ListSearchProvider` enum parameter.
- Renamed the MCP toolset from `OpenAiUsageQuotaMcpToolset` to `SubscriptionUsageMcpToolset` to reflect its multi-provider scope.
- Replaced `web_search_tools_status` with `subscription_tools_status`, which reports per-provider quota configuration and web search availability.
- Web search error responses now list which search providers are currently configured so callers can retry with an available provider without an extra status call.

## [1.4.10] - 2026-06-27
- Moved IntelliJ MCP server URL sync target configuration into the MCP settings tab and added explanatory text about keeping agent configuration files aligned when the IDE MCP server port changes.
- Added proxy setup guidance for configuring the local LLM proxy in JetBrains AI Assistant or Junie CLI as a LiteLLM proxy.

## [1.4.9] - 2026-06-27
- Fixed GitHub Copilot MAI chat-completions routing by bridging `gh-mai-code-1-flash-picker` requests to the upstream `/responses` endpoint.
- Stripped unsupported `temperature` parameters from GitHub Copilot MAI bridged requests so JetBrains Junie helper calls no longer trigger upstream 400 errors.

## [1.4.8] - 2026-06-27
- Show ended or inactive GitHub Copilot subscriptions explicitly instead of displaying them as missing usage data.
- Added OpenAI-style streaming usage chunks for GitHub Copilot Claude chat-completions translation when clients request `stream_options.include_usage`.

## [1.4.7] - 2026-06-23
- Added a new Anthropic-to-OpenAI translation bridge for GitHub Copilot Claude models, exposing Claude Sonnet and Haiku through `/v1/chat/completions` and `/v1/models` for OpenAI-compatible clients such as Junie.
- Added standalone GitHub Copilot proxy login support with device-code login, clipboard copying, and local `.env` setup for API key and port configuration.
- Included Claude bridge support for streaming chunks, tool calls, legacy `functions/function_call`, JSON response mode hints, and OpenAI image URL content blocks.

## [1.4.6] - 2026-06-23
- Fixed proxy CORS preflight handling by avoiding Ktor `HttpMethod.Options` access, which could trigger illegal access exceptions at runtime.

## [1.4.5] - 2026-06-22
- Improved subscription proxy client compatibility by stripping unsupported Kimi chat temperatures, accepting string input on OpenAI/Codex `/v1/responses`, preserving GitHub Copilot GPT response fallbacks without output caps, and emulating chat stop sequences for SuperGrok.
- Cached the GitHub Copilot proxy model catalog across proxy restarts while keeping missing-model retry state in-process, so transient discovery drops are retried in the background before models are evicted.
- Added a shared Responses-backed chat-completions bridge and enabled it for GitHub Copilot GPT models that only expose `/responses` upstream.
- Adjusted Ollama proxy model metadata so models observed without reliable tool-call support are not advertised as function-calling capable to Junie and other LiteLLM-style clients.
- Fixed tool-choice compatibility for Responses-backed chat completions and downgraded unsupported forced Kimi tool choices to `auto` to avoid upstream 400s.
- Switched new GitHub Copilot logins to the Copilot CLI OAuth; log out and log back in to replace existing GitHub tokens.

## [1.4.4] - 2026-06-22
- GitHub Copilot proxy discovery now sends Copilot-compatible integration headers, advertises picker-visible non-Anthropic models on `/v1/models`, and keeps Claude models available through `/v1/model/info` and `/v1/messages`.

## [1.4.3] - 2026-06-22
- Kimi Code proxy models now use the managed `/models` endpoint plus the distinct `models.dev` Kimi For Coding catalog to expose current subscription model IDs, with `ki-kimi-for-coding` retained as a fallback.
- Hardened Kimi Code model discovery by caching fallback metadata when upstream discovery fails, validating the Kimi `models.dev` catalog endpoint, and routing prefixed fallback chat models.
- Added provider prefixes for all local proxy providers (`oa-`, `gh-`, `ki-`, `mm-`, `ol-`, `oc-`, `sg-`, `za-`) and route prefixed fallback models when discovery is stale or unavailable.

## [1.4.2] - 2026-06-21
- Fixed GitHub Copilot proxy model discovery so the proxy uses Copilot's live model metadata more accurately, including current GPT, Gemini, MAI, and Anthropic model routing.
- Fixed GitHub Copilot streaming chat compatibility for strict OpenAI clients by normalizing Copilot stream chunks into OpenAI-shaped responses.
- Hid Anthropic-only GitHub Copilot models from `/v1/models` while keeping them available through `/v1/model/info` and `/v1/messages` for Anthropic-compatible clients.
- Allowed prefixed fallback model IDs for GitHub Copilot, OpenCode Zen, and Ollama Cloud when provider model discovery is stale.

## [1.4.1] - 2026-06-21
- Ollama and OpenCode Zen proxy models now use clear provider prefixes (`ol-` and `oc-`) to avoid name collisions with similarly named models from other providers.
- Kimi proxy models are now labeled as Kimi Code, making it clearer which Kimi subscription endpoint they use.

## [1.4.0] - 2026-06-21
- The local OpenAI-compatible proxy can now use more of your subscriptions: Kimi, MiniMax, Ollama Cloud, OpenCode Zen, Z.ai, SuperGrok/xAI, and GitHub Copilot can be exposed alongside OpenAI/Codex.
- Added SuperGrok/xAI web search for MCP clients using your existing SuperGrok login.
- Improved local proxy compatibility for AI clients, including richer model discovery and better support for GitHub Copilot chat models.
- MCP web search responses now preserve provider-specific result details instead of flattening them into a simplified format.
- Settings pages now blend more consistently with the current IntelliJ theme.

## [1.3.0] - 2026-06-19
- Dropped IntelliJ IDEA 2025.x support, raising the minimum supported version to 2026.1 so the plugin can use newer Kotlin and bundled Ktor APIs.
- Show Codex Spark and other model-specific Codex quotas separately from the main Codex usage windows when OpenAI reports additional rate limits.
- Improved the local OpenAI-compatible proxy while preserving the existing client-facing API, including LiteLLM-compatible routes, scalar JSON responses, streaming responses, CORS preflight handling, usage reporting, and double-response safety.
- Restricted the integrated proxy's default CORS behavior to loopback browser origins.
- Added GitHub Enterprise host support for GitHub Copilot login and usage requests.
- Hid unlimited GitHub Copilot chat and completion windows from the popup and tooltip so only actionable limits are shown.
- Updated Cursor usage parsing for the modern usage-summary API, legacy request quotas, and on-demand/team budget fields.
- Fixed Kimi token refresh so stale access tokens can recover after an upstream 401/403 when a refresh token is available.
- Fixed Z.ai quota reset windows by mapping days, hours, minutes, and weeks to the correct units.
- Redacted secret-like fields from cached raw provider responses while preserving quota counters for debugging.

## [1.2.0] - 2026-06-18
- Added Codex reset credit display and one-click reset redemption in the quota popup, with confirmation before consuming a reset.

## [1.1.1] - 2026-06-17
- Fixed SuperGrok billing timeout responses by retrying transient Grok cancellations and showing a clearer timeout error.
- Fixed OAuth credential isolation so logging out of SuperGrok cannot clear OpenAI credentials.
- Improved OAuth token expiry handling by deriving expiry from `expires_in`, JWT `exp`, or a safe one-hour fallback.

## [1.1.0] - 2026-06-17
- Added support for **SuperGrok** monthly credit usage quotas via plugin-managed xAI/Grok OAuth and the Grok CLI billing API, including indicator, popup, settings, cache, and MCP usage access.
- Fixed provider settings so opening the settings page selects the first provider in the custom popup order instead of the alphabetically first provider.
- Split OpenAI/Codex settings into separate **Usage Tracking** and **Proxy** tabs.

## [1.0.0] - 2026-06-16
- Added a local **OpenAI-compatible proxy** backed by the existing OpenAI/Codex login for clients such as JetBrains Junie, with copyable base URL/API key setup and optional request/response logging.
- Added MCP web search tools for **OpenAI/Codex**, **Kimi**, **Z.ai**, **MiniMax**, and **Ollama**, including configurable OpenAI/Codex search controls/source metadata and a status tool that reports which search providers are configured before callers try them.
- Added hosted Codex MCP image generation, including saving generated images directly to a file.
- Added support for **GitHub Copilot** subscription usage quotas, including GitHub device login, popup/indicator display, settings, caching, and MCP usage access.
- Improved quota display consistency by showing provider usage as percentages more consistently and labeling quota windows by duration where available.

## [0.17.3] - 2026-06-07
- Preserve original raw provider responses in settings after IDE restarts
- Refresh quota providers concurrently while still waiting for all providers to finish
- Added explicit Jsoup dependency and shared form encoding logic
- Reduced duplicated provider state and quota refresh plumbing

## [0.17.2] - 2026-06-07
- Fixed OpenCode Zen credit balances not displaying when no OpenCode Go usage windows are available
- Fixed OpenCode Zen-only responses that return `null` Go quota data so billing credits still display correctly
- Show both the OpenCode Go and billing fallback responses in the OpenCode settings response viewer
- Aligned Z.ai and OpenCode icon colors with the light and dark theme palette

## [0.17.1] - 2026-06-07
- Fixed MCP sync target removal so deleting a row clears the active table editor and selection
- Fixed property picker preselection to respect a valid saved path before falling back to auto-discovery
- Updated MCP server URL sync settings and documentation for JSON, TOML, and YAML targets

## [0.17.0] - 2026-06-06
- Keep local AI clients connected to IntelliJ automatically by syncing the current MCP server URL into JSON, TOML, or YAML config files
- Set up sync targets with file validation, property selection, and live MCP server status in settings

## [0.16.0] - 2026-06-03
- Removed Gemini quota support and updated the plugin description to list the currently supported providers. Google has reoriented Gemini CLI toward Antigravity and now blocks third-party harnesses, making this integration unreliable.

## [0.15.0] - 2026-06-01
- Improved OpenAI/Codex usage decoding for business and pay-per-use plans: the indicator and popup now show assigned-credit state, individual spend cap, and workspace-level limit reasons (`workspace_member_credits_depleted`, `workspace_member_usage_limit_reached`, `workspace_owner_*`)
- Added coverage for additional real-world `self_serve_business_usage_based` and `prolite` response shapes (additional rate limits, omitted optional fields) and inferred business scenarios (balance, unlimited, individual spend cap, overage)

## [0.14.0] - 2026-06-01
- Added a dual-track percentage bar indicator that shows quota usage alongside billing period progress
- Improved 70–90% warning visibility in light mode with an orange usage color

## [0.13.0] - 2026-05-29
- Added support for **Cursor** subscription usage quotas, including popup, indicator, settings, caching, and MCP tooling
- Cursor auth now supports browser session cookies (`WorkosCursorSessionToken` from cursor.com), with optional fallback to local Cursor IDE state

## [0.12.0] - 2026-05-27
- Added support for **Gemini** subscription usage quotas
- Improved settings layout: provider icons now act as clickable tabs and support drag-to-reorder

## [0.11.0] - 2026-05-06
- Unified "limit reached" warnings across all providers with a clear red status message in the popup
- Hovering over the indicator now shows a detailed, compact summary of usage for all active limits
- Improved reliability: percentage and reset time stay visible in the toolbar even when out of quota
- Fixed a bug where the preferred quota source would occasionally reset to OpenAI
- Cleaner, more consistent time and duration formatting across the entire UI


## [0.10.2] - 2026-05-06
- Stabilized OpenAI quota response parsing (added hysteresis) to prevent the "Last used" source indicator from bouncing incorrectly when usage drops slightly from 100% to 99%

## [0.10.1] - 2026-04-30
- Fixed MCP tools for OpenCode and Ollama to always return properly serialized JSON instead of raw response data

## [0.10.0] - 2026-04-29
- Added support for **Z.ai**, **MiniMax**, and **Kimi** usage quotas
- Quota popup provider order is now customizable via drag-and-drop in settings
- Improved popup sizing stability: no more flickering, shrinks correctly when sections hide
- Normalized Ollama icon colors for light/dark themes

## [0.9.1] - 2026-04-28
- Fixed "Last used" indicator source to detect the active provider by actual usage increase, not just fetch timestamp
- Fixed status bar not repainting on Ollama quota updates
- Removed balance from the OpenCode indicator bar to save space

## [0.9.0] - 2026-04-28
- Added support for Ollama Cloud usage quotas, including popup, indicator, settings, caching, and MCP tooling
- Improved quota popup stability while provider data loads asynchronously
- Made indicator display rules more consistent across OpenAI, OpenCode Go, and Ollama Cloud
- Grouped provider refresh timestamps in the popup footer

## [0.8.0] - 2026-04-26
- Added support for OpenCode Go subscription usage quotas
- Added MCP tooling support for OpenCode usage queries
- Renamed plugin to "LLM Subscription Usage"
- Various UI and UX improvements
- Fixed classpath issue with `kotlinx.serialization.json` dependency

## [0.7.3] - 2026-04-18
- Improved login stability to avoid incorrect signed-out states and unnecessary credential resets
- Made quota parsing and persistence more robust when OpenAI returns incomplete or unexpected responses

## [0.7.2] - 2026-04-14
- Fixed indicator always showing the correct 100% when the Codex limit is reached, using the reset time of the window that keeps usage blocked the longest

## [0.7.1] - 2026-04-08
- Switched OAuth networking and callback server handling from Ktor to Java standard classes (`java.net.http.HttpClient` and `HttpServer`) to avoid runtime conflicts on IntelliJ 2025.3
- Improved OAuth login error handling and callback reachability diagnostics
- Added a "Copy URL" fallback action during login in plugin settings

## [0.7.0] - 2026-04-04
- Added an indicator location setting so the quota icon can live in the main toolbar or the status bar

## [0.6.1] - 2026-04-04
- Fixed login/logout edge cases that could leave authentication in a bad state
- Fixed the quota popup so it updates while it is open
- Fixed the quota popup not closing when opening settings
- Moved the plugin settings page under `Tools`

## [0.6.0] - 2026-04-03
- Migrated the plugin codebase to Kotlin
- Reworked the status bar, popup, and settings UI

## [0.5.0] - 2026-04-03
- Access to usage quota with MCP tooling

## [0.4.1] - 2026-03-28
- Expanded cake diagram icons to full 5% steps from 0% to 100%

## [0.4.0] - 2026-03-23
- Added different display modes for the status bar
- Added a settings button to the quota popup

## [0.3.1] - 2026-03-16
- Explicit icons for dark and light mode
- Improved plugin description and documentation

## [0.3.0] - 2026-03-14
- Improved layout of quota state popup
- Added "review" quota to quota state popup

## [0.2.2] - 2026-03-05
- First public release
- Status bar widget showing quick quota state
