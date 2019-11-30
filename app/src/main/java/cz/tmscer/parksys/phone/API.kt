package cz.tmscer.parksys.phone

import android.content.Context
import com.android.volley.Request
import com.android.volley.RequestQueue
import com.android.volley.toolbox.Volley

// https://developer.android.com/training/volley/requestqueue.html#singleton
class API constructor(context: Context) {
    companion object {
        @Volatile
        private var INSTANCE: API? = null

        fun getInstance(context: Context) =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: API(context).also {
                    INSTANCE = it
                }
            }
    }
    val requestQueue: RequestQueue by lazy {
        // applicationContext is key, it keeps you from leaking the
        // Activity or BroadcastReceiver if someone passes one in.
        Volley.newRequestQueue(context.applicationContext)
    }
    fun <T> addToRequestQueue(req: Request<T>) {
        println("addToRequestQueue")
        requestQueue.add(req)
    }
}
