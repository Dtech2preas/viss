package com.together.app

import android.app.Application
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions

class MyApp : Application() {
    override fun onCreate() {
        super.onCreate()
        if (FirebaseApp.getApps(this).isEmpty()) {
            val options = FirebaseOptions.Builder()
                .setApiKey("AIzaSyBlHyneuosm2zVsAVF_QPKNE5SsWNDUMyc")
                .setApplicationId("1:101292842193:web:d12190895fa7a6b330b9f0")
                .setDatabaseUrl("https://dtech-75e26-default-rtdb.firebaseio.com")
                .setProjectId("dtech-75e26")
                .build()
            FirebaseApp.initializeApp(this, options)
        }
    }
}
