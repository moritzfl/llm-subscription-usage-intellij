# LLM Subscription Usage Changelog

## [Unreleased]

- Local proxy can expose `POST /v1/completions` as a FIM adapter for JetBrains AI Completion. Off by default. Pick one completion model on the Proxy tab and use the stable id `qwen2.5-coder` in the IDE (JetBrains Auto maps that name to `(fim) Qwen`) so switching models does not require reconfiguring AI Assistant. Chat models go through an optional FIM-to-chat adapter; native `/v1/completions` pass-through is available when the adapter is off. Test FIM shows the sample hole, the insert, and the assembled snippet. Best-effort AI Completion setup check.
- FIM setup help is a field list (provider, base URL, API key, model, prompt schema Auto). No “not Zeta/Sweep”, “no /v1”, or Call Inline Completion warnings.
- Proxy tab links into JetBrains AI Assistant: Providers & API keys, and AI Completion.

## [1.11.7] - 2026-09-09

- MCP tools that take a project-relative `localFile` or `targetFile` (document-to-markdown, image, audio, video) now resolve that path in the project of the MCP call, like IntelliJ’s built-in file tools. They no longer pick the first open project in the IDE.
- Mistral document-to-markdown always writes `<name>.md` beside a local source when `outputFile` is omitted. `includeImages` only controls whether extracted pictures are saved, not whether markdown is written.
- Mistral OCR no longer requests unused paragraph bounding boxes, so the conversion response stays smaller.

## [1.11.6] - 2026-09-07

- Codex popup no longer shows “Assigned credits: Depleted” when `credits.has_credits` is false but `spend_control` still has remaining assigned credits (for example Business Prolite 3.14 / 10).

## [1.11.5] - 2026-09-06

- OpenAI/Codex proxy hops `usage_limit_reached` to `gpt-reserve` only for Luna (`gpt-5.6-luna*`). Higher-tier models stay on the original error.

## [1.11.4] - 2026-09-06

- OpenAI/Codex proxy lists base model ids only (`gpt-6-astra`, `gpt-5.6-*`, `gpt-reserve`, `gpt-5.5`). Reasoning-tier name aliases are gone; clients send `reasoning_effort`.
- On Codex `usage_limit_reached`, the proxy retries once as `gpt-reserve` (Luna Reserve) and echoes the original model. Codex is not treated as exhausted while GPT Reserve still has room.

## [1.11.3] - 2026-09-06

- OpenAI/Codex proxy advertises `gpt-6-astra` (Codex UI list, priority 1) with `low`/`medium`/`high`/`xhigh`/`max`/`ultra` reasoning tiers. Default advertised model is Astra.

## [1.11.2] - 2026-09-03

- Ollama monthly credit-plan no longer guesses a reset time. `/api/usage` has no subscription-anniversary stamp, so the bar shows percent and the 30-day window length until the API includes a real reset.

## [1.11.1] - 2026-09-03

- Ollama Cloud credit plans (`limits.monthly`) parse as a monthly usage window. Legacy session/weekly limits still work. Countdown uses `activity.period.ending_at`; `starting_at` is the period start.

## [1.11.0] - 2026-09-02

- OpenAI/Codex and SuperGrok document-to-markdown can extract embedded figures. For a local PDF with `includeImages=true`, the vision model emits a normalized bounding box per figure and the plugin renders that exact page region (via bundled PDFBox) to `image-p<page>-<index>.png`. When an image file is not written, the alt text becomes a `**Figure.**` caption instead of a dangling image link. Coordinates are model-estimated and can be imprecise; crops never fall back to a whole page. Extraction stays optional via `includeImages`.
- Codex/SuperGrok document-to-markdown accept 1-based `pageFrom`/`pageTo` on local PDFs so an agent can convert a large file in slices. Results include `page_count`. Overflow still fails that slice; there is no automatic chunking.
- PDFBox for document figure crops is 3.0.8, with JPEG2000 and JBIG2 ImageIO plugins (jbig2-imageio 3.0.5). Those readers are registered on the plugin classloader. A page that cannot be rasterized skips that crop instead of failing the whole conversion.
- OpenAI/Codex MCP document-to-markdown defaults to `gpt-5.6-sol`. Web search and image generation default to `gpt-5.6-luna`.
- SuperGrok document-to-markdown defaults to `grok-4.6`. Web search defaults to `grok-4.3`.

## [1.10.5] - 2026-08-31

- Codex speech-to-text streams local audio uploads through a BodyPublisher instead of buffering the whole file.
- Mistral image generation writes a unique `image-<uuid>.png` when `targetFile` is omitted.
- Mistral TTS reference audio is rejected above the 1 GB inline limit.
- MCP video generation can download the result to `targetFile` instead of only returning a URL.

## [1.10.4] - 2026-08-31

- MCP text-to-speech writes a unique `speech-<uuid>` file when `targetFile` is omitted, so parallel generations do not overwrite each other.
- MCP document-to-markdown does not request or return embedded image base64 unless it is writing markdown and images to disk.
- Mistral, SuperGrok, and Z.ai speech-to-text stream local audio uploads instead of loading the whole file into memory.

