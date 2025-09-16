package com.roadwatch.network

import android.util.Log
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

object ApiClient {
    private const val TAG = "ApiClient"

    data class HttpResult(val code: Int, val body: String?)

    private fun request(method: String, url: String, token: String? = null, jsonBody: JSONObject? = null, timeoutMs: Int = 10000): HttpResult {
        val conn = (URL(url).openConnection() as HttpURLConnection)
        conn.requestMethod = method
        conn.connectTimeout = timeoutMs
        conn.readTimeout = timeoutMs
        conn.setRequestProperty("Accept", "application/json")
        if (token != null) conn.setRequestProperty("Authorization", "Bearer $token")
        if (jsonBody != null) {
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.outputStream.use { os -> os.write(jsonBody.toString().toByteArray()) }
        }
        val code = try { conn.responseCode } catch (e: Exception) { Log.e(TAG, "HTTP error", e); -1 }
        val stream = try { conn.inputStream } catch (_: Exception) { conn.errorStream }
        val body = stream?.use { BufferedReader(InputStreamReader(it)).readText() }
        conn.disconnect()
        return HttpResult(code, body)
    }

    // Admin auth
    fun adminLogin(baseUrl: String, email: String, password: String): Result<Triple<String, String, Int>> {
        return try {
            val url = baseUrl.trimEnd('/') + "/v1/admin/login"
            val body = org.json.JSONObject().put("email", email).put("password", password)
            val res = request("POST", url, jsonBody = body)
            if (res.code == 200 && res.body != null) {
                val j = org.json.JSONObject(res.body)
                val access = j.getString("access_token")
                val refresh = j.getString("refresh_token")
                val expires = j.getInt("expires_in")
                Result.success(Triple(access, refresh, expires))
            } else Result.failure(IllegalStateException("HTTP ${res.code}: ${res.body}"))
        } catch (t: Throwable) { Result.failure(t) }
    }

    fun adminRefresh(baseUrl: String, refreshToken: String): Result<Pair<String, String>> {
        return try {
            val url = baseUrl.trimEnd('/') + "/v1/admin/refresh"
            val body = org.json.JSONObject().put("refresh_token", refreshToken)
            val res = request("POST", url, jsonBody = body)
            if (res.code == 200 && res.body != null) {
                val j = org.json.JSONObject(res.body)
                Result.success(j.getString("access_token") to j.getString("refresh_token"))
            } else Result.failure(IllegalStateException("HTTP ${res.code}: ${res.body}"))
        } catch (t: Throwable) { Result.failure(t) }
    }

    fun adminListHazards(
        baseUrl: String,
        accessToken: String,
        limit: Int = 200,
        cursor: String? = null,
        type: String? = null,
        source: String? = null,
        active: Boolean? = null,
        search: String? = null,
        minLat: Double? = null,
        minLng: Double? = null,
        maxLat: Double? = null,
        maxLng: Double? = null,
        createdAfter: String? = null,
        createdBefore: String? = null
    ): Result<com.roadwatch.network.HazardsListResponseDto> {
        return try {
            val sb = StringBuilder()
            sb.append(baseUrl.trimEnd('/')).append("/v1/admin/hazards?limit=").append(limit)
            if (!cursor.isNullOrEmpty()) sb.append("&cursor=").append(java.net.URLEncoder.encode(cursor, "UTF-8"))
            if (!type.isNullOrEmpty()) sb.append("&type=").append(java.net.URLEncoder.encode(type, "UTF-8"))
            if (!source.isNullOrEmpty()) sb.append("&source=").append(java.net.URLEncoder.encode(source, "UTF-8"))
            if (active != null) sb.append("&active=").append(active)
            if (!search.isNullOrEmpty()) sb.append("&search=").append(java.net.URLEncoder.encode(search, "UTF-8"))
            if (minLat != null) sb.append("&min_lat=").append(minLat)
            if (minLng != null) sb.append("&min_lng=").append(minLng)
            if (maxLat != null) sb.append("&max_lat=").append(maxLat)
            if (maxLng != null) sb.append("&max_lng=").append(maxLng)
            if (!createdAfter.isNullOrEmpty()) sb.append("&created_after=").append(java.net.URLEncoder.encode(createdAfter, "UTF-8"))
            if (!createdBefore.isNullOrEmpty()) sb.append("&created_before=").append(java.net.URLEncoder.encode(createdBefore, "UTF-8"))
            val res = request("GET", sb.toString(), token = accessToken)
            if (res.code == 200 && res.body != null) {
                val j = org.json.JSONObject(res.body)
                val arr = j.getJSONArray("hazards")
                val list = mutableListOf<com.roadwatch.network.HazardDto>()
                for (i in 0 until arr.length()) list += arr.getJSONObject(i).toHazardDto()
                val cur = if (j.isNull("cursor")) null else j.getString("cursor")
                Result.success(com.roadwatch.network.HazardsListResponseDto(list, cur))
            } else Result.failure(IllegalStateException("HTTP ${res.code}: ${res.body}"))
        } catch (t: Throwable) { Result.failure(t) }
    }

