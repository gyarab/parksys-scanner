package cz.tmscer.parksys.phone

import android.content.Context
import android.content.SharedPreferences

object Helpers {
    fun backendUrl(context: Context, prefs: SharedPreferences): String {
        return prefs.getString(
            context.getString(R.string.prefs_server_protocol),
            "https"
        ) + "://" + prefs.getString(
            context.getString(R.string.prefs_server_host),
            ""
        ) + ":" + prefs.getString(
            context.getString(R.string.prefs_server_port),
            "80"
        )
    }
}