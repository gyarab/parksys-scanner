package cz.tmscer.parksys.phone

import android.content.Context
import android.content.SharedPreferences
import android.graphics.SurfaceTexture
import android.hardware.Camera
import android.os.CountDownTimer
import android.os.Looper
import android.util.Log
import androidx.preference.PreferenceManager
import com.android.volley.Request
import com.android.volley.Response
import com.android.volley.request.JsonObjectRequest
import com.android.volley.request.SimpleMultiPartRequest
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.io.IOException

class Capture(private val context: Context, private val preferences: SharedPreferences) {
    private val loggerName = "CAPTURE"
    private var camera: Camera? = null
    private var capturing = false

    private fun capture() {
        if (camera == null) {
            try {
                camera = Camera.open()
            } catch (e: RuntimeException) {
                Log.e(loggerName, "Failed to open camera")
                return
            }
        }
        val cam = camera!!
        var i = 0
        var success = false
        while (!success && i < 3) {
            i++
            try {
                cam.setPreviewTexture(SurfaceTexture(0))
                cam.startPreview()
                cam.takePicture(null, null, jpgCallback)
                success = true
            } catch (e: IOException) {
                println("Exception $e")
            }
        }
        if (!success) {
            object : CountDownTimer(1000, 0) {
                override fun onTick(millisUntilFinished: Long) {}

                override fun onFinish() {
                    capture()
                }
            }.start()
        }
    }

    fun askForConfig() {
        if (!capturing) {
            Thread {
                Looper.prepare()
                println("Ask for config thread")
                askForConfig_()
            }.start()
        }
    }

    private fun askForConfig_() {
        if (Helpers.shouldCapture(preferences)) {
            println("CAPTURE from ask")
            capture()
        }
        // Ask for config again in 5 seconds
        object : CountDownTimer(5000, 5000) {
            override fun onTick(millisUntilFinished: Long) {}

            override fun onFinish() {
                askForConfig_()
            }
        }.start()
        val request =
            JsonObjectRequest(Request.Method.PUT,
                Helpers.backendUrl(
                    context,
                    PreferenceManager.getDefaultSharedPreferences(context)
                ) + "/devices/config",
                null,
                Response.Listener<JSONObject> { response ->
                    println(response)
                    try {
                        updateConfig(response)
                    } catch (e: JSONException) {
                        // err
                    }
                },
                Response.ErrorListener { error ->
                    println(error)
                }
            )
        val h = preferences.getString(
            context.getString(R.string.prefs_access_token), "NOTOKEN"
        )
        request.headers = mapOf("Authorization" to "Bearer $h")
        API.getInstance(context).addToRequestQueue(request)
    }

    private fun updateConfig(json: JSONObject) {
        val config = json.getJSONObject("data").getJSONObject("config")
        with(preferences.edit()) {
            Helpers.updateConfig(config, this, "shared_config_")
            commit()
        }
    }

    private val jpgCallback = Camera.PictureCallback { data, camera ->
        Log.i(loggerName, "JPG Callback")
        if (capturing) {
            // schedule another take
            object : CountDownTimer(2000, 2000) {
                override fun onTick(millisUntilFinished: Long) {}

                override fun onFinish() {
                    capture()
                }
            }.start()
        }
        // Upload
        // https://github.com/DWorkS/VolleyPlus
        val request =
            if (Helpers.shouldCapture(preferences)) {
                // Save the file
                // https://developer.android.com/training/data-storage/app-specific
                val fileName = "capture_${System.currentTimeMillis()}.jpg"
                val file = File(context.filesDir, fileName)
                file.writeBytes(data)
                val upload =SimpleMultiPartRequest(
                    Request.Method.POST, Helpers.backendUrl(context, preferences) + "/capture",
                    Response.Listener { response ->
                        Log.i(loggerName, "REQUEST SUCCESSFUL")
                        file.delete()
                        println(response)
                        try {
                            updateConfig(JSONObject(response))
                        } catch (e: JSONException) {
                            // err
                        }
                    },
                    Response.ErrorListener { error ->
                        file.delete()
                        Log.w(loggerName, "REQUEST FAILED")
                        println(error)
                        println(error.networkResponse.data)
                    })
                upload.addFile(fileName, file.absolutePath)
            } else {
                JsonObjectRequest(Request.Method.PUT,
                    Helpers.backendUrl(
                        context,
                        PreferenceManager.getDefaultSharedPreferences(context)
                    ) + "/devices/config",
                    null,
                    Response.Listener<JSONObject> { response ->
                        println(response)
                        try {
                            updateConfig(response)
                        } catch (e: JSONException) {
                            // err
                        }
                    },
                    Response.ErrorListener { error ->
                        println(error)
                    }
                )
            }
        val h = preferences.getString(
            context.getString(R.string.prefs_access_token), "NOTOKEN"
        )
        request.headers = mapOf("Authorization" to "Bearer $h")
        API.getInstance(context).addToRequestQueue(request)
    }

    /**
     * Captures a single image if not capturing continuously.
     */
    fun capturePicture() {
        if (!capturing) {
            capture()
        }
    }

    fun continuousCaptureOn() {
        capturing = true
        capture()
    }

    fun continuousCaptureOff() {
        capturing = false
    }


}