    fun adminPatchHazard(baseUrl: String, accessToken: String, id: String, patch: org.json.JSONObject): Result<com.roadwatch.network.HazardDto> {
        return try {
            val url = baseUrl.trimEnd('/') + "/v1/admin/hazards/${id}"
            val res = request("PATCH", url, token = accessToken, jsonBody = patch)
            if (res.code == 200 && res.body != null) Result.success(org.json.JSONObject(res.body).toHazardDto())
            else Result.failure(IllegalStateException("HTTP ${res.code}: ${res.body}"))
        } catch (t: Throwable) { Result.failure(t) }
    }

    fun adminDeleteHazard(baseUrl: String, accessToken: String, id: String): Result<Unit> {
        return try {
            val url = baseUrl.trimEnd('/') + "/v1/admin/hazards/${id}"
            val res = request("DELETE", url, token = accessToken)
            if (res.code in 200..299) Result.success(Unit) else Result.failure(IllegalStateException("HTTP ${res.code}: ${res.body}"))
        } catch (t: Throwable) { Result.failure(t) }
    }

    fun registerAccount(baseUrl: String, email: String, password: String): Result<Unit> {
        return try {
            val url = baseUrl.trimEnd('/') + "/v1/account/register"
            val payload = org.json.JSONObject().put("email", email).put("password", password)
            val res = request("POST", url, jsonBody = payload)
            if (res.code == 201) Result.success(Unit) else Result.failure(IllegalStateException("HTTP ${res.code}: ${res.body}"))
        } catch (t: Throwable) { Result.failure(t) }
    }

    private fun basic(email: String, password: String): String =
        "Basic " + java.util.Base64.getEncoder().encodeToString("$email:$password".toByteArray())

    fun getAccountSettings(baseUrl: String, email: String, password: String): Result<org.json.JSONObject> {
        return try {
            val url = baseUrl.trimEnd('/') + "/v1/account/settings"
            val res = request("GET", url, token = basic(email, password))
            if (res.code == 200 && res.body != null) {
                val j = org.json.JSONObject(res.body)
                Result.success(j.getJSONObject("settings"))
            } else Result.failure(IllegalStateException("HTTP ${res.code}: ${res.body}"))
        } catch (t: Throwable) { Result.failure(t) }
    }

    fun putAccountSettings(baseUrl: String, email: String, password: String, settings: org.json.JSONObject): Result<Unit> {
        return try {
            val url = baseUrl.trimEnd('/') + "/v1/account/settings"
            val body = org.json.JSONObject().put("settings", settings)
            val res = request("PUT", url, token = basic(email, password), jsonBody = body)
            if (res.code in 200..299) Result.success(Unit) else Result.failure(IllegalStateException("HTTP ${res.code}: ${res.body}"))
        } catch (t: Throwable) { Result.failure(t) }
    }

    fun createHazardWithBasic(baseUrl: String, email: String, password: String, body: org.json.JSONObject): Result<com.roadwatch.network.HazardDto> {
        return try {
            val url = baseUrl.trimEnd('/') + "/v1/hazards"
            val res = request("POST", url, token = basic(email, password), jsonBody = body)
            if (res.code == 201 && res.body != null) Result.success(org.json.JSONObject(res.body).toHazardDto()) else Result.failure(IllegalStateException("HTTP ${res.code}: ${res.body}"))
        } catch (t: Throwable) { Result.failure(t) }
    }

