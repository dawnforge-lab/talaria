package lab.dawnforge.hermes.core.data.tasks

import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.serialization.json.Json
import lab.dawnforge.hermes.core.data.dashboard.DashboardRepository
import lab.dawnforge.hermes.core.data.signin.SignInService
import lab.dawnforge.hermes.core.gateway.TicketResult
import lab.dawnforge.hermes.core.model.DashboardId
import lab.dawnforge.hermes.core.net.DashboardClientFactory
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener

/** A batch of the board's events: how far it reaches, and the tasks it touched. */
data class BoardChange(val cursor: Long?, val taskIds: Set<String>)

/**
 * The board's live events (`/api/plugins/kanban/events`). Each connection takes a fresh
 * single-use ticket; the socket closes, and the flow ends, when the board goes away or the link
 * drops. The caller reloads the board and opens a new one from its latest event.
 */
class KanbanEvents(
    private val dashboards: DashboardRepository,
    private val clients: DashboardClientFactory,
    private val signIn: SignInService,
) {
    fun changes(id: DashboardId, board: String, since: Long): Flow<BoardChange> = callbackFlow {
        val dashboard = dashboards.dashboard(id)
        val ticket = signIn.mintGatewayTicket(id) as? TicketResult.Ticket
        if (dashboard == null || ticket == null) {
            close(IllegalStateException("No ticket for the board's events"))
            return@callbackFlow
        }
        val cloudflare = dashboards.cloudflareToken(id)
        val client = clients.createWebSocketClient(dashboard.url, signIn.upgradeCookies(id)) { cloudflare }
        // The kanban socket takes its ticket in the query only; it is single use and lasts 30 seconds.
        val url = dashboard.url.resolve(PATH).toHttpUrl().newBuilder()
            .addQueryParameter("ticket", ticket.value)
            .addQueryParameter("board", board)
            .addQueryParameter("since", since.toString())
            .build()
        val socket = client.newWebSocket(
            Request.Builder().url(url).build(),
            object : WebSocketListener() {
                // Hermes sends only when something happens: an empty change says the stream is up.
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    trySend(BoardChange(null, emptySet()))
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    runCatching {
                        Json.parseToJsonElement(text)
                    }.getOrNull()?.let(KanbanJson::frame)?.let { (cursor, ids) ->
                        trySend(BoardChange(cursor, ids))
                    }
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    close()
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    close(t)
                }
            },
        )
        awaitClose { socket.close(NORMAL_CLOSURE, null) }
    }

    private companion object {
        const val PATH = "/api/plugins/kanban/events"
        const val NORMAL_CLOSURE = 1000
    }
}