## [1.10.3] - 2026-08-31

- MCP image generation never returns base64. SuperGrok always requests a download URL and fetches it when writing `targetFile`. OpenAI/Codex has no image URL and writes a unique `image-<uuid>.png` in the project when `targetFile` is omitted.
- MCP document-to-markdown streams Mistral and SuperGrok file uploads. OpenAI/Codex and Z.ai still inline local files and reject anything over 1 GB.
- Ollama MCP quota JSON keeps the provider usage payload and adds computed `resets_at.session` / `resets_at.weekly` at the root.

## [1.10.2] - 2026-08-28

- Settings account list icons stay 16px when OpenAI/Codex or OpenCode is added. Those logos were 300px and were drawn at full size.

## [1.10.1] - 2026-08-27

- Mistral Tokens/min and Requests/min stay visible when a refresh gets monthly usage but misses the per-minute headers.

## [1.10.0] - 2026-08-27

- Provider settings is now an account list. Add or remove logins, including more than one of the same type. Each account has its own name, credentials, and quota. Names appear in settings, the popup, and the status-bar tooltip only when that type has two or more logins. Add, remove, default, standby, host/region, and OpenCode workspace edits wait until you Apply; Cancel drops unapplied changes.
- When a type has two or more logins, pick which account MCP tools and the local proxy use first, and whether the others are tried if that quota is exhausted. Routing is one header line on that account’s settings page. Codex and SuperGrok reset redeem stays on that account.
- MCP `subscription_quota` accepts an optional account name. `subscription_tools_status` lists each account and which of its tools are configured. Unpinned spend tools and the local proxy use the Default account for that type, then standby logins when that quota is exhausted.
- The quota popup shows one block per account. Status-bar Last used, GitHub enterprise host, MiniMax region, and reset redeem follow the account that was used. Last used looks at every login’s timestamp, not only the first account of a type.
- Local proxy and Codex MCP refresh the login that owned the rejected token, instead of resolving again after a 401.
- SuperGrok and Codex banked reset tokens show when that token expires.
- Quota percents accept integers, decimals, and numeric strings from every provider, including OpenCode Go values such as `15.2`.

## [1.9.1] - 2026-08-26

- Codex plan names are shown as title case with `self_serve` stripped, so `self_serve_business_prolite` appears as Business Prolite.

## [1.9.0] - 2026-08-24

- SuperGrok quota popup can show and redeem a weekly usage reset when grok.com reports one, with confirmation before consuming it.

## [1.8.1] - 2026-08-19
- Settings → Subscription Usage now uses a vertical provider list with search, status dots, and a detail pane, so more providers no longer widen the window. Drag or Alt+Up/Down still sets popup and indicator order. Last quota response can be copied. An eye toggle shows whether a provider is in the quota popup. Logged-out providers stay gray; a yellow dot means configured but the last refresh failed, with the reason shown next to the name.

## [1.8.0] - 2026-08-19
- Added Mistral. Paste an `admin.mistral.ai` session cookie (`ory_session_*` plus `csrftoken`) for monthly billing and Vibe usage, and a Mistral API key for per-minute token and request limits, MCP tools, and the local proxy. Per-minute usage is shown as a percent only (no reset countdown). Settings and MCP quota JSON show session usage and API-key rate limits together.
- More subscription MCP tools, using the logins and keys you already have:
  - Web search — Mistral Conversations, plus the existing OpenAI/Codex, SuperGrok, Kimi, Z.ai, MiniMax, and Ollama tools.
  - Images — OpenAI/Codex, SuperGrok, Mistral, Z.ai, and MiniMax.
  - Video — SuperGrok and Z.ai.
  - Voice — speech-to-text and text-to-speech on OpenAI/Codex, SuperGrok, and Mistral; speech-to-text on Z.ai; text-to-speech on MiniMax.
  - Documents — convert a PDF or image to markdown. Mistral and Z.ai also write extracted images next to the markdown; OpenAI/Codex and SuperGrok return markdown only.
- Mistral can be exposed through the local OpenAI-compatible proxy.
- The quota popup groups providers that share the same update time instead of repeating “just now” for each icon.

## [1.7.9] - 2026-08-16
- Status bar quota tooltip now uses the same short wording for every provider: subscription, plan when known, used percent with window kind, and time until reset.

## [1.7.8] - 2026-08-11
- Claude refresh failures no longer delete the stored login. The plugin adopts newer tokens shared by another IDE and keeps rotated tokens authoritative while retrying failed Password Safe writes.
- Fixed Claude refresh retries so ambiguous connection drops and 5xx responses do not immediately resend a refresh token Anthropic may already have rotated. Only failures known to happen before the request is sent are retried.

## [1.7.7] - 2026-08-10
- Ollama Cloud now shows a real countdown for session and weekly limits. When the usage API omits reset times, the plugin uses the global schedule (5-hour session grid and Monday 00:00 UTC weekly).
- Dropped old settings/cache compatibility shims. Outdated cached quota snapshots are ignored until the next successful refresh instead of being force-decoded.

