package cz.tmscer.parksys.phone

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.SurfaceTexture
import android.hardware.Camera
import android.net.Uri
import android.os.AsyncTask
import android.os.Bundle
import android.os.CountDownTimer
import android.telephony.MbmsDownloadSession.RESULT_CANCELLED
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.android.volley.Request
import com.android.volley.Response
import com.android.volley.request.JsonObjectRequest
import com.android.volley.request.SimpleMultiPartRequest
import com.beust.klaxon.Klaxon
import com.beust.klaxon.KlaxonException
import cz.tmscer.parksys.phone.models.ActivationPassword
import kotlinx.android.synthetic.main.activity_main.*
import org.json.JSONObject
import java.io.File
import java.io.StringReader
import java.text.ParseException
import java.util.*

class MainActivity : AppCompatActivity(), AsyncResponse<ActivationPassword?> {

    private var captureOn = false
    private val CAMERA_PERMISSION_REQUEST = 0
    private var camera: Camera? = null

    private fun take() {
        setupCamera()
        if (camera != null) {
            camera!!.setPreviewTexture(SurfaceTexture(0))
            camera!!.startPreview()
            camera!!.takePicture(null, null, jpgCallback)
        }
    }

    var jpgCallback = Camera.PictureCallback { data, camera ->
        Log.d("CAMERA", "onPictureTaken - jpg")
        if (captureOn) {
            // Take the next picture
            object : CountDownTimer(1000, 1000) {
                override fun onTick(millisUntilFinished: Long) {
                    println("TICK")
                }

                override fun onFinish() {
                    take()
                }
            }.start()
        }
        // Save file
        // https://developer.android.com/training/data-storage/app-specific
        val fname = "capture_" + System.currentTimeMillis()
        val file = File(this.filesDir, fname)
        file.writeBytes(data)

        // Upload
        // https://github.com/DWorkS/VolleyPlus
        val uploadRequest = SimpleMultiPartRequest(Request.Method.POST, backendUrl() + "/capture",
            Response.Listener { response ->
                println(response)
                // TODO: Update local config
            },
            Response.ErrorListener { error ->
                println(error)
                println(error.networkResponse.data)
            })
        val h = getPreferences(Context.MODE_PRIVATE).getString(
            getString(R.string.prefs_access_token), "NOTOKEN"
        )
        println(h)
        uploadRequest.headers = mapOf("Authentication" to "Bearer $h")
        uploadRequest.addFile(fname, file.absolutePath)
        API.getInstance(this).addToRequestQueue(uploadRequest)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        setDefaultPreferences()
        captureActivation.setOnClickListener(captureQrActivationPassword)

        btnSettings.setOnClickListener(openSettingsActivity)
        btnPerms.setOnClickListener(getPermissions)

        btnToggleCapture.setOnClickListener(toggleCapture)
        btnOneCapture.setOnClickListener(singleCapture)
    }

    private val toggleCapture = View.OnClickListener {
        captureOn = !captureOn
        if (captureOn) {
            btnToggleCapture.text = getString(R.string.capture_set_off)
            take()
        } else {
            btnToggleCapture.text = getString(R.string.capture_set_on)
        }
    }

    private val singleCapture = View.OnClickListener {
        take()
    }

    private fun setupCamera() {
        if (camera != null) return
        camera = getCameraInstance()
    }

    private fun getCameraInstance(): Camera {
        var camera: Camera? = null
        try {
            camera = Camera.open()
        } catch (e: java.lang.Exception) {
            println("CAM FAILED")
            // cannot get camera or does not exist
        }
        return camera!!
    }

    override fun onResume() {
        super.onResume()
        println(">>> OUTPUT PREFS")
        for ((k, v) in getPreferences(Context.MODE_PRIVATE).all) {
            print(k)
            print(", ")
            println(v)
        }

        println("<<< OUTPUT PREFS")
    }

    private val openSettingsActivity = View.OnClickListener {
        val myIntent = Intent(this, SettingsActivity::class.java)
        startActivity(myIntent)
    }

    private fun setDefaultPreferences(force: Boolean = false) {
        val defaults = mapOf(
            R.string.prefs_server_port to 8080,
            R.string.prefs_server_host to "192.168.1.48",
            R.string.prefs_server_protocol to "http"
        )
        val prefs = getPreferences(Context.MODE_PRIVATE)
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

    private fun backendUrl(): String {
        val prefs = getPreferences(Context.MODE_PRIVATE)
        return prefs.getString(
            getString(R.string.prefs_server_protocol),
            "https"
        ) + "://" + prefs.getString(getString(R.string.prefs_server_host), "") + ":" + prefs.getInt(
            getString(R.string.prefs_server_port),
            80
        )
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
            JsonObjectRequest(Request.Method.POST, backendUrl() + "/devices/activate",
                body,
                Response.Listener<JSONObject> { response ->
                    println(response)
                    if (!response.has("data")) return@Listener
                    val data = response.getJSONObject("data")
                    if (data.has("accessToken") && data.has("refreshToken")) {
                        val accessT = data.getString("accessToken")
                        val refreshT = data.getString("refreshToken")
                        val prefs = getPreferences(Context.MODE_PRIVATE)
                        with(prefs.edit()) {
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
