import re

def main():
    with open("app/src/main/java/com/together/app/CoupleService.kt", 'r') as f:
        content = f.read()

    # 1. Imports
    imports_search = """import android.location.Location
import android.os.Bundle
import android.os.PowerManager
import android.app.AlarmManager
import android.os.Looper
import android.annotation.SuppressLint
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationResult"""

    imports_replace = """import android.location.Location
import android.os.Bundle
import android.os.PowerManager
import android.app.AlarmManager
import android.os.Looper
import android.annotation.SuppressLint
import android.os.Handler
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationResult
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener"""

    content = content.replace(imports_search, imports_replace)

    # 2. Variables
    vars_search = """    private val client = OkHttpClient.Builder()
        .readTimeout(0, java.util.concurrent.TimeUnit.MILLISECONDS) // SSE needs 0 timeout
        .build()
    private var permanentWakeLock: PowerManager.WakeLock? = null
    private var forceUpdateEventSource: EventSource? = null"""

    vars_replace = """    private val client = OkHttpClient()
    private var permanentWakeLock: PowerManager.WakeLock? = null
    private var forceUpdateListener: ValueEventListener? = null
    private var firebaseDb: FirebaseDatabase? = null
    private var forceUpdateRef: com.google.firebase.database.DatabaseReference? = null
    private var heartbeatHandler: Handler? = null"""

    content = content.replace(vars_search, vars_replace)

    # 3. onStartCommand
    start_search = """        if (!isRunning) {

            isRunning = true

            startPolling()
        }"""

    start_replace = """        if (!isRunning) {
            isRunning = true
            initFirebase()
            startPolling()
        }"""

    content = content.replace(start_search, start_replace)

    # 4. startPolling
    poll_search = """    private fun startPolling() {
        startContinuousLocationUpdates()
        thread {
            startForceUpdateListener()
            pollForUpdates()
        }
    }"""

    poll_replace = """    private fun initFirebase() {
        if (FirebaseApp.getApps(applicationContext).isEmpty()) {
            val options = FirebaseOptions.Builder()
                .setApiKey("AIzaSyBlHyneuosm2zVsAVF_QPKNE5SsWNDUMyc")
                .setApplicationId("1:101292842193:web:d12190895fa7a6b330b9f0")
                .setDatabaseUrl("https://dtech-75e26-default-rtdb.firebaseio.com")
                .setProjectId("dtech-75e26")
                .build()
            FirebaseApp.initializeApp(applicationContext, options)
        }
        firebaseDb = FirebaseDatabase.getInstance()
    }

    private fun startPolling() {
        startContinuousLocationUpdates()
        startForceUpdateListener()
        startHeartbeat()
        thread {
            pollForUpdates()
        }
    }"""

    content = content.replace(poll_search, poll_replace)

    with open("app/src/main/java/com/together/app/CoupleService.kt", 'w') as f:
        f.write(content)

if __name__ == "__main__":
    main()
