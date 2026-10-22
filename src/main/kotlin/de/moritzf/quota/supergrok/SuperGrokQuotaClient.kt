package de.moritzf.quota.supergrok

import de.moritzf.quota.shared.JsonSupport
import de.moritzf.quota.shared.lenientDoubleOrNull
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.HttpTimeoutException
import java.time.Duration
import kotlin.time.Clock
import kotlin.time.Instant
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import org.slf4j.LoggerFactory

open class SuperGrokQuotaClient(
    private val httpClient: HttpClient = HttpClient.newHttpClient(),
    private val baseUri: URI = DEFAULT_BASE_URI,
    private val resetListUri: URI = DEFAULT_RESET_LIST_URI,
    private val resetRedeemUri: URI = DEFAULT_RESET_REDEEM_URI,
) {
    private val logger = LoggerFactory.getLogger(SuperGrokQuotaClient::class.java)

    open fun fetchQuota(accessToken: String?): SuperGrokQuota {
        val token =
            accessToken?.trim()?.takeIf { it.isNotBlank() }
                ?: throw SuperGrokQuotaException(
                    "Grok login required. Log in from SuperGrok settings."
                )

        val weeklyJson =
            getJson(token, WEEKLY_BILLING_PATH, required = true)
                ?: throw SuperGrokQuotaException("Grok billing response changed.")
        val settingsJson = getJson(token, SETTINGS_PATH, required = false)
        val autoTopUpJson = runCatching {
            getJson(token, AUTO_TOP_UP_PATH, required = false)
        }
            .getOrNull()
        val resetTokens = fetchResetTokens(token)

        val rawJson = combinedRawJson(weeklyJson, settingsJson, resetTokens, autoTopUpJson)

        val quota =
            try {
                parseQuota(weeklyJson, settingsJson)
                    .copy(resetTokens = resetTokens, autoTopUp = parseAutoTopUp(autoTopUpJson))
            } catch (exception: SuperGrokQuotaException) {
                throw SuperGrokQuotaException(
                    exception.message ?: "Grok billing response changed.",
                    200,
                    rawJson,
                    exception,
                )
            } catch (exception: Exception) {
                throw SuperGrokQuotaException(
                    "Grok billing response changed.",
                    200,
                    rawJson,
                    exception,
                )
            }
        quota.rawJson = rawJson
        return quota
    }

    open fun redeemReset(accessToken: String?, tokenId: String?): List<SuperGrokResetToken> {
        val token =
            accessToken?.trim()?.takeIf { it.isNotBlank() }
                ?: throw SuperGrokQuotaException(
                    "Grok login required. Log in from SuperGrok settings."
                )
        val resetId =
            tokenId?.trim()?.takeIf { it.isNotBlank() }
                ?: throw SuperGrokQuotaException("Grok reset token is missing.")
        return postReset(
            token,
            resetRedeemUri,
            SuperGrokResetCodec.redeemRequestFrame(resetId),
            required = true,
        )
    }

    private fun getJson(accessToken: String, path: String, required: Boolean): String? {
        val request =
            HttpRequest.newBuilder()
                .uri(baseUri.resolve(path))
                .timeout(Duration.ofSeconds(30))
                .header("Authorization", "Bearer $accessToken")
                .header("X-XAI-Token-Auth", TOKEN_AUTH_HEADER)
                .header("Accept", "application/json")
                .header("User-Agent", "LLM Subscription Usage")
                .GET()
                .build()

        for (attempt in 1..BILLING_REQUEST_ATTEMPTS) {
            val response = send(request)
            val status = response.statusCode()
            val body = response.body()
            if (status == 401 || status == 403) {
                throw SuperGrokQuotaException(
                    "Grok auth expired. Log in to SuperGrok again from settings.",
                    status,
                    body,
                )
            }
            if (status !in 200..299) {
                if (!required) return null
                if (isGrokBillingTimeout(status, body)) {
                    if (attempt < BILLING_REQUEST_ATTEMPTS) continue
                    throw SuperGrokQuotaException(GROK_BILLING_TIMEOUT_MESSAGE, status)
                }
                throw SuperGrokQuotaException(
                    "Grok billing request failed (HTTP $status). Try again later.",
                    status,
                    body,
                )
            }
            return body
        }
        throw SuperGrokQuotaException(GROK_BILLING_TIMEOUT_MESSAGE)
    }

    private fun fetchResetTokens(accessToken: String): List<SuperGrokResetToken> {
        return runCatching {
                postReset(
                    accessToken,
                    resetListUri,
                    SuperGrokResetCodec.emptyRequestFrame(),
                    required = false,
                )
            }
            .getOrDefault(emptyList())
    }

    private fun postReset(
        accessToken: String,
        uri: URI,
        body: ByteArray,
        required: Boolean,
    ): List<SuperGrokResetToken> {
        val request =
            HttpRequest.newBuilder()
                .uri(uri)
                .timeout(Duration.ofSeconds(30))
                .header("Authorization", "Bearer $accessToken")
                .header("Accept", "*/*")
                .header("Content-Type", GRPC_WEB_CONTENT_TYPE)
                .header("connect-protocol-version", "1")
                .header("Origin", "https://grok.com")
                .header("Referer", "https://grok.com/?_s=usage")
                .header("x-grpc-web", "1")
                .header("x-user-agent", "connect-es/2.1.1")
                .header("User-Agent", "LLM Subscription Usage")
                .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                .build()
        val response = sendBytes(request)
        val status = response.statusCode()
        val payload = response.body()
        logger.debug("SuperGrok reset response: status=$status, bodySize=${payload.size}")
        if (status == 401 || status == 403) {
            throw SuperGrokQuotaException(
                "Grok auth expired. Log in to SuperGrok again from settings.",
                status,
            )
        }
        if (status !in 200..299) {
            if (!required) return emptyList()
            logger.warn(
                "SuperGrok reset HTTP error: $status, body=${String(payload.copyOfRange(0, payload.size.coerceAtMost(200)))}"
            )
            throw SuperGrokQuotaException(
                "Grok reset request failed (HTTP $status). Try again later.",
                status,
            )
        }
        val headers = response.headers().map()
        val (grpcStatus, grpcMessage) = SuperGrokResetCodec.grpcStatus(payload, headers)
        if (grpcStatus != 0) {
            if (!required) return emptyList()
            logger.warn(
                "SuperGrok reset gRPC error: status=$grpcStatus, message=$grpcMessage, bodySize=${payload.size}"
            )
            val detail = grpcMessage?.takeIf { it.isNotBlank() } ?: "status $grpcStatus"
            throw SuperGrokQuotaException("Grok reset request failed ($detail).", status)
        }
        return SuperGrokResetCodec.parseResetTokens(payload, Clock.System.now())
    }

    private fun send(request: HttpRequest): HttpResponse<String> {
        return try {
            httpClient.send(request, HttpResponse.BodyHandlers.ofString())
        } catch (exception: HttpTimeoutException) {
            throw SuperGrokQuotaException(GROK_BILLING_TIMEOUT_MESSAGE, 0, null, exception)
        } catch (exception: IOException) {
            throw SuperGrokQuotaException(
                "Grok billing request failed. Check your connection.",
                0,
                null,
                exception,
            )
        } catch (exception: InterruptedException) {
            Thread.currentThread().interrupt()
            throw SuperGrokQuotaException(
                "Grok billing request failed. Check your connection.",
                0,
                null,
                exception,
            )
        }
    }

    private fun sendBytes(request: HttpRequest): HttpResponse<ByteArray> {
        return try {
            httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray())
        } catch (exception: HttpTimeoutException) {
            throw SuperGrokQuotaException(GROK_BILLING_TIMEOUT_MESSAGE, 0, null, exception)
        } catch (exception: IOException) {
            throw SuperGrokQuotaException(
                "Grok billing request failed. Check your connection.",
                0,
                null,
                exception,
            )
        } catch (exception: InterruptedException) {
            Thread.currentThread().interrupt()
            throw SuperGrokQuotaException(
                "Grok billing request failed. Check your connection.",
                0,
                null,
                exception,
            )
        }
    }

    companion object {
        @JvmField val DEFAULT_BASE_URI: URI = URI.create("https://cli-chat-proxy.grok.com/v1/")

        @JvmField
        val DEFAULT_RESET_LIST_URI: URI =
            URI.create("https://grok.com/prod_mc_billing.ConsumerUiSvc/GetRemainingResets")

        @JvmField
        val DEFAULT_RESET_REDEEM_URI: URI =
            URI.create("https://grok.com/prod_mc_billing.ConsumerUiSvc/RedeemReset")

        private const val WEEKLY_BILLING_PATH = "billing?format=credits"
        private const val SETTINGS_PATH = "settings"
        private const val AUTO_TOP_UP_PATH = "auto-topup-rule"
        private const val TOKEN_AUTH_HEADER = "xai-grok-cli"
        private const val GRPC_WEB_CONTENT_TYPE = "application/grpc-web+proto"
        private const val AUTH_SOURCE = "xai-oauth-cli-proxy"
        private const val BILLING_REQUEST_ATTEMPTS = 2
        private const val GROK_BILLING_TIMEOUT_MESSAGE =
            "Grok billing request timed out. The Grok billing API cancelled the request before returning usage data; try again later."

        fun parseQuota(
            weeklyBillingJson: String,
            settingsJson: String? = null,
            fetchedAt: Instant = Clock.System.now(),
        ): SuperGrokQuota {
            val config = billingConfig(weeklyBillingJson)
            val used = config.unitValue("used") ?: 0L
            val limit = config.unitValue("monthlyLimit") ?: 0L
            val usagePercent = resolveUsagePercent(config, used, limit)
            val period = config.objectValue("currentPeriod")
            // The credits period supersedes deprecated monthly billing dates.
            val periodStart =
                period?.stringValue("start") ?: config.stringValue("billingPeriodStart")
            val periodEnd = period?.stringValue("end") ?: config.stringValue("billingPeriodEnd")
            val resetsAt = periodEnd?.let { runCatching { Instant.parse(it) }.getOrNull() }
            val periodDurationMs = periodDurationMillis(periodStart, periodEnd)
            val plan = parsePlan(settingsJson)
            val isUnified = config.booleanValue("isUnifiedBillingUser") == true
            val periodType = period?.stringValue("type")
            val onDemandCap = config.unitValue("onDemandCap") ?: 0L
            val productUsage = productUsage(config, usagePercent)
            // Unused/new periods often omit creditUsagePercent entirely. With a known
            // period window, treat that as 0% used so the UI can show reset timing.
            val reported = usagePercent != null
            val effectivePercent =
                usagePercent ?: if (resetsAt != null || periodDurationMs != null) 0.0 else null

            return SuperGrokQuota(
                plan = plan.orEmpty(),
                authSource = AUTH_SOURCE,
                creditUsage =
                    effectivePercent?.let {
                        SuperGrokUsageWindow(
                            label = if (isUnified) "Weekly credits" else "Credits used",
                            used = used,
                            limit = limit,
                            usagePercent = it.coerceIn(0.0, 100.0),
                            resetsAt = resetsAt,
                            periodDurationMs = periodDurationMs,
                            reported = reported,
                        )
                    },
                onDemandCap = onDemandCap,
                onDemandUsed = config.cents("onDemandUsed")?.takeIf { onDemandCap > 0 },
                prepaidBalance = config.cents("prepaidBalance"),
                productUsage = productUsage,
                isUnifiedBilling = isUnified,
                periodType = periodType.orEmpty(),
                fetchedAt = fetchedAt,
            )
        }

        fun combinedRawJson(
            weeklyBillingJson: String,
            settingsJson: String?,
            resetTokens: List<SuperGrokResetToken> = emptyList(),
            autoTopUpJson: String? = null,
        ): String {
            return buildJsonObject {
                put("authSource", AUTH_SOURCE)
                put("billing", rawElement(weeklyBillingJson))
                if (!settingsJson.isNullOrBlank()) {
                    put("settings", rawElement(settingsJson))
                }
                if (!autoTopUpJson.isNullOrBlank()) {
                    put("autoTopup", rawElement(autoTopUpJson))
                }
                if (resetTokens.isNotEmpty()) {
                    put(
                        "resets",
                        JsonArray(
                            resetTokens.map { token ->
                                buildJsonObject {
                                    put("tokenId", token.tokenId)
                                    token.expiresAt?.let { put("expiresAt", it.toString()) }
                                }
                            }
                        ),
                    )
                }
            }
                .toString()
        }

        private fun parsePlan(settingsJson: String?): String? {
            if (settingsJson.isNullOrBlank()) return null
            return (runCatching { JsonSupport.json.parseToJsonElement(settingsJson) }.getOrNull()
                    as? JsonObject)
                ?.stringValue("subscription_tier_display")
                ?.trim()
                ?.takeIf { it.isNotBlank() }
        }

        /**
         * Product shares add up to the total; their sum stands in when the total is missing. The
         * largest share alone under-reports.
         */
        private fun resolveUsagePercent(config: JsonObject, used: Long, limit: Long): Double? {
            config.doubleValue("creditUsagePercent")?.let {
                return it
            }
            productShares(config)
                .takeIf { it.isNotEmpty() }
                ?.sumOf { it.usagePercent }
                ?.let {
                    return it
                }
            if (limit > 0) {
                return used.toDouble() / limit.toDouble() * 100.0
            }
            return null
        }

        private fun productShares(config: JsonObject): List<SuperGrokProductUsage> =
            (config["productUsage"] as? JsonArray).orEmpty().mapNotNull { element ->
                val item = element as? JsonObject ?: return@mapNotNull null
                val product = item.stringValue("product")?.takeIf { it.isNotBlank() }
                val percent = item.doubleValue("usagePercent")?.takeIf { it.isFinite() } ?: 0.0
                product?.let { SuperGrokProductUsage(it, percent) }
            }

        /** The breakdown, kept only when its shares add up to the total within a point. */
        private fun productUsage(config: JsonObject, total: Double?): List<SuperGrokProductUsage> {
            val shares = productShares(config)
            if (shares.isEmpty() || total == null) return emptyList()
            return shares
                .takeIf { kotlin.math.abs(it.sumOf { share -> share.usagePercent } - total) <= 1.0 }
                .orEmpty()
        }

        /**
         * Billing stores credit amounts in cents, top-ups as negative values; an empty object is a
         * zero amount.
         */
        private fun JsonObject.cents(name: String): Long? {
            val value = this[name] ?: return null
            if (value is JsonObject && value["val"] == null) return 0
            return unitValue(name)?.let(Math::abs)
        }

        /** An enabled rule, or null when the rule is off, missing, or unreadable. */
        internal fun parseAutoTopUp(json: String?): SuperGrokAutoTopUp? {
            val root =
                runCatching { JsonSupport.json.parseToJsonElement(json.orEmpty()) }.getOrNull()
                    as? JsonObject ?: return null
            val rule = root.objectValue("rule") ?: return null
            if (rule.booleanValue("enabled") != true) return null
            return SuperGrokAutoTopUp(
                amountCents = rule.cents("topupAmount") ?: return null,
                thresholdCents = rule.cents("minBeforeHittingSl"),
                monthlyMaxCents = rule.cents("maxAmountPerMonth"),
            )
        }

        private fun billingConfig(json: String): JsonObject {
            val root =
                runCatching { JsonSupport.json.parseToJsonElement(json) as? JsonObject }
                    .getOrElse {
                        throw SuperGrokQuotaException(
                            "Grok billing response changed.",
                            200,
                            json,
                            it,
                        )
                    } ?: throw SuperGrokQuotaException("Grok billing response changed.", 200, json)
            return root.objectValue("config")
                ?: root.objectValue("billing")?.objectValue("config")
                ?: throw SuperGrokQuotaException("Grok billing response changed.", 200, json)
        }

        private fun JsonObject.objectValue(name: String): JsonObject? = this[name] as? JsonObject

        private fun JsonObject.stringValue(name: String): String? =
            (this[name] as? JsonPrimitive)?.contentOrNull

        private fun JsonObject.booleanValue(name: String): Boolean? =
            (this[name] as? JsonPrimitive)?.booleanOrNull

        private fun JsonObject.doubleValue(name: String): Double? =
            this[name]?.lenientDoubleOrNull()

        private fun JsonObject.unitValue(name: String): Long? {
            val value = this[name]
            return (value as? JsonPrimitive)?.longOrNull
                ?: ((value as? JsonObject)?.get("val") as? JsonPrimitive)?.longOrNull
        }

        private fun periodDurationMillis(start: String?, end: String?): Long? {
            val startInstant =
                start?.let { runCatching { Instant.parse(it) }.getOrNull() } ?: return null
            val endInstant =
                end?.let { runCatching { Instant.parse(it) }.getOrNull() } ?: return null
            val duration = endInstant.toEpochMilliseconds() - startInstant.toEpochMilliseconds()
            return duration.takeIf { it > 0 }
        }

        private fun rawElement(value: String): JsonElement {
            return runCatching { JsonSupport.json.parseToJsonElement(value) }
                .getOrElse { JsonPrimitive(value) }
        }

        private fun isGrokBillingTimeout(status: Int, body: String): Boolean {
            if (status != 400) return false
            val payload =
                runCatching { JsonSupport.json.parseToJsonElement(body) as? JsonObject }.getOrNull()
                    ?: return false
            val code = (payload["code"] as? JsonPrimitive)?.contentOrNull
            val error = (payload["error"] as? JsonPrimitive)?.contentOrNull
            return code.equals("The operation was cancelled", ignoreCase = true) &&
                error.equals("Timeout expired", ignoreCase = true)
        }
    }
}
