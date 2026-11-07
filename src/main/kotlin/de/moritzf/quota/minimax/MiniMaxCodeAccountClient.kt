package de.moritzf.quota.minimax

import de.moritzf.quota.shared.JsonSupport
import java.io.IOException
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.MessageDigest
import java.time.Duration
import java.time.ZoneId
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull

/** What a MiniMax Code login reports: plan membership, Credits, and the plan's usage windows. */
class MiniMaxCodeAccount(
    /** The complete `get_membership_info` response. */
    val membershipJson: String,
    val hasTokenPlan: Boolean?,
    val credits: MiniMaxCredits?,
    /** The plan's `coding_plan/remains` response, when it was read. */
    val tokenPlanJson: String? = null,
    val tokenPlanError: String? = null,
)

/**
 * Reads the account of a MiniMax Code login the way MiniMax Code does
 * (`packages/tui/src/account/matrix-account-client.ts`): account identity, the personal workspace's
 * membership with its Credits, and the plan's usage windows. Only reads; never claims anything.
 */
open class MiniMaxCodeAccountClient(
    private val httpClient: HttpClient = HttpClient.newHttpClient(),
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val timezoneOffsetSeconds: () -> Int = {
        ZoneId.systemDefault().rules.getOffset(java.time.Instant.now()).totalSeconds
    },
    private val osName: String = platformName(System.getProperty("os.name").orEmpty()),
    private val hosts: (MiniMaxRegion) -> MiniMaxCodeHosts = MiniMaxCodeHosts::forRegion,
) {
    /** The account's user ID, which the account API expects with every request. */
    open fun fetchUserId(accessToken: String, region: MiniMaxRegion): String {
        val body = signedRequest(region, USER_INFO_PATH, accessToken, "0", payload = null)
        val data = body["data"] as? JsonObject
        val userInfo =
            (data?.get("userInfo") ?: data?.get("user_info") ?: body["userInfo"]) as? JsonObject
        return userInfo?.text("realUserID")
            ?: userInfo?.text("real_user_id")
            ?: throw MiniMaxQuotaException("MiniMax Code returned no account ID.", 200)
    }

    open fun fetchAccount(
        accessToken: String,
        region: MiniMaxRegion,
        userId: String,
        includeTokenPlan: Boolean,
    ): MiniMaxCodeAccount {
        val workspace =
            try {
                personalWorkspace(
                    signedRequest(region, USER_EXTRA_INFO_PATH, accessToken, userId, EMPTY),
                )
            } catch (exception: MiniMaxQuotaException) {
                if (exception.statusCode == 401) throw exception
                null
            }
        val payload =
            workspace?.get("workspace_id")?.let { buildJsonObject { put("workspace_id", it) } }
                ?: EMPTY
        val membership = signedRequest(region, MEMBERSHIP_PATH, accessToken, userId, payload)
        val hasTokenPlan = membership.bool("has_token_plan") ?: workspace?.bool("has_token_plan")
        val opGroupId = membership.text("op_group_id") ?: workspace?.text("op_group_id")
        var tokenPlanJson: String? = null
        var tokenPlanError: String? = null
        if (includeTokenPlan && hasTokenPlan != false && opGroupId != null) {
            try {
                tokenPlanJson = tokenPlanRemains(region, accessToken, opGroupId)
            } catch (exception: MiniMaxQuotaException) {
                if (exception.statusCode == 401) throw exception
                tokenPlanError = exception.message
            }
        }
        return MiniMaxCodeAccount(
            membershipJson = compactJson.encodeToString(JsonObject.serializer(), membership),
            hasTokenPlan = hasTokenPlan,
            credits = credits(membership),
            tokenPlanJson = tokenPlanJson,
            tokenPlanError = tokenPlanError,
        )
    }

    private fun tokenPlanRemains(
        region: MiniMaxRegion,
        accessToken: String,
        opGroupId: String,
    ): String {
        val request =
            HttpRequest.newBuilder(hosts(region).platform.resolve(TOKEN_PLAN_REMAINS_PATH))
                .timeout(TIMEOUT)
                .header("Accept", "application/json")
                .header("Authorization", "Bearer $accessToken")
                .header("X-Group-Id", opGroupId)
                .GET()
                .build()
        val response = send(request)
        if (response.statusCode() == 401 || response.statusCode() == 403) {
            throw MiniMaxQuotaException(LOGIN_EXPIRED, 401)
        }
        if (response.statusCode() !in 200..299) {
            throw MiniMaxQuotaException(
                "MiniMax Code usage request failed (HTTP ${response.statusCode()}).",
                response.statusCode(),
                response.body(),
            )
        }
        return response.body()
    }

    private fun signedRequest(
        region: MiniMaxRegion,
        path: String,
        accessToken: String,
        userId: String,
        payload: JsonObject?,
    ): JsonObject {
        val requestTime = nowMs()
        val query = query(region, requestTime, userId)
        val body = payload?.let { compactJson.encodeToString(JsonObject.serializer(), it) }
        val builder =
            HttpRequest.newBuilder(hosts(region).agent.resolve("$path?$query"))
                .timeout(TIMEOUT)
                .header("Accept", "application/json")
                .header("Content-Type", "application/json")
                .header("User-Agent", USER_AGENT)
                .header("Authorization", "Bearer $accessToken")
        attributionHeaders("$path?$query", body, requestTime).forEach { (name, value) ->
            builder.header(name, value)
        }
        val request =
            if (body == null) builder.GET().build()
            else builder.POST(HttpRequest.BodyPublishers.ofString(body)).build()
        val response = send(request)
        if (response.statusCode() == 401 || response.statusCode() == 403) {
            throw MiniMaxQuotaException(LOGIN_EXPIRED, 401)
        }
        // Identity responses carry personal details; keep them out of errors.
        val errorBody = response.body().takeUnless { path == USER_INFO_PATH }
        if (response.statusCode() !in 200..299) {
            throw MiniMaxQuotaException(
                "MiniMax Code account request failed (HTTP ${response.statusCode()}).",
                response.statusCode(),
                errorBody,
            )
        }
        val json =
            runCatching { JsonSupport.json.parseToJsonElement(response.body()) as? JsonObject }
                .getOrNull()
                ?: throw MiniMaxQuotaException(
                    "MiniMax Code returned an unreadable account response.",
                    200,
                    errorBody,
                )
        val statusInfo = (json["statusInfo"] as? JsonObject)?.get("code") as? JsonPrimitive
        val statusCode = statusInfo?.intOrNull
        if (statusCode != null && statusCode != 0) {
            if (statusCode == NEED_LOGIN) throw MiniMaxQuotaException(LOGIN_EXPIRED, 401)
            throw MiniMaxQuotaException(
                "MiniMax Code account request failed (status $statusCode).",
                200,
                errorBody,
            )
        }
        val baseStatus = ((json["base_resp"] as? JsonObject)?.get("status_code") as? JsonPrimitive)
        if (baseStatus?.intOrNull?.let { it != 0 } == true) {
            throw MiniMaxQuotaException(
                "MiniMax Code account request failed (status ${baseStatus.content}).",
                200,
                errorBody,
            )
        }
        return json
    }

    private fun send(request: HttpRequest): HttpResponse<String> =
        try {
            httpClient.send(request, HttpResponse.BodyHandlers.ofString())
        } catch (exception: InterruptedException) {
            Thread.currentThread().interrupt()
            throw MiniMaxQuotaException(
                "Request failed. Check your connection.",
                0,
                null,
                exception,
            )
        } catch (exception: IOException) {
            throw MiniMaxQuotaException(
                "Request failed. Check your connection.",
                0,
                null,
                exception,
            )
        }

    /** MiniMax Code's query parameters, in its order; the signature covers them. */
    private fun query(region: MiniMaxRegion, requestTime: Long, userId: String): String {
        val language = if (region == MiniMaxRegion.CN) "zh" else "en"
        return listOf(
                "device_platform" to "mcode",
                "biz_id" to "3",
                "app_id" to "3001",
                "version_code" to "22201",
                "unix" to requestTime.toString(),
                "timezone_offset" to timezoneOffsetSeconds().toString(),
                "sys_language" to language,
                "lang" to language,
                "device_id" to "0",
                "os_name" to osName,
                "browser_name" to "mcode",
                "user_id" to userId.ifBlank { "0" },
                "client" to "mcode",
            )
            .joinToString("&") { (name, value) ->
                "${URLEncoder.encode(name, Charsets.UTF_8)}=${URLEncoder.encode(value, Charsets.UTF_8)}"
            }
    }

    companion object {
        private const val USER_INFO_PATH = "/v1/api/user/info"
        private const val USER_EXTRA_INFO_PATH = "/matrix/api/v1/user/get_user_extra_info"
        private const val MEMBERSHIP_PATH = "/matrix/api/v1/commerce/get_membership_info"
        private const val TOKEN_PLAN_REMAINS_PATH = "/v1/api/openplatform/coding_plan/remains"
        private const val USER_AGENT = "MiniMaxCode"
        private const val NEED_LOGIN = 1_000_048
        private const val LOGIN_EXPIRED = "MiniMax Code login expired. Sign in again in settings."
        private val TIMEOUT: Duration = Duration.ofSeconds(30)
        private val EMPTY = JsonObject(emptyMap())
        private val compactJson = Json { ignoreUnknownKeys = true }

        /**
         * MiniMax Code's client attribution headers. Its source documents the two literals as
         * shared wire-protocol constants that tag first-party client requests, not credentials; the
         * bearer token authorizes the request.
         */
        internal fun attributionHeaders(
            pathWithQuery: String,
            body: String?,
            requestTime: Long,
        ): Map<String, String> {
            val second = requestTime / 1000
            return mapOf(
                "yy" to
                    md5(
                        "${encodeUriComponent(pathWithQuery)}_${body ?: "{}"}${md5(requestTime.toString())}ooui",
                    ),
                "x-timestamp" to second.toString(),
                "x-signature" to md5("${second}I*7Cf%WZ#S&%1RlZJ&C2${body.orEmpty()}"),
            )
        }

        /** JavaScript's `encodeURIComponent`, which the attribution hash is computed over. */
        internal fun encodeUriComponent(value: String): String = buildString {
            for (byte in value.toByteArray(Charsets.UTF_8)) {
                val code = byte.toInt() and 0xff
                val char = code.toChar()
                if (
                    char in 'A'..'Z' || char in 'a'..'z' || char in '0'..'9' || char in "-_.!~*'()"
                ) {
                    append(char)
                } else {
                    append('%').append("%02X".format(code))
                }
            }
        }

        /** Node's `process.platform` names, which MiniMax Code reports as `os_name`. */
        internal fun platformName(osName: String): String {
            val name = osName.lowercase()
            return when {
                name.contains("mac") || name.contains("darwin") -> "darwin"
                name.contains("win") -> "win32"
                else -> "linux"
            }
        }

        /** Credits from `op_credit_summary`; amounts are decimal strings such as "3944.267". */
        internal fun credits(membership: JsonObject): MiniMaxCredits? {
            val summary =
                (membership["op_credit_summary"]
                    ?: (membership["data"] as? JsonObject)?.get("op_credit_summary"))
                    as? JsonObject
            val remaining =
                summary?.amount("total_remaining_amount")
                    ?: membership.amount("opcredit_balance")
                    ?: return null
            return MiniMaxCredits(
                remaining = remaining,
                purchased = summary?.amount("purchased_remaining_amount"),
                free = summary?.amount("free_remaining_amount"),
            )
        }

        private fun personalWorkspace(extra: JsonObject): JsonObject? {
            val workspaces =
                (extra["workspaces"] ?: (extra["data"] as? JsonObject)?.get("workspaces"))
                    as? JsonArray ?: return null
            return workspaces.filterIsInstance<JsonObject>().firstOrNull {
                (it["workspace_type"] as? JsonPrimitive)?.intOrNull == 0 &&
                    it["workspace_id"] is JsonPrimitive
            }
        }

        private fun JsonObject.text(key: String): String? =
            (this[key] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }

        private fun JsonObject.bool(key: String): Boolean? =
            (this[key] as? JsonPrimitive)?.booleanOrNull

        private fun JsonObject.amount(key: String): Double? {
            val value = this[key] as? JsonPrimitive ?: return null
            return (value.doubleOrNull ?: value.contentOrNull?.trim()?.toDoubleOrNull())?.takeIf {
                it.isFinite()
            }
        }

        private fun md5(value: String): String =
            MessageDigest.getInstance("MD5").digest(value.toByteArray(Charsets.UTF_8)).joinToString(
                "",
            ) {
                "%02x".format(it)
            }
    }
}
