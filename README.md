# LLM Subscription Usage

<table align="center">
  <tr>
    <td align="center">
      <a href="https://plugins.jetbrains.com/plugin/30690-openai-usage-quota">
        <img src="src/main/resources/META-INF/pluginIcon.svg" alt="LLM Subscription Usage on JetBrains Marketplace" width="96" />
      </a>
      <br />
      <strong><a href="https://plugins.jetbrains.com/plugin/30690-openai-usage-quota">LLM Subscription Usage on JetBrains Marketplace</a></strong>
      <br />
      <br />
      <a href="https://plugins.jetbrains.com/plugin/30690-openai-usage-quota">
        <img src="https://img.shields.io/jetbrains/plugin/v/30690" alt="JetBrains Marketplace version" />
      </a>
      <a href="https://plugins.jetbrains.com/plugin/30690-openai-usage-quota">
        <img src="https://img.shields.io/jetbrains/plugin/d/30690" alt="JetBrains Marketplace downloads" />
      </a>
      <a href="https://plugins.jetbrains.com/plugin/30690-openai-usage-quota">
        <img src="https://img.shields.io/jetbrains/plugin/r/rating/30690" alt="JetBrains Marketplace rating" />
      </a>
    </td>
  </tr>
</table>

Track and use your LLM subscriptions directly in IntelliJ IDEA.

- **See your quota** for ChatGPT, Claude, Grok, Copilot & more in the status bar and a detail popup.
- **Use your subscriptions from IDE chat** via MCP tools: quota lookup, web search, image and video generation, voice, and document-to-markdown.
- **Reuse your subscriptions in other tools** through a local OpenAI-compatible proxy (for example as a custom LLM provider for JetBrains Junie).
- **Keep AI client configs in sync** with IntelliJ's MCP server URL, which changes port between restarts.

![Quota popup](docs/quota-popup.png)

## Supported providers

| Provider | Sign-in | Quota | Web search | Images | Video | Voice | Docs | Proxy |
|---|---|:-:|:-:|:-:|:-:|:-:|:-:|:-:|
| OpenAI (ChatGPT / Codex) | Browser login | ✓ | ✓ | ✓ | — | ✓ | (✓) | ✓ |
| Claude (Anthropic) | Browser login | ✓ | — | — | — | — | — | — |
| SuperGrok / xAI | Browser login | ✓ | ✓ | ✓ | ✓ | ✓ | (✓) | ✓ |
| GitHub Copilot | Device code | ✓ | — | — | — | — | — | ✓ |
| Cursor | Session cookie | ✓ | — | — | — | — | — | — |
| OpenCode (Go / Zen) | Browser login | ✓ | — | — | — | — | — | ✓ |
| Ollama Cloud | API key | ✓ | ✓ | — | — | — | — | ✓ |
| Z.ai | API key | ✓ | ✓ | ✓ | ✓ | (✓) | ✓ | ✓ |
| MiniMax | Subscription key | ✓ | ✓ | ✓ | — | ✓ | — | ✓ |
| Mistral | Session cookie + API key | ✓ | ✓ | ✓ | — | ✓ | ✓ | ✓ |
| Kimi | Device code | ✓ | ✓ | — | — | — | — | ✓ |
| Antigravity (Gemini / Claude / GPT) | AGY CLI login | ✓ | — | — | — | — | — | — |
| Azure OpenAI / Foundry | Azure CLI login | ✓ | — | — | — | — | ✓* | ✓ |

- **Quota** — usage in the status bar and detail popup.
- **Web search** — MCP tool that searches the web with your subscription. Copilot Chat can Bing-search in GitHub's own UI, but Copilot has no callable search API we can wrap.
- **Images** / **Video** — MCP tools that generate images or video with your subscription.
- **Voice** — MCP tools for speech-to-text and text-to-speech. (✓) means only one of the two.
- **Docs** — MCP tool that converts a PDF or image to markdown. ✓ uses a dedicated OCR API that returns figures. ✓* requires an explicitly selected Azure document model. (✓) uses a chat/vision API and reconstructs figure images locally from estimated page boxes. OpenAI and SuperGrok use the vision model selected in that provider's settings. A general vision model is less precise and usually costs more than Mistral or Z.ai OCR.
- **Proxy** — available through the local OpenAI-compatible proxy (for use of subscriptions in tools like Jetbrains AI Chat and other tools that require authentication by endpoint and API key).

Claude is quota-only. Anthropic does not allow using a Claude subscription outside their own apps, so this plugin only shows usage and does not wrap Claude search, media, documents, or a proxy.

## Installation

Open IntelliJ IDEA `Settings` > `Plugins` > `Marketplace`, search for **LLM Subscription Usage**, and click Install.

