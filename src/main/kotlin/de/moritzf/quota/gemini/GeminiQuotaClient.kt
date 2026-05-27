package de.moritzf.quota.gemini

import de.moritzf.quota.gemini.dto.*
import de.moritzf.quota.shared.JsonSupport
import kotlinx.datetime.Clock
import kotlinx.serialization.encodeToString
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

class GeminiQuotaClient(
    private val httpClient: HttpClient = HttpClient.newBuilder()
        .followRedirects(HttpClient.Redirect.ALWAYS)
        .build()
) {
    fun fetchQuota(accessToken: String, projectId: String? = null): GeminiQuota {
        var rawResponse: String? = null
        val loadResp = try {
            val (resp, body) = loadCodeAssist(accessToken, projectId)
            rawResponse = body
            resp
        } catch (e: Exception) {
            if (projectId != null) null else throw e
        }
        
        val finalProjectId = projectId 
            ?: loadResp?.cloudaicompanionProject 
            ?: tryFindProject(accessToken)
            ?: throw GeminiQuotaException("Could not determine project ID. Please provide it in settings.")

        val (quotaResp, quotaBody) = retrieveUserQuota(accessToken, finalProjectId)
        
        val finalRawJson = if (rawResponse != null) {
            "{\"loadCodeAssist\": " + rawResponse + ", \"retrieveUserQuota\": " + quotaBody + "}"
        } else {
            quotaBody
        }

        val buckets = quotaResp.buckets.map {
            GeminiBucket(
                modelId = it.modelId ?: "unknown",
                tokenType = it.tokenType ?: "requests",
                remainingAmount = it.remainingAmount,
                remainingFraction = it.remainingFraction,
                resetTime = it.resetTime
            )
        }

        return GeminiQuota(
            buckets = buckets,
            planType = loadResp?.currentTier?.id ?: loadResp?.currentTier?.name,
            gcpManaged = loadResp?.gcpManaged,
            fetchedAt = Clock.System.now(),
            projectId = finalProjectId,
            rawJson = finalRawJson,
            paidTierName = loadResp?.paidTier?.name
        )
    }

    private fun loadCodeAssist(accessToken: String, projectId: String?): Pair<LoadCodeAssistResponseDto, String> {
        val requestBody = LoadCodeAssistRequestDto(
            cloudaicompanionProject = projectId,
            metadata = GeminiMetadataDto(project = projectId)
        )
        val body = post("https://cloudcode-pa.googleapis.com/v1internal:loadCodeAssist", accessToken, requestBody)
        return JsonSupport.json.decodeFromString<LoadCodeAssistResponseDto>(body) to body
    }

    private fun retrieveUserQuota(accessToken: String, projectId: String): Pair<RetrieveUserQuotaResponseDto, String> {
        val requestBody = RetrieveUserQuotaRequestDto(project = projectId)
        val body = post("https://cloudcode-pa.googleapis.com/v1internal:retrieveUserQuota", accessToken, requestBody)
        return JsonSupport.json.decodeFromString<RetrieveUserQuotaResponseDto>(body) to body
    }

    private fun tryFindProject(accessToken: String): String? {
        return try {
            val request = HttpRequest.newBuilder()
                .uri(URI.create("https://cloudresourcemanager.googleapis.com/v1/projects"))
                .timeout(Duration.ofSeconds(20))
                .header("Authorization", "Bearer " + accessToken)
                .header("Accept", "application/json")
                .GET()
                .build()
            val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
            if (response.statusCode() == 200) {
                val regex = """"projectId"\s*:\s*"([^"]+)"""".trim().toRegex()
                regex.find(response.body())?.groupValues?.get(1)
            } else null
        } catch (e: Exception) {
            null
        }
    }

    private fun post(url: String, accessToken: String, body: Any): String {
        val jsonBody = when (body) {
            is LoadCodeAssistRequestDto -> JsonSupport.json.encodeToString(body)
            is RetrieveUserQuotaRequestDto -> JsonSupport.json.encodeToString(body)
            else -> ""
        }

        val request = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .timeout(Duration.ofSeconds(30))
            .header("Authorization", "Bearer " + accessToken)
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
            .build()

        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() !in 200..299) {
            throw GeminiQuotaException("API request failed: " + response.statusCode() + " " + response.body(), response.statusCode(), response.body())
        }
        return response.body()
    }
}
