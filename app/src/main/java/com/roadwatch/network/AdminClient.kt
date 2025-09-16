package com.roadwatch.network

import android.content.Context

object AdminClient {
    private fun isUnauthorized(err: Throwable?): Boolean =
        (err?.message ?: "").contains("HTTP 401")

    fun patchHazardWithRefresh(ctx: Context, baseUrl: String, id: String, patch: org.json.JSONObject): Result<Unit> {
        val access0 = com.roadwatch.prefs.AppPrefs.getAdminAccess(ctx)
        val refresh = com.roadwatch.prefs.AppPrefs.getAdminRefresh(ctx)
        if (access0.isNullOrEmpty() || refresh.isNullOrEmpty()) return Result.failure(IllegalStateException("admin not logged in"))
        val r1 = ApiClient.adminPatchHazard(baseUrl, access0, id, patch)
        if (r1.isSuccess) return Result.success(Unit)
        if (!isUnauthorized(r1.exceptionOrNull())) return Result.failure(r1.exceptionOrNull()!!)
        // Try refresh
        val rr = ApiClient.adminRefresh(baseUrl, refresh)
        if (!rr.isSuccess) return Result.failure(rr.exceptionOrNull() ?: IllegalStateException("refresh failed"))
        val (access1, newRefresh) = rr.getOrNull()!!
        com.roadwatch.prefs.AppPrefs.setAdminTokens(ctx, access1, newRefresh)
        val r2 = ApiClient.adminPatchHazard(baseUrl, access1, id, patch)
        return if (r2.isSuccess) Result.success(Unit) else Result.failure(r2.exceptionOrNull()!!)
    }

    fun deleteHazardWithRefresh(ctx: Context, baseUrl: String, id: String): Result<Unit> {
        val access0 = com.roadwatch.prefs.AppPrefs.getAdminAccess(ctx)
        val refresh = com.roadwatch.prefs.AppPrefs.getAdminRefresh(ctx)
        if (access0.isNullOrEmpty() || refresh.isNullOrEmpty()) return Result.failure(IllegalStateException("admin not logged in"))
        val r1 = ApiClient.adminDeleteHazard(baseUrl, access0, id)
        if (r1.isSuccess) return Result.success(Unit)
        if (!isUnauthorized(r1.exceptionOrNull())) return Result.failure(r1.exceptionOrNull()!!)
        val rr = ApiClient.adminRefresh(baseUrl, refresh)
        if (!rr.isSuccess) return Result.failure(rr.exceptionOrNull() ?: IllegalStateException("refresh failed"))
        val (access1, newRefresh) = rr.getOrNull()!!
        com.roadwatch.prefs.AppPrefs.setAdminTokens(ctx, access1, newRefresh)
        val r2 = ApiClient.adminDeleteHazard(baseUrl, access1, id)
        return if (r2.isSuccess) Result.success(Unit) else Result.failure(r2.exceptionOrNull()!!)
    }

    fun listHazardsWithRefresh(
        ctx: Context,
        baseUrl: String,
        limit: Int,
        cursor: String?,
        type: String? = null,
        source: String? = null,
        active: Boolean? = null,
        search: String? = null,
        minLat: Double? = null,
        minLng: Double? = null,
        maxLat: Double? = null,
        maxLng: Double? = null,
        createdAfter: String? = null,
        createdBefore: String? = null,
    ): Result<com.roadwatch.network.HazardsListResponseDto> {
        val access0 = com.roadwatch.prefs.AppPrefs.getAdminAccess(ctx)
        val refresh = com.roadwatch.prefs.AppPrefs.getAdminRefresh(ctx)
        if (access0.isNullOrEmpty() || refresh.isNullOrEmpty()) return Result.failure(IllegalStateException("admin not logged in"))
        val r1 = ApiClient.adminListHazards(baseUrl, access0, limit, cursor, type, source, active, search, minLat, minLng, maxLat, maxLng, createdAfter, createdBefore)
        if (r1.isSuccess) return r1
        if (!isUnauthorized(r1.exceptionOrNull())) return r1
        val rr = ApiClient.adminRefresh(baseUrl, refresh)
        if (!rr.isSuccess) return Result.failure(rr.exceptionOrNull() ?: IllegalStateException("refresh failed"))
        val (access1, newRefresh) = rr.getOrNull()!!
        com.roadwatch.prefs.AppPrefs.setAdminTokens(ctx, access1, newRefresh)
        return ApiClient.adminListHazards(baseUrl, access1, limit, cursor, type, source, active, search, minLat, minLng, maxLat, maxLng, createdAfter, createdBefore)
    }
}
