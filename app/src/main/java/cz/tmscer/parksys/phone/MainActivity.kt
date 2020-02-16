package cz.tmscer.parksys.phone

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.AsyncTask
import android.os.Bundle
import android.telephony.MbmsDownloadSession.RESULT_CANCELLED
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.preference.PreferenceManager
import com.android.volley.Request
import com.android.volley.Response
import com.android.volley.request.JsonObjectRequest
import com.beust.klaxon.Klaxon
import com.beust.klaxon.KlaxonException
import cz.tmscer.parksys.phone.models.ActivationPassword
import kotlinx.android.synthetic.main.activity_main.*
import org.json.JSONObject
import java.io.StringReader
import java.text.ParseException
import java.util.*

class MainActivity : AppCompatActivity(), AsyncResponse<ActivationPassword?> {

    private var captureOn = false
    private val CAMERA_PERMISSION_REQUEST = 0
    private var capture: Capture? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        capture = Capture(this, prefs)

        setDefaultPreferences()
        captureActivation.setOnClickListener(captureQrActivationPassword)

        btnSettings.setOnClickListener(openSettingsActivity)
        btnPerms.setOnClickListener(getPermissions)

        btnToggleCapture.setOnClickListener(toggleCapture)
        btnOneCapture.setOnClickListener { capture!!.capturePicture() }
        askConfig.setOnClickListener { capture!!.askForConfig() }

        textAccessToken.text =
            prefs.getString(getString(R.string.prefs_access_token), "<NO ACCESS TOKEN>")
        textRefreshToken.text = prefs.getString(
            getString(R.string.prefs_refresh_token),
            "<NO REFRESH TOKEN>"
        )
    }

    override fun onPause() {
        super.onPause()
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    private val toggleCapture = View.OnClickListener {
        captureOn = !captureOn
        if (captureOn) {
            btnToggleCapture.text = getString(R.string.capture_set_off)
            capture!!.continuousCaptureOn()
            PreferenceManager.getDefaultSharedPreferences(this)
                .edit()
                .putString("shared_config_capturing", "true")
                .commit()
        } else {
            btnToggleCapture.text = getString(R.string.capture_set_on)
            capture!!.continuousCaptureOff()
            PreferenceManager.getDefaultSharedPreferences(this)
                .edit()
                .putString("shared_config_capturing", "false")
                .commit()
        }
    }

    override fun onResume() {
        super.onResume()
        println(">>> OUTPUT PREFS")
        for ((k, v) in PreferenceManager.getDefaultSharedPreferences(this).all) {
            print(k)
            print(", ")
            println(v)
        }

        println("<<< OUTPUT PREFS")
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    private val openSettingsActivity = View.OnClickListener {
        val myIntent = Intent(this, SettingsActivity::class.java)
        startActivity(myIntent)
    }

    private fun setDefaultPreferences(force: Boolean = false) {
        val defaults = mapOf(
            R.string.prefs_server_port to "8080",
            R.string.prefs_server_host to "192.168.1.48",
            R.string.prefs_server_protocol to "http",
            R.string.app_name to 1
        )
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        with(prefs.edit()) {
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

    private val getPermissions = View.OnClickListener {
        val permission = Manifest.permission.CAMERA
        Thread {
            if (ContextCompat.checkSelfPermission(
                    this,
                    permission
                ) != PackageManager.PERMISSION_GRANTED
            ) {

                // Permission is not granted
                if (ActivityCompat.shouldShowRequestPermissionRationale(
                        this,
                        permission
                    )
                ) {
                    Toast.makeText(this, "Camera permission is needed.", Toast.LENGTH_SHORT).show()
                } else {
                    // We can request the permission.
                    ActivityCompat.requestPermissions(
                        this,
                        arrayOf(permission),
                        CAMERA_PERMISSION_REQUEST
                    )
                    // Response is handled by onRequestPermissionResult
                }
            } else {
                // Permission has already been granted
                println("PERMISSION ALREADY GRANTED")
            }
        }.start()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>, grantResults: IntArray
    ) {
        when (requestCode) {
            CAMERA_PERMISSION_REQUEST -> {
                // If request is cancelled, the result arrays are empty.
                if ((grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED)) {
                    // permission was granted, yay! Do the
                    // contacts-related task you need to do.
                    println("PERMISSION GRANTED")
                } else {
                    // permission denied, boo! Disable the
                    // functionality that depends on this permission.
                    println("PERMISSION DENIED")
                }
                return
            }

            // Add other 'when' lines to check for other
            // permissions this app might request.
            else -> {
                // Ignore all other requests.
            }
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
        // TODO: Check if expired and notify the user
        val body = JSONObject()
        body.put("activationPassword", output!!.password)
        val request =
            JsonObjectRequest(Request.Method.POST,
                Helpers.backendUrl(
                    this,
                    PreferenceManager.getDefaultSharedPreferences(this)
                ) + "/devices/activate",
                body,
                Response.Listener<JSONObject> { response ->
                    println(response)
                    if (!response.has("data")) return@Listener
                    val data = response.getJSONObject("data")
                    if (data.has("accessToken") && data.has("refreshToken")) {
                        val accessT = data.getString("accessToken")
                        val refreshT = data.getString("refreshToken")
                        val config = data.getJSONObject("device").getJSONObject("config")
                        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
                        with(prefs.edit()) {
                            putString(getString(R.string.prefs_refresh_token), refreshT)
                            putString(getString(R.string.prefs_access_token), accessT)
                            Helpers.updateConfig(
                                config,
                                this,
                                getString(R.string.prefs_config_prefix)
                            )
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
                }
            )
        API.getInstance(this).addToRequestQueue(request)
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
