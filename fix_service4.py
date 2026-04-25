import re

def main():
    with open("app/src/main/java/com/together/app/CoupleService.kt", 'r') as f:
        content = f.read()

    # 6. pollForUpdates
    poll_search = """                // Check for force location update flag and isTripMode in Firebase
                try {
                    val locRef = Request.Builder()
                        .url("$firebaseUrl/locations/$localUserName.json")
                        .build()
                    val locRes = client.newCall(locRef).execute()
                    if (locRes.isSuccessful) {
                        val locBody = locRes.body?.string()
                        if (locBody != null && locBody != "null") {
                            val locJson = JSONObject(locBody)
                            isTripMode = locJson.optBoolean("isTripMode", false)
                        }
                    }

                    val forceReq = Request.Builder()
                        .url("$firebaseUrl/forceUpdate/$localUserName.json")
                        .build()
                    val forceRes = client.newCall(forceReq).execute()
                    if (forceRes.isSuccessful) {
                        val forceBody = forceRes.body?.string()
                        if (forceBody == "true") {
                            shouldUpdateLocation = true
                        }
                    }
                } catch (e: Exception) {
                    Log.e("CoupleService", "Failed to check forceUpdate/tripMode", e)
                }"""

    poll_replace = """                // Check for force location update flag and isTripMode in Firebase
                try {
                    val locRef = Request.Builder()
                        .url("$firebaseUrl/locations/$localUserName.json")
                        .build()
                    val locRes = client.newCall(locRef).execute()
                    if (locRes.isSuccessful) {
                        val locBody = locRes.body?.string()
                        if (locBody != null && locBody != "null") {
                            val locJson = JSONObject(locBody)
                            isTripMode = locJson.optBoolean("isTripMode", false)
                        }
                    }

                    val forceReq = Request.Builder()
                        .url("$firebaseUrl/forceUpdate/$localUserName.json")
                        .build()
                    val forceRes = client.newCall(forceReq).execute()
                    if (forceRes.isSuccessful) {
                        val forceBody = forceRes.body?.string()
                        if (forceBody != null && forceBody != "null") {
                            val forceJson = JSONObject(forceBody)
                            if (forceJson.has("requestId")) {
                                val forceReqId = forceJson.optLong("requestId", -1L)
                                val lastReqId = sharedPref.getLong("lastForceUpdateReqId", -1L)
                                if (forceReqId != -1L && forceReqId != lastReqId) {
                                    sharedPref.edit().putLong("lastForceUpdateReqId", forceReqId).apply()
                                    shouldUpdateLocation = true
                                }
                            } else if (forceBody == "true") {
                                shouldUpdateLocation = true
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e("CoupleService", "Failed to check forceUpdate/tripMode", e)
                }

                // Fetch Partner Location and broadcast to UI
                try {
                    val partnerLocReq = Request.Builder().url("$firebaseUrl/locations/$partnerName.json").build()
                    val partnerLocRes = client.newCall(partnerLocReq).execute()
                    if (partnerLocRes.isSuccessful) {
                        val locBody = partnerLocRes.body?.string()
                        if (locBody != null && locBody != "null") {
                            val locJson = JSONObject(locBody)
                            val lat = locJson.optDouble("lat", Double.NaN)
                            val lng = locJson.optDouble("lng", Double.NaN)
                            val lastUpdated = locJson.optLong("lastUpdated", 0L)

                            val lastBroadcastedUpdate = sharedPref.getLong("lastBroadcastedPartnerLocation", 0L)
                            if (lastUpdated > lastBroadcastedUpdate || lastBroadcastedUpdate == 0L) {
                                val intent = Intent("PARTNER_LOCATION_UPDATE")
                                intent.putExtra("lat", lat)
                                intent.putExtra("lng", lng)
                                intent.putExtra("timestamp", lastUpdated)
                                sendBroadcast(intent)

                                sharedPref.edit().putLong("lastBroadcastedPartnerLocation", lastUpdated).apply()
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e("CoupleService", "Failed to broadcast partner location", e)
                }"""

    content = content.replace(poll_search, poll_replace)

    with open("app/src/main/java/com/together/app/CoupleService.kt", 'w') as f:
        f.write(content)

if __name__ == "__main__":
    main()
