package dev.timlock.jarvis

import android.app.Application

class JarvisApp : Application() {
    override fun onCreate() {
        super.onCreate()
        AppGraph.start()
    }
}
