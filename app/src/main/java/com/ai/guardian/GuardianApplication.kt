package com.ai.guardian

import android.app.Application
import android.os.StrictMode
import com.ai.guardian.di.AppContainer
import com.ai.guardian.di.DefaultAppContainer

class GuardianApplication : Application() {
    lateinit var container: AppContainer

    override fun onCreate() {
        super.onCreate()
        
        /* if (BuildConfig.DEBUG) {
            StrictMode.setThreadPolicy(
                StrictMode.ThreadPolicy.Builder()
                    .detectAll()
                    .penaltyLog()
                    .build()
            )
            StrictMode.setVmPolicy(
                StrictMode.VmPolicy.Builder()
                    .detectAll()
                    .penaltyLog()
                    .build()
            )
        } */
        
        container = DefaultAppContainer(this)
        android.util.Log.d("GuardianAI_Phase1", "[Init] GuardianApplication onCreate() finished. Container initialized.")
    }
}
