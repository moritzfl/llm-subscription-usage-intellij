# OpenAI Usage Quota Changelog

## [Unreleased]

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
