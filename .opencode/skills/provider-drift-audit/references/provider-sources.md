# Public provider sources

Use the provider's official docs for the contract and its public source or
release artifact for shipped behavior. For a regression, inspect the source
tag matching the failing version first, then the latest tag/default branch.
If a path moved, search that public repo for the named symbol, endpoint, or
response field. Verify subscription behavior using the same route and account
type; use independent implementations as leads, not as authority.

## OpenAI / Codex

- **Missing model or wrong metadata:** Start with
  [Codex release notes](https://developers.openai.com/codex/changelog) and
  [Codex `codex-rs/models-manager/models.json`](https://github.com/openai/codex/blob/main/codex-rs/models-manager/models.json).
  Read `slug`, `visibility`, `priority`, `context_window` vs `max_context_window`,
  `supported_reasoning_levels`, `input_modalities`, and `service_tiers`.
  Compare [plan availability](https://developers.openai.com/codex/pricing) and
  `ChatGptSubscriptionModels.kt`. Keep curated visibility while model discovery
  is incomplete. A `-fast` alias may be a base model plus `service_tier`, not an
  upstream slug. Do not use API limits/pricing for Codex subscriptions.
- **Quota, resets, or stale login:** In [Codex](https://github.com/openai/codex),
  read `codex-rs/backend-client/src/client/rate_limit_resets.rs` and
  `codex-rs/app-server/tests/suite/v2/{rate_limits,rate_limit_reset_credits,luna_reserve}.rs`.
  Search `wham/usage` and `rate-limit-reset-credits` in that repo and in
  `quota/openai/OpenAiCodexQuotaClient.kt`. Compare the
  [Sign in with ChatGPT contract](https://developers.openai.com/siwc/token-sharing-open-source)
  and [preview limits](https://developers.openai.com/siwc/token-sharing-open-source/preview-limitations).
  Before switching auth, verify quota, reset-credit listing, and inference
  independently; a token that works on the public Responses route may fail on
  the Codex backend. Do not redeem a reset as part of a probe.
- **Public API vs Codex behavior:** Use the [API changelog](https://developers.openai.com/api/docs/changelog),
  [model guide](https://developers.openai.com/api/docs/guides/latest-model),
  and [deprecations](https://developers.openai.com/api/docs/deprecations) for API
  parameters. For a second implementation, compare OpenCode's
  `packages/core/src/plugin/provider/{chatgpt,openai-context-caps}.ts`
  ([public source](https://github.com/anomalyco/opencode/tree/dev/packages/core/src/plugin/provider));
  search `fallbackModels`, `modelID`, `service_tier`, and `currentInput`.
  OpenCode overrides and catalog entries do not establish Codex entitlement.

## Other providers

The plugin paths below are relative to `src/main/kotlin/de/moritzf/`.

| Provider | Best first public sources | Plugin entry points and distinctions |
| --- | --- | --- |
| Anthropic / Claude | [Claude Code changelog](https://code.claude.com/docs/en/changelog), [Anthropic API release notes](https://platform.claude.com/docs/en/release-notes/overview), [usage limits](https://support.claude.com/en/articles/9797557-usage-limit-best-practices) | `quota/claude/ClaudeQuotaClient.kt`, Claude popup/reset tests. Compare Claude.ai usage/reset details to OAuth usage and distinguish rate limits from a reset grant withheld for `cli_version`. |
| GitHub Copilot | [Supported models](https://docs.github.com/en/copilot/reference/ai-models/supported-models), [billing/model multipliers](https://docs.github.com/en/copilot/reference/copilot-billing/models-and-pricing), [Copilot Chat source](https://github.com/microsoft/vscode-copilot-chat), [VS Code releases](https://code.visualstudio.com/updates/), [Marketplace](https://marketplace.visualstudio.com/items?itemName=GitHub.copilot-chat) | `quota/github/GitHubQuotaClient.kt` for quotas; `quota/github/proxy/GitHubCopilotModelCatalog.kt` and `GitHubCopilotProxySupport.kt` for negotiated capabilities and client headers. Compare the stable VS Code build and Marketplace VSIX `extension/package.json` separately. |
| OpenCode Zen / Go | [Console changelog](https://opencode.ai/console/changelog), [Go plans and models](https://opencode.ai/docs/console/go/), [Console models](https://opencode.ai/docs/console/models/), [OpenCode source](https://github.com/anomalyco/opencode) | `quota/opencode/OpenCodeQuotaClient.kt` for `api/go/status` and `api/billing/status`; `quota/opencode/proxy/OpenCodeConsoleModels.kt` for discovery. Search `GO_PRODUCTS` in upstream `packages/data/core/src/domain/inference.ts` and gateway IDs in `packages/core/src/plugin/provider/opencode.ts`. Distinguish Go/Plus/Teams, Zen balance, workspace overrides, and stock CLI models. |
| Mistral | [Usage and limits](https://docs.mistral.ai/admin/billing-usage/usage-limits), [API changelog](https://docs.mistral.ai/resources/changelogs), [Mistral Vibe source and changelog](https://github.com/mistralai/mistral-vibe) | `quota/mistral/MistralQuotaClient.kt` and `quota/mistral/proxy/`. Compare Vibe, billing/subscription, and API-key responses separately; use official API `/models` capabilities for proxy routing. |
| Kimi Code | [Kimi Code docs](https://www.kimi.com/code/docs/en/), [current CLI source and releases](https://github.com/MoonshotAI/kimi-code/releases) | `quota/kimi/{KimiDeviceHeaders,KimiOAuthClient,KimiQuotaClient}.kt` and `quota/kimi/proxy/`. Search upstream `apps/kimi-code/src/cli/host-identity.ts`, `CLI_USER_AGENT_PRODUCT`, and `CLI_MSH_PLATFORM`; do not use the archived Python CLI as the source for current headers. Separate CN/global and coding-plan vs public API discovery. |
| MiniMax | [M Plan quickstart](https://platform.minimax.io/docs/m-plan/quickstart), [M Plan FAQ](https://platform.minimax.io/docs/m-plan/faq), [model releases](https://platform.minimax.io/docs/release-notes/models) | `quota/minimax/{MiniMaxQuotaClient,MiniMaxCodeAccountClient}.kt`; look up the exact model endpoint in the API reference for media failures. Separate M Plan/legacy Token Plan keys, Credits-only usage, pay-as-you-go keys, and CN/global. |
| Z.ai | [GLM Coding Plan](https://docs.z.ai/devpack/overview), [plan model availability](https://docs.z.ai/devpack/latest-model), [API releases](https://docs.z.ai/release-notes/new-released) | `quota/zai/ZaiQuotaClient.kt`, `quota/zai/proxy/ZaiSubscriptionProxyProvider.kt`. Compare coding and standard `/models` separately; check documented legacy aliases and unsupported reasoning knobs. |
| SuperGrok / Grok Build | [Grok Build docs](https://docs.x.ai/build/overview), [subscription usage limits](https://docs.x.ai/x-subscription/usage-limits), [API release notes](https://docs.x.ai/developers/release-notes) | `quota/supergrok/SuperGrokQuotaClient.kt` and `quota/supergrok/proxy/`. Compare Grok usage/reset details to the subscription route; public xAI availability and pricing are separate. |
| Cursor | [Cursor docs](https://cursor.com/docs), [CLI changelog](https://cursor.com/docs/cli/changelog), [dashboard](https://cursor.com/dashboard) | `quota/cursor/CursorQuotaClient.kt`; compare the user's dashboard and plugin raw response for personal, team, or pooled usage. CLI docs can explain a new meter without documenting the dashboard's wire format. |
| Azure | [ARM deployment list](https://learn.microsoft.com/en-us/rest/api/aiservices/accountmanagement/deployments/list?view=rest-aiservices-accountmanagement-2024-10-01), [regional usage list](https://learn.microsoft.com/en-us/rest/api/aiservices/accountmanagement/usages/list?view=rest-aiservices-accountmanagement-2024-10-01) | `quota/azure/AzureQuotaClient.kt` and `quota/azure/proxy/AzureSubscriptionProxyProvider.kt`. Match deployment → `properties.model.name`, SKU and region before inferring capabilities. Use only the documented Azure CLI account/token operations from `AGENTS.md`. |
| Antigravity | [CLI usage](https://antigravity.google/docs/cli/commands/usage/), [CLI credits](https://antigravity.google/docs/cli/commands/credits/), [CLI releases](https://github.com/google-antigravity/antigravity-cli/releases) | `quota/antigravity/AntigravityQuotaClient.kt` and its fixtures. Compare the documented `agy` JSON `/usage` and `/credits` reports; no internal APIs or credential extraction. |
| Ollama | [Balance API](https://docs.ollama.com/api/balance), [Ollama releases](https://github.com/ollama/ollama/releases) | `quota/ollama/OllamaQuotaClient.kt` and `quota/ollama/proxy/`. Check `/api/balance` for cloud quota; `/api/usage` is activity history. Distinguish cloud from local server models and limits. |
| OpenAI-compatible / LiteLLM | Start with the configured endpoint's docs and its own `/models`, `/model/info`, and, for LiteLLM quota, `/key/info`. Check tagged [LiteLLM source](https://github.com/BerriAI/litellm) for fields and routing. | `quota/compatible/proxy/`, `quota/litellm/LiteLlmClient.kt`, and `proxy/model/ReasoningCapabilities.kt`. Scope behavior to that endpoint and its account; do not extrapolate from another server or truncate `/key/info` to UI fields. |

## Cross-checks when first-party sources are incomplete

- **Quota, resets, and independent sign-in:** [OpenUsage](https://openusage.ai/)
  ([GitHub](https://github.com/robinebers/openusage)) →
  [provider implementations](https://github.com/robinebers/openusage/tree/main/Sources/OpenUsage/Providers)
  and [tests](https://github.com/robinebers/openusage/tree/main/Tests/OpenUsageTests).
  Check `<Provider>UsageClient.swift` for endpoints and `<Provider>UsageMapper.swift`
  for fields and units. Start with `Codex/CodexUsageClient.swift`,
  `Claude/ClaudeResetGrantStatus.swift`, or `Cursor/CursorUsageSummaryMapper.swift`
  for those providers. For login failures, its
  [provider guide](https://github.com/robinebers/openusage/blob/main/docs/adding-a-provider.md)
  points to the browser/device login catalog.
- **Quota and account behavior:** [CodexBar](https://github.com/steipete/CodexBar)
  → `Sources/CodexBarCore/Providers/<provider>/`, then tests for the same provider.
  Some names differ (`Codex`, `Copilot`, `Grok`, `AzureOpenAI`, `OpenCodeGo`).
  Search for the exact quota field, endpoint, or error, not the whole project.
  Do not adopt its credential import or local-session techniques.
- **Provider requests and adapters:** Search the exact field/model in
  [OpenCode's provider source](https://github.com/anomalyco/opencode/tree/dev/packages/core/src/plugin/provider)
  and adjacent tests. Codex/ChatGPT, Copilot, Ollama, xAI, and Console have
  separate integrations. Check the route and auth mode before porting behavior.
- **Version gates:** Use the vendor's release/tag or the npm `latest` metadata
  for `@openai/codex`, `@anthropic-ai/claude-code`, and `@moonshot-ai/kimi-code`.
  Use VS Code's stable release and the Marketplace VSIX for Copilot headers.
  A newer public artifact is not in itself evidence that a working integration
  needs a version bump.
- **Reasoning and model metadata:** Compare a provider's discovery first,
  then [OpenRouter's model list](https://openrouter.ai/api/v1/models),
  [models.dev](https://github.com/anomalyco/models.dev), and the tagged
  [LiteLLM](https://github.com/BerriAI/litellm) model map and
  `reasoning_effort_capability.py`. Use these only as corroboration or the
  existing fallbacks described in `AGENTS.md`, not as subscription catalogs.
