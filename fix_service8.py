import re

def main():
    with open("app/src/main/java/com/together/app/CoupleService.kt", 'r') as f:
        content = f.read()

    # Add missing startHeartbeat function
    heartbeat_search = """    private fun startPolling() {"""
    heartbeat_replace = """    private fun startHeartbeat() {
        if (heartbeatHandler == null) {
            heartbeatHandler = Handler(Looper.getMainLooper())
        }
        heartbeatHandler?.postDelayed(object : Runnable {
            override fun run() {
                if (!isRunning) return
                try {
                    firebaseDb?.getReference(".info/connected")?.get()?.addOnCompleteListener { task ->
                        if (task.isSuccessful) {
                            Log.d("CoupleService", "Heartbeat: Firebase connection OK")
                        } else {
                            Log.e("CoupleService", "Heartbeat: Firebase connection check failed")
                            startForceUpdateListener()
                        }
                    }
                } catch (e: Exception) {
                    Log.e("CoupleService", "Heartbeat failed", e)
                }
                heartbeatHandler?.postDelayed(this, 60000)
            }
        }, 60000)
    }

    private fun startPolling() {"""

    content = content.replace(heartbeat_search, heartbeat_replace)

    with open("app/src/main/java/com/together/app/CoupleService.kt", 'w') as f:
        f.write(content)

if __name__ == "__main__":
    main()
