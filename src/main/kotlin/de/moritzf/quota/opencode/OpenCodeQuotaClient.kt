package de.moritzf.quota.opencode

import de.moritzf.quota.shared.JsonSupport
import kotlinx.datetime.Clock
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.Serializable
import java.io.IOException
import java.time.Duration
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.concurrent.atomic.AtomicReference
import java.util.logging.Logger

/**
 * HTTP client for fetching OpenCode Go subscription quota via SolidStart RPC.
 */
open class OpenCodeQuotaClient(
    private val httpClient: HttpClient = HttpClient.newHttpClient(),
    private val endpoint: URI = DEFAULT_ENDPOINT,
) {
    @Throws(IOException::class, InterruptedException::class)
    open fun fetchQuota(sessionCookie: String, workspaceId: String): OpenCodeQuota {
        require(sessionCookie.isNotBlank()) { "sessionCookie must not be null or blank" }
        require(workspaceId.isNotBlank()) { "workspaceId must not be null or blank" }

        val functionId = resolveFunctionId(sessionCookie, workspaceId)
        val argsJson = """["$workspaceId"]"""
        val encodedArgs = java.net.URLEncoder.encode(argsJson, Charsets.UTF_8)
        val uri = URI.create("${endpoint}?id=$functionId&args=$encodedArgs")

        LOG.info("Fetching OpenCode quota: $uri")

        val request = HttpRequest.newBuilder()
            .uri(uri)
            .timeout(Duration.ofSeconds(30))
            .header("Cookie", "auth=$sessionCookie")
            .header("Accept", "application/json")
            .header("X-Server-Id", functionId)
            .header("X-Server-Instance", "server-fn:1")
            .header("Referer", "https://opencode.ai/workspace/$workspaceId/go")
            .header("Origin", "https://opencode.ai")
            .GET()
            .build()

        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
        val status = response.statusCode()
        val body = response.body()

        LOG.info("OpenCode quota response: status=$status, body=${body.take(200)}")

        if (status !in 200..299) {
            throw OpenCodeQuotaException("OpenCode quota request failed: HTTP $status - ${body.take(500)}", status, body)
        }

        val quota = parseQuotaResponse(body)
        runCatching {
            fetchBillingInfo(sessionCookie, workspaceId)?.balance
        }.onFailure { exception ->
            LOG.warning("Could not fetch OpenCode billing balance: ${exception.message}")
        }.getOrNull()?.let { balance ->
            quota.availableBalance = balance
        }
        quota.fetchedAt = Clock.System.now()
        quota.rawJson = body
        return quota
    }

    /**
     * Fetches all workspaces for the user and enriches them with quota info
     * so the caller can pick the right one.
     */
    @Throws(IOException::class, InterruptedException::class)
    open fun fetchWorkspaces(sessionCookie: String): List<OpenCodeWorkspace> {
        require(sessionCookie.isNotBlank()) { "sessionCookie must not be null or blank" }

        val workspaces = fetchWorkspaceList(sessionCookie)
        if (workspaces.isEmpty()) {
            throw OpenCodeQuotaException(
                "No workspaces found. Please ensure you have access to opencode.ai.",
                200, null
            )
        }

        return workspaces.parallelStream().map { (id, name) ->
            val (mine, hasGo) = try {
                val quota = fetchQuota(sessionCookie, id)
                quota.mine to quota.hasUsageState()
            } catch (exception: OpenCodeQuotaException) {
                false to false
            } catch (exception: Exception) {
                false to false
            }
            OpenCodeWorkspace(id = id, name = name, mine = mine, hasGoSubscription = hasGo)
        }.toList()
    }

    /**
     * Discovers the workspace ID from the user's opencode.ai console.
     * Iterates all workspaces and returns the first one with an active Go subscription.
     */
    @Throws(IOException::class, InterruptedException::class)
    open fun discoverWorkspaceId(sessionCookie: String): String {
        require(sessionCookie.isNotBlank()) { "sessionCookie must not be null or blank" }

        val workspaces = fetchWorkspaceList(sessionCookie)
        if (workspaces.isEmpty()) {
            throw OpenCodeQuotaException(
                "No workspace found. Please ensure you have an active OpenCode Go subscription.",
                200, null
            )
        }

        for ((workspaceId, _) in workspaces) {
            try {
                val quota = fetchQuota(sessionCookie, workspaceId)
                if (quota.hasUsageState()) {
                    LOG.info("Found Go subscription in workspace: $workspaceId")
                    return workspaceId
                }
            } catch (exception: OpenCodeQuotaException) {
                when (exception.statusCode) {
                    0, 401, 403, 404 -> continue
                    else -> throw exception
                }
            }
        }

        throw OpenCodeQuotaException(
            "No workspace with an active OpenCode Go subscription found.",
            200, null
        )
    }

    private fun fetchWorkspaceList(sessionCookie: String): List<Pair<String, String>> {
        val uri = URI.create("https://opencode.ai/_server?id=$WORKSPACES_FUNCTION_ID&args=%5B%5D")
        LOG.info("Fetching workspace list: $uri")

        val request = HttpRequest.newBuilder()
            .uri(uri)
            .timeout(Duration.ofSeconds(15))
            .header("Cookie", "auth=$sessionCookie")
            .header("Accept", "application/json")
            .header("X-Server-Id", WORKSPACES_FUNCTION_ID)
            .header("X-Server-Instance", "server-fn:2")
            .GET()
            .build()

        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
        LOG.info("Workspace list response: status=${response.statusCode()}, body=${response.body().take(200)}")

        if (response.statusCode() == 401 || response.statusCode() == 403) {
            throw OpenCodeQuotaException(
                "Could not fetch workspace list: session cookie is invalid or expired (HTTP ${response.statusCode()}). " +
                    "Please update your session cookie in the plugin settings.",
                response.statusCode(), response.body()
            )
        }
        if (response.statusCode() !in 200..299) {
            throw OpenCodeQuotaException(
                "Could not fetch workspace list: HTTP ${response.statusCode()}. " +
                    "The opencode.ai API may have changed — please report this issue if it persists.",
                response.statusCode(), response.body()
            )
        }

        val workspaces = WORKSPACE_ID_PATTERN.findAll(response.body()).map {
            it.groupValues[1] to it.groupValues[2]
        }.toList()

        if (workspaces.isEmpty() && response.body().isNotBlank()) {
            LOG.warning(
                "Workspace list response was successful but no workspace IDs could be parsed. " +
                    "The opencode.ai page format may have changed. Body preview: ${response.body().take(300)}"
            )
        }

        return workspaces
    }

    /**
     * Resolves the server function ID, using a cached value if available.
     * Falls back to extracting it from the JS bundle.
     */
    private fun resolveFunctionId(sessionCookie: String, workspaceId: String): String {
        cachedFunctionId.get()?.takeIf { it.workspaceId == workspaceId }?.let { return it.functionId }

        val functionId = extractFunctionIdFromBundle(sessionCookie, workspaceId)
            ?: throw OpenCodeQuotaException(
                "Could not discover OpenCode server function ID. " +
                    "The opencode console may have been updated. Please report this issue.",
                0, null
            )
        cachedFunctionId.set(CachedFunctionId(workspaceId, functionId))
        return functionId
    }

    /**
     * Fetches the Go page JS bundle and extracts the queryLiteSubscription server function hash.
     */
    private fun extractFunctionIdFromBundle(sessionCookie: String, workspaceId: String): String? {
        val pageRequest = HttpRequest.newBuilder()
            .uri(URI.create("https://opencode.ai/workspace/$workspaceId/go"))
            .timeout(Duration.ofSeconds(15))
            .header("Cookie", "auth=$sessionCookie")
            .header("Accept", "text/html")
            .GET()
            .build()

        val pageResponse = httpClient.send(pageRequest, HttpResponse.BodyHandlers.ofString())
        if (pageResponse.statusCode() !in 200..299) return null

        val bundleMatches = Regex("""_build/assets/([^.]+)\.js""").findAll(pageResponse.body())
        for (match in bundleMatches) {
            val bundlePath = match.value
            val bundleRequest = HttpRequest.newBuilder()
                .uri(URI.create("https://opencode.ai/$bundlePath"))
                .timeout(Duration.ofSeconds(15))
                .header("Cookie", "auth=$sessionCookie")
                .GET()
                .build()

            val bundleResponse = httpClient.send(bundleRequest, HttpResponse.BodyHandlers.ofString())
            if (bundleResponse.statusCode() !in 200..299) continue

            val extracted = FUNCTION_ID_PATTERN.find(bundleResponse.body())?.groupValues?.get(1)
            if (extracted != null) return extracted
        }
        return null
    }

    /**
     * Fetches billing info so we can surface the available workspace balance next to the Go limits.
     */
    private fun fetchBillingInfo(sessionCookie: String, workspaceId: String): OpenCodeBillingInfo? {
        val argsJson = """["$workspaceId"]"""
        val encodedArgs = java.net.URLEncoder.encode(argsJson, Charsets.UTF_8)
        val uri = URI.create("${endpoint}?id=$BILLING_INFO_FUNCTION_ID&args=$encodedArgs")

        val request = HttpRequest.newBuilder()
            .uri(uri)
            .timeout(Duration.ofSeconds(15))
            .header("Cookie", "auth=$sessionCookie")
            .header("Accept", "application/json")
            .header("X-Server-Id", BILLING_INFO_FUNCTION_ID)
            .header("X-Server-Instance", "server-fn:1")
            .header("Referer", "https://opencode.ai/workspace/$workspaceId/billing")
            .header("Origin", "https://opencode.ai")
            .GET()
            .build()

        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
        val status = response.statusCode()
        val body = response.body()

        if (status == 401 || status == 403) {
            throw OpenCodeQuotaException(
                "OpenCode billing request failed: session cookie is invalid or expired (HTTP $status). " +
                    "Please update your session cookie in the plugin settings.",
                status, body
            )
        }
        if (status == 404) {
            // Billing endpoint hash may have rotated — log prominently but treat as non-fatal
            LOG.warning(
                "OpenCode billing endpoint returned 404. The server function ID ($BILLING_INFO_FUNCTION_ID) " +
                    "may be outdated. Balance will not be shown. Please report this issue."
            )
            return null
        }
        if (status !in 200..299) {
            throw OpenCodeQuotaException(
                "OpenCode billing request failed: HTTP $status. " +
                    "The opencode.ai API may have changed — please report this issue if it persists.",
                status, body
            )
        }

        return parseBillingInfoResponse(body)
    }

    companion object {
        @JvmField
        val DEFAULT_ENDPOINT: URI = URI.create("https://opencode.ai/_server")

        private val LOG = Logger.getLogger(OpenCodeQuotaClient::class.java.name)
        private val cachedFunctionId = AtomicReference<CachedFunctionId?>()

        private const val WORKSPACES_FUNCTION_ID = "def39973159c7f0483d8793a822b8dbb10d067e12c65455fcb4608459ba0234f"
        private const val BILLING_INFO_FUNCTION_ID = "c83b78a614689c38ebee981f9b39a8b377716db85c1fd7dbab604adc02d3313d"

        private val FUNCTION_ID_PATTERN = Regex(
            """queryLiteSubscription_query\s*=\s*createServerReference\("([a-f0-9]{64})"\)"""
        )

        private val WORKSPACE_ID_PATTERN = Regex(
            """id:"(wrk_[A-Za-z0-9]+)",name:"([^"]*)""""
        )

        /** Clear the cached function ID to force re-discovery. */
        fun clearCachedFunctionId() {
            cachedFunctionId.set(null)
        }

        fun parseQuotaResponse(body: String): OpenCodeQuota {
            val rootObject = parseRootObject(body)

            return try {
                JsonSupport.json.decodeFromJsonElement<OpenCodeQuota>(rootObject)
            } catch (exception: Exception) {
                throw OpenCodeQuotaException(
                    "Could not parse OpenCode quota response",
                    200,
                    body,
                    exception,
                )
            }
        }

        internal fun parseBillingInfoResponse(body: String): OpenCodeBillingInfo {
            val rootObject = parseRootObject(body)

            return try {
                JsonSupport.json.decodeFromJsonElement<OpenCodeBillingInfo>(rootObject)
            } catch (exception: Exception) {
                throw OpenCodeQuotaException(
                    "Could not parse OpenCode billing response",
                    200,
                    body,
                    exception,
                )
            }
        }

        private fun parseRootObject(body: String): JsonObject {
            val rootAssignmentIndex = body.indexOf(ROOT_ASSIGNMENT_MARKER)
            if (rootAssignmentIndex < 0) {
                throw OpenCodeQuotaException("Could not parse OpenCode response: unexpected format", 200, body)
            }

            val parser = SolidStartValueParser(body, rootAssignmentIndex + ROOT_ASSIGNMENT_MARKER.length)
            val rootElement = try {
                parser.parseValue()
            } catch (exception: OpenCodeQuotaException) {
                throw exception
            } catch (exception: Exception) {
                throw OpenCodeQuotaException(
                    "Could not parse OpenCode quota response",
                    200,
                    body,
                    exception,
                )
            }

            if (rootElement == JsonNull) {
                throw OpenCodeQuotaException("Could not parse OpenCode response: unexpected format", 200, body)
            }

            val rootObject = rootElement as? JsonObject
                ?: throw OpenCodeQuotaException("Could not parse OpenCode response: unexpected format", 200, body)
            return rootObject
        }

        private const val ROOT_ASSIGNMENT_MARKER = "\$R[0]="

        private data class CachedFunctionId(
            val workspaceId: String,
            val functionId: String,
        )

    }
}

@Serializable
internal data class OpenCodeBillingInfo(
    val balance: Long? = null,
)
