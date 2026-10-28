package lab.dawnforge.hermes.core.data.settings

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import lab.dawnforge.hermes.core.data.session.DashboardRequests
import lab.dawnforge.hermes.core.data.session.map
import lab.dawnforge.hermes.core.model.DashboardId
import lab.dawnforge.hermes.core.net.ConnectionFailure
import lab.dawnforge.hermes.core.net.NetResult

/**
 * A profile's skills, toolsets and keys, as the iOS app's Capabilities pages manage them. Every
 * change applies to new sessions.
 */
class CapabilitiesRepository(private val requests: DashboardRequests) {
    suspend fun skills(id: DashboardId, profile: String): NetResult<List<Skill>> = requests.run(id) { url, jar, cf ->
        get(url, jar, cf, "/api/skills", query = mapOf("profile" to profile)).map(CapabilitiesJson::skills)
    }

    suspend fun setSkill(id: DashboardId, profile: String, name: String, enabled: Boolean): NetResult<Unit> =
        requests.run(id) { url, jar, cf ->
            val body = buildJsonObject {
                put("name", name)
                put("enabled", enabled)
                put("profile", profile)
            }
            put(url, jar, cf, "/api/skills/toggle", emptyList(), body).map { }
        }

    /** A skill's `SKILL.md`. */
    suspend fun skillFile(id: DashboardId, profile: String, name: String): NetResult<String> =
        requests.run(id) { url, jar, cf ->
            get(url, jar, cf, "/api/skills/content", query = mapOf("name" to name, "profile" to profile))
                .map { (it as? JsonObject)?.text("content").orEmpty() }
        }

    /** Hermes' official skills catalog. */
    suspend fun catalog(id: DashboardId, profile: String): NetResult<List<CatalogSkill>> =
        requests.run(id) { url, jar, cf ->
            get(
                url,
                jar,
                cf,
                "/api/skills/hub/official",
                query = mapOf("profile" to profile),
            ).map(CapabilitiesJson::catalog)
        }

    /** A catalog skill's `SKILL.md`, before installing it. */
    suspend fun catalogFile(id: DashboardId, profile: String, identifier: String): NetResult<String> =
        requests.run(id) { url, jar, cf ->
            get(
                url,
                jar,
                cf,
                "/api/skills/hub/preview",
                query = mapOf("identifier" to identifier, "profile" to profile),
            )
                .map { (it as? JsonObject)?.text("skill_md").orEmpty() }
        }

    /**
     * Starts installing a catalog skill (Hermes scans it first) and answers the name of the
     * background action to follow ([GatewayStatusRepository.action]).
     */
    suspend fun installSkill(id: DashboardId, profile: String, identifier: String): NetResult<String> =
        requests.run(id) { url, jar, cf ->
            val body = buildJsonObject {
                put("identifier", identifier)
                put("profile", profile)
            }
            when (val started = post(url, jar, cf, "/api/skills/hub/install", body, mapOf("profile" to profile))) {
                is NetResult.Failure -> started

                is NetResult.Success -> (started.value as? JsonObject)?.text("name")?.let { NetResult.Success(it) }
                    ?: NetResult.Failure(ConnectionFailure.UnexpectedResponse(null, "Hermes didn't start the install"))
            }
        }

    suspend fun toolsets(id: DashboardId, profile: String): NetResult<List<Toolset>> = requests.run(id) {
            url,
            jar,
            cf,
        ->
        get(url, jar, cf, "/api/tools/toolsets", query = mapOf("profile" to profile)).map(CapabilitiesJson::toolsets)
    }

    /** Calls per tool over the last year. */
    suspend fun toolUsage(id: DashboardId, profile: String): NetResult<Map<String, Long>> =
        requests.run(id) { url, jar, cf ->
            get(url, jar, cf, "/api/analytics/usage", query = mapOf("days" to "365", "profile" to profile))
                .map(CapabilitiesJson::toolUsage)
        }

    suspend fun setToolset(id: DashboardId, profile: String, name: String, enabled: Boolean): NetResult<Unit> =
        requests.run(id) { url, jar, cf ->
            val body = buildJsonObject {
                put("enabled", enabled)
                put("profile", profile)
            }
            put(url, jar, cf, "/api/tools/toolsets", listOf(name), body, mapOf("profile" to profile)).map { }
        }

    suspend fun toolsetConfig(id: DashboardId, profile: String, name: String): NetResult<ToolsetConfig> =
        requests.run(id) { url, jar, cf ->
            get(url, jar, cf, "/api/tools/toolsets", listOf(name, "config"), mapOf("profile" to profile))
                .map(CapabilitiesJson::toolsetConfig)
        }

    /**
     * Makes [backend] the toolset's; for the web toolset, for one [capability] (`search` or
     * `extract`). True when it still needs a Nous Portal sign-in on the host.
     */
    suspend fun useBackend(
        id: DashboardId,
        profile: String,
        toolset: String,
        backend: String,
        capability: String? = null,
    ): NetResult<Boolean> = requests.run(id) { url, jar, cf ->
        val body = buildJsonObject {
            put("provider", backend)
            capability?.let { put("capability", it) }
            put("profile", profile)
        }
        put(url, jar, cf, "/api/tools/toolsets", listOf(toolset, "provider"), body, mapOf("profile" to profile)).map {
            ((it as? JsonObject)?.get("needs_nous_auth") as? JsonPrimitive)?.booleanOrNull == true
        }
    }

    suspend fun terminalBackends(id: DashboardId, profile: String): NetResult<List<TerminalBackend>> =
        requests.run(id) { url, jar, cf ->
            get(url, jar, cf, "/api/tools/terminal/backends", query = mapOf("profile" to profile))
                .map(CapabilitiesJson::terminalBackends)
        }

    suspend fun useTerminal(id: DashboardId, profile: String, backend: String): NetResult<Unit> =
        requests.run(id) { url, jar, cf ->
            val body = buildJsonObject {
                put("backend", backend)
                put("profile", profile)
            }
            put(url, jar, cf, "/api/tools/terminal/backend", emptyList(), body, mapOf("profile" to profile)).map { }
        }

    /** Saves a key to the profile's `.env` on the host. The phone never reads keys back. */
    suspend fun setKey(id: DashboardId, profile: String, key: String, value: String): NetResult<Unit> =
        requests.run(id) { url, jar, cf ->
            val body = buildJsonObject {
                put("key", key)
                put("value", value)
                put("profile", profile)
            }
            put(url, jar, cf, "/api/env", emptyList(), body, mapOf("profile" to profile)).map { }
        }
}

private fun JsonObject.text(key: String): String? =
    (get(key) as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.content?.takeIf(String::isNotEmpty)
