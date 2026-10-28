package lab.dawnforge.hermes.tile

import android.app.PendingIntent
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import androidx.core.service.quicksettings.PendingIntentActivityWrapper
import androidx.core.service.quicksettings.TileServiceCompat
import lab.dawnforge.hermes.launch.LaunchAction
import lab.dawnforge.hermes.launch.LauncherActivity

/** The Quick Settings tile: a new chat with Hermes, on the dashboard and profile the app shows. */
class NewChatTileService : TileService() {
    override fun onStartListening() {
        super.onStartListening()
        qsTile?.apply {
            state = Tile.STATE_INACTIVE
            updateTile()
        }
    }

    override fun onClick() {
        super.onClick()
        val open = {
            val pending = PendingIntentActivityWrapper(
                this,
                REQUEST,
                LauncherActivity.intent(this, LaunchAction.NewChat(null, null)),
                PendingIntent.FLAG_UPDATE_CURRENT,
                false,
            )
            TileServiceCompat.startActivityAndCollapse(this, pending)
        }
        // A locked phone unlocks first: the chat shows what's on Hermes.
        if (isLocked) unlockAndRun(open) else open()
    }

    private companion object {
        const val REQUEST = 1
    }
}
