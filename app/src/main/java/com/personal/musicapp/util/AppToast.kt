package com.personal.musicapp.util

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.Toast

object AppToast {
    private var currentToast: Toast? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    fun show(context: Context, message: CharSequence, duration: Int = Toast.LENGTH_SHORT) {
        val appContext = context.applicationContext
        if (Looper.myLooper() == Looper.getMainLooper()) {
            showInternal(appContext, message, duration)
        } else {
            mainHandler.post {
                showInternal(appContext, message, duration)
            }
        }
    }

    private fun showInternal(appContext: Context, message: CharSequence, duration: Int) {
        currentToast?.cancel()
        currentToast = Toast.makeText(appContext, message, duration).apply {
            show()
        }
    }
}
