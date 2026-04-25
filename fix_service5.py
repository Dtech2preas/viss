import re

def main():
    with open("app/src/main/java/com/together/app/CoupleService.kt", 'r') as f:
        content = f.read()

    # 7. fetchAndPushLocation
    loc_search = """                        val mediaType = "application/json; charset=utf-8".toMediaTypeOrNull()

                        // Check previous location first to calculate far apart correctly
                        var wasFarApartBefore = false"""

    loc_replace = """                        val mediaType = "application/json; charset=utf-8".toMediaTypeOrNull()

                        // Push location
                        val newLocation = JSONObject()
                        newLocation.put("lat", location.latitude)
                        newLocation.put("lng", location.longitude)
                        newLocation.put("speed", location.speed)
                        newLocation.put("accuracy", location.accuracy)
                        newLocation.put("timestamp", System.currentTimeMillis())
                        newLocation.put("lastUpdated", System.currentTimeMillis())

                        // Preserve isTripMode if it exists
                        var isTripMode = false
                        try {
                            val locReq = Request.Builder().url("$firebaseUrl/locations/$userName.json").build()
                            val locRes = client.newCall(locReq).execute()
                            if (locRes.isSuccessful) {
                                val locBody = locRes.body?.string()
                                if (locBody != null && locBody != "null") {
                                    val locJson = JSONObject(locBody)
                                    isTripMode = locJson.optBoolean("isTripMode", false)
                                }
                            }
                        } catch (e: Exception) {}
                        newLocation.put("isTripMode", isTripMode)

                        // Check previous location first to calculate far apart correctly
                        var wasFarApartBefore = false"""

    content = content.replace(loc_search, loc_replace)

    loc2_search = """                        // Always clear force flag after updating
                        val forceReqBody = "false".toRequestBody(mediaType)
                        val forcePostReq = Request.Builder()
                            .url("$firebaseUrl/forceUpdate/$userName.json")
                            .put(forceReqBody)
                            .build()
                        client.newCall(forcePostReq).execute()"""

    loc2_replace = """                        // Always clear force flag after updating
                        val forceReqBody = "{\"requestId\":-1,\"timestamp\":${System.currentTimeMillis()}}".toRequestBody(mediaType)
                        val forcePostReq = Request.Builder()
                            .url("$firebaseUrl/forceUpdate/$userName.json")
                            .put(forceReqBody)
                            .build()
                        client.newCall(forcePostReq).execute()"""

    content = content.replace(loc2_search, loc2_replace)

    # 8. onDestroy
    destroy_search = """        forceUpdateEventSource?.cancel()
        if (permanentWakeLock?.isHeld == true) {"""

    destroy_replace = """        heartbeatHandler?.removeCallbacksAndMessages(null)
        if (forceUpdateRef != null && forceUpdateListener != null) {
            forceUpdateRef?.removeEventListener(forceUpdateListener!!)
        }

        if (permanentWakeLock?.isHeld == true) {"""

    content = content.replace(destroy_search, destroy_replace)

    with open("app/src/main/java/com/together/app/CoupleService.kt", 'w') as f:
        f.write(content)

if __name__ == "__main__":
    main()