    fun upvoteWithBasic(baseUrl: String, email: String, password: String, hazardId: String): Result<Unit> {
        return try {
            val url = baseUrl.trimEnd('/') + "/v1/hazards/${hazardId}/votes"
            val res = request("PUT", url, token = basic(email, password))
            if (res.code in 200..299) Result.success(Unit) else Result.failure(IllegalStateException("HTTP ${res.code}: ${res.body}"))
        } catch (t: Throwable) { Result.failure(t) }
    }

    fun unvoteWithBasic(baseUrl: String, email: String, password: String, hazardId: String): Result<Unit> {
        return try {
            val url = baseUrl.trimEnd('/') + "/v1/hazards/${hazardId}/votes"
            val res = request("DELETE", url, token = basic(email, password))
            if (res.code in 200..299) Result.success(Unit) else Result.failure(IllegalStateException("HTTP ${res.code}: ${res.body}"))
        } catch (t: Throwable) { Result.failure(t) }
    }
        return try {
            val url = baseUrl.trimEnd('/') + "/v1/devices/register"
            val payload = JSONObject()
                .put("platform", "ANDROID")
                .put("app_version", appVersion)
            val res = request("POST", url, jsonBody = payload)
            if (res.code == 201 && res.body != null) {
                val j = JSONObject(res.body)
                Result.success(j.getString("device_id") to j.getString("device_token"))
            } else {
                Result.failure(IllegalStateException("HTTP ${res.code}: ${res.body}"))
            }
        } catch (t: Throwable) {
            Result.failure(t)
        }
    }

    fun listHazards(baseUrl: String, lat: Double, lng: Double, radiusM: Int, limit: Int = 200, cursor: String? = null, token: String? = null): Result<com.roadwatch.network.HazardsListResponseDto> {
        return try {
            val sb = StringBuilder()
            sb.append(baseUrl.trimEnd('/')).append("/v1/hazards?lat=").append(lat).append("&lng=").append(lng).append("&radius_m=").append(radiusM).append("&limit=").append(limit)
            if (!cursor.isNullOrEmpty()) sb.append("&cursor=").append(java.net.URLEncoder.encode(cursor, "UTF-8"))
            val res = request("GET", sb.toString(), token)
            if (res.code == 200 && res.body != null) {
                val j = org.json.JSONObject(res.body)
                val arr = j.getJSONArray("hazards")
                val list = mutableListOf<com.roadwatch.network.HazardDto>()
                for (i in 0 until arr.length()) list += arr.getJSONObject(i).toHazardDto()
                val cur = if (j.isNull("cursor")) null else j.getString("cursor")
                Result.success(com.roadwatch.network.HazardsListResponseDto(list, cur))
            } else Result.failure(IllegalStateException("HTTP ${res.code}: ${res.body}"))
        } catch (t: Throwable) { Result.failure(t) }
    }

    // legacy token versions kept for backward compatibility where present

    fun voteStatus(baseUrl: String, hazardId: String, token: String? = null): Result<com.roadwatch.network.VoteStatusDto> {
        return try {
            val url = baseUrl.trimEnd('/') + "/v1/hazards/${hazardId}/votes"
            val res = request("GET", url, token)
            if (res.code == 200 && res.body != null) {
                val j = org.json.JSONObject(res.body)
                val dto = com.roadwatch.network.VoteStatusDto(
                    votes_count = j.getInt("votes_count"),
                    has_voted = if (j.has("has_voted") && !j.isNull("has_voted")) j.getBoolean("has_voted") else null
                )
                Result.success(dto)
            } else Result.failure(IllegalStateException("HTTP ${res.code}: ${res.body}"))
        } catch (t: Throwable) { Result.failure(t) }
    }

    fun voteStatusWithBasic(baseUrl: String, email: String, password: String, hazardId: String): Result<com.roadwatch.network.VoteStatusDto> {
        return try {
            val url = baseUrl.trimEnd('/') + "/v1/hazards/${hazardId}/votes"
            val res = request("GET", url, token = basic(email, password))
            if (res.code == 200 && res.body != null) {
                val j = org.json.JSONObject(res.body)
                val dto = com.roadwatch.network.VoteStatusDto(
                    votes_count = j.getInt("votes_count"),
                    has_voted = if (j.has("has_voted") && !j.isNull("has_voted")) j.getBoolean("has_voted") else null
                )
                Result.success(dto)
            } else Result.failure(IllegalStateException("HTTP ${res.code}: ${res.body}"))
        } catch (t: Throwable) { Result.failure(t) }
    }
}
