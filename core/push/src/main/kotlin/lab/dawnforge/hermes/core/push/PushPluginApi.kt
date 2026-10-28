package lab.dawnforge.hermes.core.push

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import lab.dawnforge.hermes.core.data.session.DashboardRequests
import lab.dawnforge.hermes.core.model.DashboardId
import lab.dawnforge.hermes.core.net.NetResult

/** The `talaria_push` plugin's dashboard routes (`/api/plugins/talaria_push/<route>`) for a profile. */
interface PushPluginApi {
    suspend fun get(dashboardId: DashboardId, profile: String, route: String): NetResult<JsonElement>

    suspend fun post(dashboardId: DashboardId, profile: String, route: String, body: JsonObject): NetResult<JsonElement>
}

/** Through the dashboard's signed-in session, renewed once if it expired. */
class DashboardPushPluginApi(private val requests: DashboardRequests) : PushPluginApi {
    override suspend fun get(dashboardId: DashboardId, profile: String, route: String) =
        requests.run(dashboardId) { url, jar, cloudflare ->
            get(url, jar, cloudflare, path(route), query = query(profile))
        }

    override suspend fun post(dashboardId: DashboardId, profile: String, route: String, body: JsonObject) =
        requests.run(dashboardId) { url, jar, cloudflare ->
            post(url, jar, cloudflare, path(route), body, query(profile))
        }

    private fun path(route: String) = "/api/plugins/talaria_push/$route"

    private fun query(profile: String) = mapOf("profile" to profile)
}
