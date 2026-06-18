# LLM Subscription Usage Changelog

## [Unreleased]

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
