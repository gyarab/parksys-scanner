package cz.tmscer.parksys.phone

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.SurfaceTexture
import android.hardware.Camera
import android.os.CountDownTimer
import android.util.Log
import androidx.preference.PreferenceManager
import com.android.volley.Request
import com.android.volley.Response
import com.android.volley.error.VolleyError
import com.android.volley.request.JsonObjectRequest
import com.android.volley.request.SimpleMultiPartRequest
import cz.tmscer.parksys.phone.models.Status
import org.json.JSONException
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.nio.charset.Charset
import java.util.*
import kotlin.math.max

class Capture(
    private val context: Context,
    private val preferences: SharedPreferences,
    private val onSuccessfulComm: () -> Unit,
    private val onFailedComm: (err: String) -> Unit,
    private val onStatusChange: (status: Status) -> Unit,
    private val onCapture: (data: Bitmap) -> Unit
) {
    private val loggerName = "CAPTURE"
    private var camera: Camera? = null
    private var status = Status.IDLE
    private var captureStart = 0L

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
                captureStart = Date().time
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
        askForConfig_()
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
                Response.Listener { response ->
                    println(response)
                    try {
                        updateConfig(response)
                    } catch (e: JSONException) {
                        if (e.message == null) {
                            this.onFailedComm("Json error while parsing config (config)")
                        } else {
                            this.onFailedComm(e.message!!)
                        }
                    }
                },
                Response.ErrorListener { error ->
                    println(error)
                }
            )
        val h = preferences.getString(
            context.getString(R.string.prefs_access_token), "-"
        )
        request.headers = mapOf("Authorization" to "Bearer $h")
        API.getInstance(context).addToRequestQueue(request)
    }

    private fun updateConfig(json: JSONObject) {
        if (!json.has("data")) return
        val config = json.getJSONObject("data").getJSONObject("config")
        status = if (config.getString("capturing") == "true") {
            Status.CAPTURING
        } else {
            Status.FETCHING_CONFIG
        }
        this.onStatusChange(status)
        with(preferences.edit()) {
            Helpers.updateConfig(config, this, "shared_config_")
            commit()
        }
    }

    private fun handleError(error: VolleyError) {
        Log.w(loggerName, "REQUEST FAILED")
        val errorBody = when {
            error.networkResponse.data == null -> {
                "unreachable"
            }
            error.networkResponse.data.isNotEmpty() -> {
                String(error.networkResponse.data, Charset.forName("utf-8"))
            }
            else -> {
                ""
            }
        }
        val errorDescription = "${error.networkResponse.statusCode}: $errorBody"
        this.onFailedComm(errorDescription)
    }

    private val jpgCallback = Camera.PictureCallback { data, camera ->
        Log.i(loggerName, "JPG Callback")
        if (status != Status.IDLE) {
            // schedule another take
            val currDelay = Date().time - captureStart
            val delay = 500L
            val wouldBeDelay = currDelay - delay
            val actualDelay = max(200, wouldBeDelay)
            println(wouldBeDelay)
            println(actualDelay)
            object : CountDownTimer(actualDelay, actualDelay) {
                override fun onTick(millisUntilFinished: Long) {}

                override fun onFinish() {
                    capture()
                }
            }.start()
        } else if (status === Status.IDLE) {
            // It is a test
            val bitmap = BitmapFactory.decodeByteArray(data, 0, data.size)
            this.onCapture(bitmap)
            return@PictureCallback
        }
        // Upload
        // https://github.com/DWorkS/VolleyPlus
        val request =
            (when (status) {
                Status.CAPTURING -> {
                    val bitmap = BitmapFactory.decodeByteArray(data, 0, data.size)
                    this.onCapture(bitmap)
                    this.onStatusChange(Status.CAPTURING)
                    val fileName = "capture_${System.currentTimeMillis()}.jpg"
                    val file = File(context.filesDir, fileName)


                    val output = ByteArrayOutputStream()
                    val resizeX =
                        preferences.getString("shared_config_resizeX", "1300")?.toInt() ?: 1300
                    val resizeY =
                        preferences.getString("shared_config_resizeY", "1000")?.toInt() ?: 1000
                    // https://stackoverflow.com/questions/10413659/how-to-resize-image-in-android
                    Bitmap.createScaledBitmap(bitmap, resizeX, resizeY, true)
                        .compress(Bitmap.CompressFormat.JPEG, 100, output)
                    // Save the file
                    // https://developer.android.com/training/data-storage/app-specific
                    file.writeBytes(output.toByteArray())
                    val upload = SimpleMultiPartRequest(
                        Request.Method.POST, Helpers.backendUrl(context, preferences) + "/capture",
                        Response.Listener { response ->
                            Log.i(loggerName, "REQUEST SUCCESSFUL")
                            this.onSuccessfulComm()
                            file.delete()
                            println(response)
                            try {
                                updateConfig(JSONObject(response))
                            } catch (e: JSONException) {
                                if (e.message == null) {
                                    this.onFailedComm("Json error while parsing config (capture)")
                                } else {
                                    this.onFailedComm(e.message!!)
                                }
                            }
                        },
                        Response.ErrorListener { error ->
                            file.delete()
                            this.handleError(error)
                        })
                    upload.addFile(fileName, file.absolutePath)
                }
                Status.FETCHING_CONFIG -> {
                    this.onStatusChange(Status.FETCHING_CONFIG)
                    JsonObjectRequest(Request.Method.PUT,
                        Helpers.backendUrl(
                            context,
                            PreferenceManager.getDefaultSharedPreferences(context)
                        ) + "/devices/config",
                        null,
                        Response.Listener<JSONObject> { response ->
                            println(response)
                            this.onSuccessfulComm()
                            try {
                                updateConfig(response)
                            } catch (e: JSONException) {
                                if (e.message == null) {
                                    this.onFailedComm("Json error while parsing config (capture)")
                                } else {
                                    this.onFailedComm(e.message!!)
                                }
                            }
                        },
                        Response.ErrorListener { error -> this.handleError(error) }
                    )
                }
                else -> {
                    println("Status!!")
                    null
                }
            })
                ?: return@PictureCallback
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
        if (status == Status.IDLE) {
            capture()
        }
    }

    fun continuousCaptureOn() {
        status = Status.CAPTURING
        this.onStatusChange(Status.CAPTURING)
        capture()
    }

    fun continuousCaptureOff() {
        status = Status.IDLE
        this.onStatusChange(Status.IDLE)
    }


}