## Quick start

1. Open `Settings` > `Tools` > `LLM Subscription Usage`.
2. Sign in or add credentials for the providers you use.
3. Done — the status bar widget now shows your quota. Click it for the detail popup.

Everything else is optional and lives in the same settings page: MCP tools, MCP server URL sync, and the local proxy.

## Quota tracking

**Status bar widget** — a compact indicator shows your quota at a glance. Hover for a quick summary, click for the full popup. You can also place the indicator in the main toolbar instead.

![Status bar icon](docs/quota-statusbar-icon.png) ![Status bar with percentage](docs/quota-statusbar-percentage.png) ![Status bar with cake diagram](docs/quota-statusbar-cake.png)

**Detail popup** — one block per login, with usage windows, next reset times, and last refresh timestamps. Reorder accounts in settings (the list on the left); the popup follows that order.

Quotas refresh automatically every 5 minutes, plus on login and when opening the popup. Plugin-managed credentials — OAuth tokens, API keys, and session cookies — are stored in IntelliJ Password Safe.

In each provider's settings, use **Refresh quota** beside the copy button in **Last quota response** for an immediate update of the selected account.

**Antigravity is a CLI exception.** Install [Antigravity CLI (Google documentation)](https://antigravity.google/docs/cli/install/) 1.1.11 or later, run `agy` in a terminal to sign in, then add Antigravity in the plugin settings. The plugin runs `agy -p /usage --output-format json` to read quotas without an agent turn. It uses the current CLI login; credentials stay with AGY. Only one Antigravity entry is supported. Leave the executable path blank for automatic detection from `PATH` and standard install locations, use **Detect** to fill it, or browse to the binary. Sign-in/account changes happen in AGY; quotas refresh automatically. No credentials or conversation files are imported.

**Azure is a separate CLI exception.** Install the [Azure CLI](https://learn.microsoft.com/en-us/cli/azure/install-azure-cli), run `az login`, then add Azure in the plugin settings. Only one Azure entry is supported. It can pin a subscription from `az account list` and an Azure OpenAI resource name or endpoint. The plugin calls documented `az account get-access-token` and does not read `~/.azure`. Quota usage needs Cognitive Services Usages Reader and is optional: if that call is denied, the local proxy and any models or rate-limit numbers the token can read still work. Other providers continue to use plugin-managed credentials. For document-to-markdown, the first model list picks the newest Mistral OCR deployment when one exists, otherwise conversion stays off (`-`). You can still pick another discovered document model, Cohere Parse, or Document Intelligence, or choose `-` to turn it off. Changing the resource clears that choice so the next model list can pick again. The dropdown lists matching discovered deployments and manually entered names. Document Intelligence is not chosen automatically. OCR needs data-plane access to the selected resource; Document Intelligence requires Cognitive Services User access for the CLI identity. PDFs/images are sent inline to Azure, not to the providers' hosted APIs. Cohere Parse accepts images only, so PDF pages are rendered locally and processed one request per page for the whole document (or the requested `pageFrom`/`pageTo` slice). Cancellation takes effect between pages. Document Intelligence uses its asynchronous layout analysis API and can download detected figures when requested.

Azure proxy model IDs use `az-<deployment-name>`, including deployments missing from discovery. Deployment names come from the selected resource when ARM access is available; otherwise the plugin uses the endpoint's model list or manually entered deployment names. Capacity allocations use Azure's native capacity units (PTU for provisioned deployments); live rate-limit percentages come from recent proxy response headers.

Azure document conversion currently accepts PDF, PNG, and JPEG inputs up to 20 MiB. Document Intelligence can support larger files through its own API; the plugin's inline-input limit is lower.

## MCP tools for IDE chat

The plugin registers subscription-backed tools with IntelliJ's built-in MCP server. They are available to the IDE's AI chat — and to any external agent or AI harness that connects to IntelliJ's MCP server (Codex CLI, OpenCode, and others):

| Tool | What it does |
|---|---|
| `subscription_quota` | Current usage for any configured provider (optional `account` when you have more than one login of that type) |
| `subscription_tools_status` | Which accounts and tools are ready to use. Optional `capability`/`model` adds cached quota freshness, limiting pool, reset time, and whether that operation can still run |
| `codex_web_search` | Web search answered by OpenAI/Codex (context size, live access, domain filters) |
| `supergrok_web_search` | Web search answered by Grok (model selection, domain filters) |
| `mistral_web_search` | Answer-style web search via Mistral Conversations |
| `subscription_web_search` | Result-list web search via Kimi, Z.ai, MiniMax, or Ollama |
| `subscription_web_fetch` | Fetch a page as JSON (title/content/links) via Ollama or Z.ai |
| `subscription_document_to_markdown` | Convert a PDF/image to markdown; prefer Mistral OCR, then Azure or Z.ai OCR, before OpenAI/Codex or SuperGrok vision conversion. Azure supports Mistral OCR/Document AI, Cohere Parse, and Document Intelligence layout. Image-only Cohere Parse renders PDF pages locally; Document Intelligence retrieves detected figures; Codex/SuperGrok crop figures from vision-estimated boxes |
| `subscription_image_generation` | Image generation via OpenAI/Codex, SuperGrok/xAI Imagine, Mistral, Z.ai GLM-Image, or MiniMax. Returns a download URL or writes a file; never base64 |
| `subscription_image_edit` | SuperGrok/xAI JSON image edits from a source URL or local file. Masks are not supported |
| `subscription_speech_to_text` | Transcribe audio via OpenAI/Codex, SuperGrok/xAI, Mistral, Z.ai, or MiniMax |
| `subscription_text_to_speech` | Generate speech audio via OpenAI/Codex, SuperGrok/xAI, Mistral, or MiniMax and write it to a file |
| `subscription_list_voices` | List OpenAI/Codex, SuperGrok/xAI, Mistral, or MiniMax voices |
| `subscription_video_generation` | Video generation via SuperGrok/xAI Imagine or Z.ai CogVideoX |
| `supergrok_video_generation` | SuperGrok/xAI Imagine video (same as `subscription_video_generation` with SUPERGROK) |

Right-click a local PDF in the Project view or its editor and select **Convert PDF to Markdown...**. The dialog lists configured document providers, preferring Mistral, then Azure/Z.ai OCR, and prefills the sibling `.md` output with image extraction enabled. Provider, image extraction, and output location can all be changed before conversion; the generated Markdown opens in the editor.

Azure Mistral OCR/Document AI automatically splits PDFs into requests of at most 30 pages and joins the results in page order, both from this action and from MCP. The action shows completed pages and the current range; cancellation takes effect between requests. Azure's local input-size guard remains 20 MB. Z.ai OCR does the same for local PDFs, at most 30 pages and 50 MB per request; a URL is still one request. Cohere Parse walks every selected page as its own image request and joins the markdown. Mistral/Azure OCR images use a unique `<output>-images-…` folder and page-specific names, with matching relative Markdown links. Existing Markdown is replaced only after the whole conversion succeeds; failed or cancelled runs remove their partial output. Earlier image folders are retained so existing links are never overwritten. Without an output path, a multi-request MCP result contains `page_count` and `chunks` with `page_from`, `page_to`, and each original provider `response`.

### Figure quality for OCR documents

Mistral, Z.ai, and Azure document converters use the OCR response's figure coordinates to export from the original PDF. No extra vision-model request is needed. The PDF dialog and `subscription_document_to_markdown` expose:

| Option | Default | Meaning |
|---|---|---|
| `imageFormat` | `SVG` | Prefer SVG from the original PDF, with PNG fallback. `PNG` renders the original PDF; `PROVIDER` retains the provider's image bytes. |
| `imageDpi` | `300` | Resolution for local PNG export and SVG's PNG fallback. MCP accepts 72–600; the dialog offers 150, 300, and 600 DPI. This does not change the OCR service's input resolution. |
| `imagePaddingPoints` | `2` | Extra margin around the detected figure (0–72 PDF points; 72 points = 1 inch). |

SVG keeps vector graphics and text outlines sharp when zooming; existing raster content remains raster content. Linear and stitched RGB/gray gradients are supported. Unsupported paints, nonlinear gradients, complex transparency, or SVG errors fall back to PNG. Missing/invalid coordinates, mismatched page orientation, or an unavailable original PDF fall back to provider images. The result JSON includes `warnings`; the PDF action displays those warnings after conversion. PNG rendering is limited to 40 million page pixels to bound memory. Figures are stored in a unique `<output>-images-…` folder, with correct relative links in **one resulting Markdown document**.

Azure reuses the document bytes submitted to OCR. For Mistral/Z.ai URL inputs, local figure export downloads the original PDF on demand (public HTTPS, no redirects, maximum 50 MB); prefer a local PDF for a stable source. Image inputs use provider images and report that original-PDF export is unavailable. OpenAI/Codex and SuperGrok keep their existing vision-based figure export; the new options apply to OCR providers only.

Individual tools can be enabled or disabled under `Settings` > `Tools` > `MCP Server` > `Exposed Tools`.

![PDF to markdown pipelines](docs/document-to-markdown-pipelines.svg)

![MCP integration](docs/quota-mcp-integration.png)

## MCP server URL sync

IntelliJ's built-in MCP server may change its port between restarts, which breaks AI clients (OpenCode, Codex CLI, and others) that store the server URL in their own config files.

The plugin can keep those configs pointed at the active endpoint: enable `Sync IntelliJ MCP server URL to JSON/TOML/YAML files` in settings, pick one or more config files, and select the property to update. The settings page also shows whether IntelliJ's MCP server is running, stopped, disabled, or unavailable.

![MCP server sync settings](docs/mcp-sync-settings.png)

## OpenAI-compatible proxy

The plugin can run a local proxy that exposes your subscriptions through standard OpenAI-compatible endpoints (`/v1/chat/completions`, `/v1/responses` where supported, `/v1/completions` for FIM, `/v1/models`, plus LiteLLM-style `/v1/model/info`). Any tool that speaks the OpenAI API or expects a LiteLLM server can then use your subscriptions. Compatibility is tested with the Junie CLI and with JetBrains' own API Connections for AI features (such as the integrated AI chat).

**Setup:** open the **Proxy** tab in `Settings` > `Tools` > `LLM Subscription Usage`, tick `Enable local subscription proxy`, choose the providers to expose, and apply. Then use `Copy Base URL` and `Copy API Key` to configure your client.

**FIM / AI Completion:** when the proxy is on, `/v1/completions` is on too. Pick one completion model on the Proxy tab, then point JetBrains AI Completion at this proxy using the stable model id `qwen2.5-coder` (JetBrains Auto maps that name to `(fim) Qwen`). Ghost text stays off until that IDE setup is done. Changing the underlying model in this plugin does not require changing the IDE completion model. Most models do not support FIM — leave **Adapt model for FIM** on so the proxy converts FIM calls into chat requests, unless the selected model already infills via `/v1/completions` (some Ollama/Mistral coder models). Expect seconds of latency and quota use on chat models. Claude stays quota-only.

Point JetBrains AI Completion at:

- Settings → Tools → AI Assistant → Providers & API keys → AI Completion
- Provider: OpenAI Compatible
- Base URL: the copied proxy URL (no `/v1`)
- API key: the copied local key
- Model: `qwen2.5-coder`
- Prompt schema: Auto (recognized as `(fim) Qwen`). Do not pick Zeta/Sweep (next-edit) or Mellum.

Use **Test FIM** on the Proxy tab to send a sample completion and inspect the insert text. The same page tries to detect whether AI Completion already points at this proxy.

**Good to know:**

- Configure clients with the base URL **without** a `/v1` suffix (for example `http://127.0.0.1:14621`) — clients append `/v1/...` themselves, and all routes also answer unprefixed.
- For JetBrains Junie, add the proxy as a LiteLLM provider with that base URL and the copied API key; available models are discovered automatically. Do not pick `qwen2.5-coder` for Junie chat.
- Image, speech, and transcription OpenAI routes are on the same base URL (`/v1/images/generations`, `/v1/audio/speech`, `/v1/audio/transcriptions`). Use a prefixed model id (`sg-`, `mm-`, `za-`). Images return a URL, not base64.
- The API key is generated locally and stored in IntelliJ Password Safe. Provider credentials never leave the plugin's regular secure storage.
- `Log requests and responses to disk` writes full request/response bodies to a temp folder for debugging. Off by default; logs are pruned automatically (7 days / 2000 files).

![Proxy settings](docs/proxy-settings.png)

The proxy implementation was derived from the initial proxy design of [AIProxyOauth](https://github.com/skanga/AIProxyOauth), adapted to Kotlin and extended for this plugin's multi-provider subscription proxy, broader client compatibility, model discovery, request/response translation, and additional OpenAI-compatible routes.

## How it works

The plugin calls each provider's usage API with your credentials and displays the result in a normalized format. Nothing is sent anywhere except to the providers you configured.

Settings is an account list: add or remove logins, including more than one of the same type. Drag to reorder. The detail pane shows the raw `Last quota response` exactly as it arrived from the API — useful for transparency, debugging, and bug reports. Add, remove, default, and standby wait until Apply.

![Settings](docs/quota-settings.png)

## Troubleshooting

**"Port 1455 is already in use"** — another process is using the OpenAI login callback port. Stop it and retry the login.

**"Not logged in"** — open the plugin settings and start the sign-in flow for that provider again.

**Quota fetch errors or wrong numbers** — providers occasionally change their API responses. Check `Last quota response` in the provider's settings page to see what the API actually returned, and please [open an issue](https://github.com/moritzfl/llm-subscription-usage-intellij/issues/new/choose) with that response attached (redact anything you consider sensitive first). This is usually all that is needed to fix a parsing problem.
