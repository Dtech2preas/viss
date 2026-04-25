import re

def main():
    with open("app/src/main/java/com/together/app/CoupleService.kt", 'r') as f:
        content = f.read()

    # Apply the startPolling properly
    poll_search = """    private fun startPolling() {
        startContinuousLocationUpdates()
        thread {
            startForceUpdateListener()
            pollForUpdates()
        }
    }"""

    poll_replace = """    private fun startPolling() {
        startContinuousLocationUpdates()
        startForceUpdateListener()
        startHeartbeat()
        thread {
            pollForUpdates()
        }
    }"""

    content = content.replace(poll_search, poll_replace)

    # 8. forceUpdateEventSource remaining
    listener_search = """            // Cancel any existing connection
            forceUpdateEventSource?.cancel()

            val request = Request.Builder()
                .url(sseUrl)
                .addHeader("Accept", "text/event-stream")
                .build()

            val listener = object : EventSourceListener() {
                override fun onOpen(eventSource: EventSource, response: Response) {
                    Log.d("CoupleService", "SSE Connected to Firebase")
                }

                override fun onEvent(eventSource: EventSource, id: String?, type: String?, data: String) {
                    Log.d("CoupleService", "SSE Event: $data")
                    // data from Firebase realtime db typically looks like: {"path":"/","data":true}
                    if (data.contains("true")) {
                        Log.d("CoupleService", "Force update triggered via SSE!")
                        val authToken = sharedPref.getString("together_auth_token", "") ?: ""

                        // We fetch the current state to pass it down
                        thread {
                            try {
                                val stateReq = Request.Builder()
                                    .url(apiUrl)
                                    .addHeader("Authorization", "Bearer $authToken")
                                    .build()
                                val stateRes = client.newCall(stateReq).execute()
                                if (stateRes.isSuccessful) {
                                    val bodyStr = stateRes.body?.string()
                                    if (!bodyStr.isNullOrEmpty()) {
                                        val globalState = JSONObject(bodyStr)
                                        val myState = globalState.optJSONObject(localUserName) ?: JSONObject()
                                        fetchAndPushLocation(localUserName, authToken, myState)
                                    }
                                }
                            } catch (e: Exception) {
                                Log.e("CoupleService", "Failed fetching state for force update", e)
                            }
                        }
                    }
                }

                override fun onClosed(eventSource: EventSource) {
                    Log.d("CoupleService", "SSE Closed")
                    // Attempt reconnect after delay if still running
                    if (isRunning) {
                        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                            startForceUpdateListener()
                        }, 5000)
                    }
                }

                override fun onFailure(eventSource: EventSource, t: Throwable?, response: Response?) {
                    Log.e("CoupleService", "SSE Failure", t)
                    // Attempt reconnect after delay if still running
                    if (isRunning) {
                        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                            startForceUpdateListener()
                        }, 5000)
                    }
                }
            }

            forceUpdateEventSource = EventSources.createFactory(client).newEventSource(request, listener)"""

    listener_replace = """            if (forceUpdateRef != null && forceUpdateListener != null) {
                forceUpdateRef?.removeEventListener(forceUpdateListener!!)
            }

            forceUpdateRef = firebaseDb?.getReference("forceUpdate/$localUserName")

            forceUpdateListener = object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    try {
                        val forceReqId = snapshot.child("requestId").getValue(Long::class.java)

                        var shouldUpdate = false
                        if (forceReqId != null && forceReqId != -1L) {
                            val lastReqId = sharedPref.getLong("lastForceUpdateReqId", -1L)
                            if (forceReqId != lastReqId) {
                                sharedPref.edit().putLong("lastForceUpdateReqId", forceReqId).apply()
                                shouldUpdate = true
                            }
                        } else {
                            val legacyValue = snapshot.getValue(Boolean::class.java)
                            if (legacyValue == true) {
                                shouldUpdate = true
                            }
                        }

                        if (shouldUpdate) {
                            Log.d("CoupleService", "Force update triggered via Firebase Listener!")
                            val authToken = sharedPref.getString("together_auth_token", "") ?: ""

                            thread {
                                try {
                                    val stateReq = Request.Builder()
                                        .url(apiUrl)
                                        .addHeader("Authorization", "Bearer $authToken")
                                        .build()
                                    val stateRes = client.newCall(stateReq).execute()
                                    if (stateRes.isSuccessful) {
                                        val bodyStr = stateRes.body?.string()
                                        if (!bodyStr.isNullOrEmpty()) {
                                            val globalState = JSONObject(bodyStr)
                                            val myState = globalState.optJSONObject(localUserName) ?: JSONObject()
                                            fetchAndPushLocation(localUserName, authToken, myState)
                                        }
                                    }
                                } catch (e: Exception) {
                                    Log.e("CoupleService", "Failed fetching state for force update", e)
                                }
                            }
                        }
                    } catch (e: Exception) {
                        Log.e("CoupleService", "Error in force update listener", e)
                    }
                }

                override fun onCancelled(error: DatabaseError) {
                    Log.e("CoupleService", "Firebase listener cancelled", error.toException())
                }
            }

            forceUpdateRef?.addValueEventListener(forceUpdateListener!!)"""

    content = content.replace(listener_search, listener_replace)

    with open("app/src/main/java/com/together/app/CoupleService.kt", 'w') as f:
        f.write(content)

if __name__ == "__main__":
    main()