## [1.7.6] - 2026-08-09
- "Last used" status bar source correctly switches to OpenCode (and other providers) after real usage, instead of staying stuck on OpenAI at 0%. Provider selection now uses one shared id everywhere (`opencode`, `openai`, …) so enum names can no longer be confused with storage keys. The last active provider is kept across IDE restarts (it was already saved, but the id mismatch made the status bar ignore it and fall back to OpenAI).

## [1.7.5] - 2026-08-08
- "Last used" status bar source now tracks slow usage growth and per-limit changes correctly. It no longer stays stuck on an old provider when another subscription is being used (for example OpenCode Go, Ollama, or SuperGrok while Codex is maxed out).

## [1.7.4] - 2026-08-06
- Ollama Cloud MCP `subscription_quota` now returns the provider usage JSON directly, like the other providers, instead of a plugin-shaped copy.

## [1.7.3] - 2026-08-06
- Ollama Cloud no longer shows “unknown” for the time left on session/weekly usage. The official usage API does not send a reset timestamp yet, so the plugin shows the known window length (5h / 7d) instead until a real countdown is available.
- Ollama Cloud no longer shows a plan name. The usage API does not include one, and the plugin no longer guesses.

## [1.7.2] - 2026-08-02
- Running two JetBrains IDEs no longer costs you a browser login (Claude, OpenAI/Codex, SuperGrok). Both IDEs share the same password storage and refresh the same login, and the IDE that lost the race used to throw the login away; it now notices the newer login and keeps using it.
- A connection that drops while a login is being refreshed no longer costs you that login either. The refresh is now only repeated when it never reached the provider, because repeating it afterwards makes the provider reject the login for good.
- The settings window now warns when the IDE is set to forget passwords after restart. That setting makes every login, session cookie, and API key of every provider disappear when the IDE closes, which otherwise just looks like the providers logging you out.
- Login problems are now traceable in the IDE log (Help | Show Log): every login refresh records why it ran, whether it succeeded, why it was rejected, and whether another IDE changed the stored login. No tokens or keys are written to the log.

## [1.7.1] - 2026-07-29
- MCP sync targets table in settings now shows its add and remove buttons at the top.

## [1.7.0] - 2026-07-29
- Claude no longer asks you to log in again after a network or server hiccup: refreshing your login is retried, and a refresh that still fails keeps both your login and the last usage numbers instead of showing “login required”.
- Ollama Cloud quota now uses the official usage API with your API key (`GET https://ollama.com/api/usage`). Session cookies and HTML scraping were removed; one API key covers quota, web search, and the local proxy, and the stored Ollama cookies are deleted from Password Safe.
- A temporary problem no longer blanks the quota: while you are offline, timed out, rate limited, or the provider has a server hiccup, the last known usage stays on screen with its “Updated” time instead of being replaced by an error. Problems you need to act on (expired login, wrong API key) are still shown right away, and the settings page keeps showing the exact failure.
- Ollama usage parsing is now lenient: unknown fields and a reshaped or unreadable block (for example per-model details or one of the two limits) only hide that part instead of the whole quota.
- The settings page now uses the whole window: every tab grows with the dialog instead of being fixed to the height of the largest one, and the “Last quota response” and “Advertised models” boxes expand into that space rather than scrolling inside a small box with empty space below.

## [1.6.14] - 2026-07-28
- SuperGrok now shows 0% used (with reset timing) when a new/unused billing period has no usage numbers yet, instead of an empty quota section.
- MCP `subscription_tools_status` now treats OpenCode and Ollama Cloud quota as configured when their session cookie is set (not only when an API key is present).

## [1.6.13] - 2026-07-27
- The local proxy no longer reports an IDE error when a client cancels a request mid-answer (for example when you stop a running completion); an aborted connection is now treated as normal and logged quietly.

## [1.6.12] - 2026-07-27
- OpenAI tooltip now shows the individual spend cap as a single short line in credits (for example “Individual limit: 10 credits (reached)”) instead of repeating the same information twice and labelling credits as dollars.

## [1.6.11] - 2026-07-26
- OpenAI/Codex inference no longer fails when Codex version lookup is slow or unavailable; inference requests now identify the plugin directly instead of reporting a Codex CLI version.
- Reworked the README and Marketplace description around a supported-provider table and shorter feature sections.

## [1.6.10] - 2026-07-25
- Claude usage no longer fails with “Claude usage response changed.” when the extra-usage credits are reported as decimal numbers (for example `4403.0`); credit amounts now accept integers, decimals, and numeric strings.
- OpenAI tooltip no longer shows a bogus “Assigned credits: Depleted” line for Plus/Free accounts whose payload contains an empty spend-control block.
- Quota parsing is now resilient to partial payload changes: an unparsable credit or limit block (Claude windows/limits/extra usage, OpenAI credits/spend control/additional rate limits, Cursor plan/profile documents, Z.ai subscription info) only hides that block instead of breaking the whole quota display.

## [1.6.9] - 2026-07-24
- OpenAI/Codex proxy now prefers the plugin’s verified Codex client version over an older local Codex install; a newer local/npm version can still raise it.

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
