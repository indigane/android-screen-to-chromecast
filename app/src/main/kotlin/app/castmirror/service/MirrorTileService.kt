package app.castmirror.service

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import app.castmirror.R
import app.castmirror.ui.MainActivity

/**
 * A quick-settings tile that opens CastMirror, mirroring Google's own "Cast screen" tile.
 *
 * The tile deliberately does not start mirroring on its own. Every session needs a fresh
 * `MediaProjection` consent — the token is single-use from Android 14 — and consent can only be
 * requested from an activity, so the tile's job is to get the user to that activity in one tap.
 * While a session is running the tile shows as active and taps still open the app, where Stop is.
 */
class MirrorTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        updateTile()
    }

    override fun onClick() {
        super.onClick()
        val intent = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)

        if (isLocked) {
            // The activity would be launched behind the keyguard and never seen.
            unlockAndRun { launch(intent) }
        } else {
            launch(intent)
        }
    }

    private fun launch(intent: Intent) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val pending = PendingIntent.getActivity(
                this,
                0,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            startActivityAndCollapse(pending)
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }

    private fun updateTile() {
        val tile = qsTile ?: return
        val running = MirrorService.isRunning
        tile.state = if (running) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = getString(R.string.app_name)
        tile.contentDescription = getString(
            if (running) R.string.tile_description_active else R.string.tile_description_idle
        )
        tile.updateTile()
    }
}
