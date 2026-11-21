---
name: provider-drift-audit
description: Diagnose broken provider login, quotas, model discovery, proxy routes, or MCP tools in LLM Subscription Usage. Audit changes in models, plans, API contracts, and client versions.
---

# Provider drift audit

Read `AGENTS.md`. Diagnose the reported symptom first; do not start with a
survey of every provider. Kotlin paths below are relative to
`src/main/kotlin/de/moritzf/`; tests mirror them in `src/test/kotlin/de/moritzf/`.

## First five minutes

1. Identify provider, account/plan, region or workspace, auth mode, model,
   failing route, and last known working version. Distinguish IDE vs standalone,
   quota vs inference, and subscription vs public API. They can fail separately.
2. If the IDE is running, use `subscription_tools_status` first to see cached
   account readiness without upstream calls, then `subscription_quota` for the
   affected `provider` and `account`, or the provider's raw response in settings.
   Select the correct IDE project if the tool requests one. Record a redacted
   status, error code, and relevant payload fields; don't paste credentials or
   full logs. A missing login, 401, 403, 429, and an empty parsed meter are
   different diagnoses. An unauthenticated response does not prove compatibility.
3. Use the matching row below, then open only the relevant entry in
   [Public provider sources](references/provider-sources.md). Search the exact
   error, endpoint, field, model slug, or header in that public repo and in this
   repo's implementation and tests. Check history from the last working release
   if it is known. Broaden only when those sources fail to explain the symptom.

| Symptom | Start here |
| --- | --- |
| Login, credentials, or wrong account | For a failure before the upstream call, start at `quota/idea/settings/AccountResolver.kt` and `quota/idea/operations/AccountOperations.kt`. For upstream auth, inspect `quota/idea/auth/QuotaAuthService.kt`, `OAuthClientConfig.kt`, and the provider OAuth client. Compare IDE vs standalone wiring before asking a user to sign in again. |
| Quota missing, wrong plan/reset, or UI and MCP disagree | Check `quota/<provider>/*QuotaClient.kt` and its test first. Raw response correct but UI wrong → provider quota model and `quota/idea/ui/popup/`. MCP alone wrong → `quota/idea/operations/SubscriptionStatusOperations.kt` and redaction/passthrough tests. Stale data → `quota/idea/common/QuotaUsageService.kt` and the last successful snapshot. |
| Model absent, misnamed, or wrong default | Compare the provider's model list for the same login with proxy `/v1/models` **and** `/v1/model/info`. Start at `proxy/subscription/SubscriptionModelCatalog.kt`, then the provider's `quota/<provider>/proxy/`; for Codex start at `proxy/model/ChatGptSubscriptionModels.kt`. Check plan/workspace filters, base ID vs alias, route support, and cached discovery before adding a static model. |
| Model listed but requests fail, tools disappear, or streaming stalls | Map local → upstream model and route in `proxy/subscription/SubscriptionProxyServer.kt` and the provider's proxy. For Codex-style bridges, check `proxy/server/ChatCompletionsRequestMapper.kt`, `ResponsesHandler.kt`, and `proxy/sse/`. Compare the outbound JSON and terminal SSE event against a minimal request; HTTP 200 alone is not success. |
| Wrong reasoning, FIM, vision, or document mode | Compare advertised capabilities with the outbound request. Start at `proxy/model/KnownReasoningLevels.kt`, `ModelAliasResolver.kt`, or `quota/<provider>/proxy/` for endpoint-specific rules. FIM → `proxy/fim/FimModels.kt`; settings-only → `quota/idea/settings/DocumentModelSelection.kt` or `VisionModelSelection.kt`; Codex tool defaults → `quota/idea/mcp/CodexMcpClient.kt`. |
| Version gate or feature silently withheld | Search for `User-Agent`, `Editor-Plugin-Version`, `X-Msh-Version`, or the reported gate. Check the upstream release **and** the version/header actually shipped. Don't bump an API date merely because a CLI released. |

## Freshness sweep

If no specific failure was reported, inventory `ProviderCatalog.kt` and
`QuotaProviderType.kt` in `quota/idea/common/`. Compare the latest release/tag
and Unreleased changes. Check static defaults, catalogs and client identities
first; dynamic providers need action only when discovery or behavior is wrong.
For each relevant change, record source URL and tag/commit/date, plan/route,
evidence (live, source-only, or not checked), and affected plugin file. An
announcement, public aggregator, or another client's login proves neither
access nor capabilities for this plugin's subscription path.

## Verify and finish

Use only the plugin's login or explicitly configured standalone credentials
for live checks. Do not read another tool's credential stores. Prefer read-only
quota/model probes. Never redeem credits, change plans, or run costly inference
without separate authorization. Preserve complete raw settings diagnostics and
provider-native MCP JSON as required by `AGENTS.md`; do not commit captures.

Test the failing boundary with a redacted fixture or a mock upstream. Run
`./gradlew test --tests '*<RelevantTest>'` first and follow `AGENTS.md` for
the full gate on code changes. Report what is fixed, what upstream still
rejects, and what could not be verified. Commit only when requested; do not
push or release without separate authorization.
