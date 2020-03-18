package cz.tmscer.parksys.phone

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONObject

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

    fun shouldCapture(prefs: SharedPreferences): Boolean {
        return prefs.getString("shared_config_capturing", "false").equals("true")
    }

    fun updateConfig(config: JSONObject, prefs: SharedPreferences.Editor, prefix: String) {
        for (confKey in config.keys()) {
            val value = config.getString(confKey)
            // Prefix the config key
            val key = String.format("%s%s", prefix, confKey)
            println(confKey)
            println(key)
            prefs.putString(key, value)
        }
    }
}