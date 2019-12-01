package cz.tmscer.parksys.phone

import android.content.Context
import android.content.SharedPreferences
import android.graphics.SurfaceTexture
import android.hardware.Camera
import android.os.CountDownTimer
import android.util.Log
import com.android.volley.Request
import com.android.volley.Response
import com.android.volley.request.SimpleMultiPartRequest
import java.io.File

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
        cam.setPreviewTexture(SurfaceTexture(0))
        cam.startPreview()
        cam.takePicture(null, null, jpgCallback)
    }

    private val jpgCallback = Camera.PictureCallback { data, camera ->
        Log.i(loggerName, "JPG Callback")
        if (capturing) {
            // schedule another take
            object : CountDownTimer(1000, 1000) {
                override fun onTick(millisUntilFinished: Long) {}

                override fun onFinish() {
                    capture()
                }
            }.start()
        }
        // Save the file
        // https://developer.android.com/training/data-storage/app-specific
        val fileName = "capture_${System.currentTimeMillis()}.jpg"
        val file = File(context.filesDir, fileName)
        file.writeBytes(data)

        // Upload
        // https://github.com/DWorkS/VolleyPlus
        val uploadRequest = SimpleMultiPartRequest(
            Request.Method.POST, Helpers.backendUrl(context, preferences) + "/capture",
            Response.Listener { response ->
                Log.i(loggerName, "REQUEST SUCCESSFUL")
                println(response)
                // TODO: Update local config
            },
            Response.ErrorListener { error ->
                Log.w(loggerName, "REQUEST FAILED")
                println(error)
                println(error.networkResponse.data)
            })
        val h = preferences.getString(
            context.getString(R.string.prefs_access_token), "NOTOKEN"
        )
        uploadRequest.headers = mapOf("Authentication" to "Bearer $h")
        uploadRequest.addFile(fileName, file.absolutePath)
        API.getInstance(context).addToRequestQueue(uploadRequest)
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