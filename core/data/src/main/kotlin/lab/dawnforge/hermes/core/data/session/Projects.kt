package lab.dawnforge.hermes.core.data.session

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import lab.dawnforge.hermes.core.model.SessionSummary

/**
 * A project on Hermes (`projects.*`): a named set of folders. A chat belongs to the project whose
 * folder holds its working folder; Hermes works that out, the phone only shows it.
 */
data class Project(
    val id: String,
    val title: String,
    /** The folder new chats in the project start in; null for Home. */
    val primaryPath: String?,
    /** `#rrggbb`, when the project has a colour. */
    val color: String?,
    /** Hermes' bucket for chats in no project. */
    val isHome: Boolean,
    /** Found by Hermes from a git repository chats worked in, not made by anyone. */
    val isAuto: Boolean,
    val sessionCount: Int,
    /** The newest few chats. */
    val previewSessions: List<SessionSummary>,
    /** Every chat Hermes counts in the project. */
    val sessionIds: Set<String>,
) {
    /** Only a project someone made can be renamed or deleted. */
    val editable: Boolean get() = !isHome && !isAuto

    /** A chat can be moved to a project with a folder. */
    val canTakeChats: Boolean get() = !isHome && !primaryPath.isNullOrBlank()
}

/** A folder of a project with its chats: a repository's checkout, a worktree, a subfolder. */
data class ProjectLane(
    val id: String,
    /** Null for "Recent", the newest chats of a project Hermes gave no folders for. */
    val title: String?,
    val path: String?,
    val sessions: List<SessionSummary>,
)

data class ProjectDetail(val id: String, val title: String, val lanes: List<ProjectLane>)

object ProjectsJson {
    /** Home first, then the busiest, then by name, as iOS lists them. */
    fun tree(json: JsonElement): List<Project> {
        val rows = (json as? JsonObject)?.get("projects") as? JsonArray ?: json as? JsonArray ?: JsonArray(emptyList())
        return rows.mapNotNull { (it as? JsonObject)?.let(::project) }.sortedWith(
            compareByDescending<Project> { it.isHome }
                .thenByDescending { it.sessionCount }
                .thenBy(String.CASE_INSENSITIVE_ORDER) { it.title },
        )
    }

    fun project(json: JsonObject): Project? {
        val id = json.string("id")?.trim()?.takeIf(String::isNotEmpty) ?: return null
        val home = json.bool("isNoProject") ?: json.bool("is_no_project") ?: (id == HOME_ID)
        val previews = sessions(json["previewSessions"] ?: json["preview_sessions"])
        val primary = listOf("path", "primary_path", "primaryPath").firstNotNullOfOrNull { json.string(it) }
            ?: firstPath(json["folders"])
            ?: if (home) null else firstPath(json["repos"])
        return Project(
            id = id,
            title =
                listOf("label", "name", "title").firstNotNullOfOrNull {
                    json.string(it)?.trim()?.takeIf(String::isNotEmpty)
                }
                    ?: UNTITLED,
            primaryPath = primary,
            color = json.string("color"),
            isHome = home,
            // A project without the flag is taken as found, so nothing made by Hermes is offered for editing.
            isAuto = json.bool("isAuto") ?: json.bool("is_auto") ?: (!home && !id.startsWith("p_")),
            sessionCount = json.count("sessionCount") ?: json.count("session_count") ?: previews.size,
            previewSessions = previews,
            sessionIds = (json["sessionIds"] as? JsonArray).orEmpty()
                .mapNotNullTo(LinkedHashSet()) { (it as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.content },
        )
    }

    /**
     * A project's chats by folder (`projects.project_sessions`). With more than one repository a
     * lane is named "repository · folder".
     */
    fun detail(json: JsonElement): ProjectDetail? {
        val project = (json as? JsonObject)?.get("project") as? JsonObject ?: return null
        val id = project.string("id") ?: return null
        val title = listOf("label", "name", "title").firstNotNullOfOrNull { project.string(it) } ?: UNTITLED
        val repos = (project["repos"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
        val lanes = repos.flatMap { repo ->
            val repoLabel = repo.string("label") ?: repo.string("path")
            (repo["groups"] as? JsonArray).orEmpty().mapNotNull { row ->
                val group = row as? JsonObject ?: return@mapNotNull null
                val sessions = sessions(group["sessions"])
                if (sessions.isEmpty()) return@mapNotNull null
                val label = group.string("label") ?: group.string("path") ?: ""
                val path = group.string("path") ?: group.string("cwd")
                ProjectLane(
                    id = group.string("id") ?: path ?: label,
                    title = if (repos.size > 1 && repoLabel != null) "$repoLabel · $label" else label,
                    path = path?.takeIf { it != label },
                    sessions = sessions,
                )
            }
        }
        val previews = sessions(project["previewSessions"] ?: project["preview_sessions"])
        val shown = if (lanes.isEmpty() &&
            previews.isNotEmpty()
        ) {
            listOf(ProjectLane("recent", null, null, previews))
        } else {
            lanes
        }
        return ProjectDetail(id, title, shown)
    }

    private fun sessions(json: JsonElement?): List<SessionSummary> =
        (json as? JsonArray).orEmpty().mapNotNull { (it as? JsonObject)?.toSessionSummary() }

    private fun firstPath(json: JsonElement?): String? = (json as? JsonArray).orEmpty().firstNotNullOfOrNull {
        (it as? JsonObject)?.string("path")?.takeIf(String::isNotBlank)
    }

    private fun JsonObject.bool(key: String): Boolean? = (this[key] as? JsonPrimitive)?.booleanOrNull

    private fun JsonObject.count(key: String): Int? =
        (this[key] as? JsonPrimitive)?.let { it.intOrNull ?: it.content.toIntOrNull() }

    private fun JsonArray?.orEmpty(): List<JsonElement> = this ?: emptyList()

    /** Hermes' id for the bucket of chats in no project. */
    const val HOME_ID = "__no_project__"
    private const val UNTITLED = "Untitled project"
}
