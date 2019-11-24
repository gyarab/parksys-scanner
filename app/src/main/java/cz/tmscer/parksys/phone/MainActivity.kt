package cz.tmscer.parksys.phone

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.AsyncTask
import android.os.Bundle
import android.telephony.MbmsDownloadSession.RESULT_CANCELLED
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.android.volley.Request
import com.android.volley.Response
import com.android.volley.toolbox.JsonObjectRequest
import com.android.volley.toolbox.Volley
import com.beust.klaxon.Klaxon
import com.beust.klaxon.KlaxonException
import cz.tmscer.parksys.phone.models.ActivationPassword
import kotlinx.android.synthetic.main.activity_main.*
import org.json.JSONObject
import java.io.StringReader
import java.text.ParseException
import java.util.*


class MainActivity : AppCompatActivity(), AsyncResponse<ActivationPassword?> {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        setDefaultPreferences()
        captureActivation.setOnClickListener(captureQrActivationPassword)
    }

    private fun setDefaultPreferences(force: Boolean = false) {
        val defaults = mapOf(
            R.string.prefs_server_port to 8080,
            R.string.prefs_server_host to "192.168.1.48",
            R.string.prefs_server_protocol to "http"
        )
        val prefs = getPreferences(Context.MODE_PRIVATE)
        with (prefs.edit()) {
            for ((keyI, value) in defaults) {
                val key = getString(keyI)
                if (force || !prefs.contains(key)) {
                    when (value) {
                        is Int -> putInt(key, value)
                        is String -> putString(key, value)
                        is Float -> putFloat(key, value)
                        is Long -> putLong(key, value)
                        is Boolean -> putBoolean(key, value)
                    }
                }
            }
            commit()
        }
    }

    // Taken from https://stackoverflow.com/questions/8830647/how-to-scan-qrcode-in-android/8830801
    private val captureQrActivationPassword = View.OnClickListener {
        try {
            val intent = Intent("com.google.zxing.client.android.SCAN")
            intent.putExtra("SCAN_MODE", "QR_CODE_MODE") // "PRODUCT_MODE for bar codes
            startActivityForResult(intent, 0)
        } catch (e: Exception) {
            val marketUri = Uri.parse("market://details?id=com.google.zxing.client.android")
            val marketIntent = Intent(Intent.ACTION_VIEW, marketUri)
            startActivity(marketIntent)
        }
    }

    // Taken from https://stackoverflow.com/questions/8830647/how-to-scan-qrcode-in-android/8830801
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        // Does not block main thread

        if (requestCode == 0) {
            if (resultCode == Activity.RESULT_OK) {
                val asyncTask = ParseQrActivationPassword()
                asyncTask.delegate = this
                asyncTask.execute(data!!.getStringExtra("SCAN_RESULT"))
            }
            if (resultCode == RESULT_CANCELLED) {
                Toast.makeText(this, "Failed to scan QRCode", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun processFinish(output: ActivationPassword?) {
        // https://developer.android.com/training/volley/simple
        val queue = Volley.newRequestQueue(this)
        // TODO: Check if expired and notify the user
        val body = JSONObject()
        body.put("activationPassword", output!!.password)
        val request =
            JsonObjectRequest(Request.Method.POST, "http://192.168.1.48:8080/devices/activate",
                body,
                Response.Listener<JSONObject> { response ->
                    println(response)
                    if (!response.has("data")) return@Listener
                    val data = response.getJSONObject("data")
                    if (data.has("accessToken") && data.has("refreshToken")) {
                        val accessT = data.getString("accessToken")
                        val refreshT = data.getString("refreshToken")
                        val prefs = getPreferences(Context.MODE_PRIVATE)
                        with (prefs.edit()) {
                            putString(getString(R.string.prefs_refresh_token), refreshT)
                            putString(getString(R.string.prefs_access_token), accessT)
                            textAccessToken.text = accessT
                            textRefreshToken.text = refreshT
                            commit()
                        }
                    } else {
                        // Error
                    }
                },
                Response.ErrorListener { error ->
                    println(error)
                    println(error.networkResponse.data)
                }
            )
        queue.add(request)
    }

    class ParseQrActivationPassword : AsyncTask<String, Void, ActivationPassword>() {
        var delegate: AsyncResponse<ActivationPassword?>? = null
        private fun parseActivationPassword(value: String): ActivationPassword? {
            try {
                val json = Klaxon().parseJsonObject(StringReader(value))
                if (json.containsKey("password") && json.containsKey("expiresAt")) {
                    return try {
                        val expiresAt = Date(json["expiresAt"].toString().toLong())
                        ActivationPassword(json["password"].toString(), expiresAt)
                    } catch (ex: Exception) {
                        when (ex) {
                            is IllegalArgumentException, is ParseException -> {
                                println(ex)
                                return null
                            }
                            else -> throw ex
                        }
                    }
                }
            } catch (e: KlaxonException) {
                println(e)
            }
            return null
        }

        override fun doInBackground(vararg contents: String): ActivationPassword? {
            return this.parseActivationPassword(contents[0])
        }

        override fun onPostExecute(result: ActivationPassword?) {
            super.onPostExecute(result)
            if (this.delegate != null) {
                this.delegate!!.processFinish(result)
            }
        }
    }
}
