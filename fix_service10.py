import re

def main():
    with open("app/src/main/java/com/together/app/CoupleService.kt", 'r') as f:
        content = f.read()

    # Restore fallbacks in fetchAndPushLocation
    fetch_search = """    @SuppressLint("MissingPermission")
    private fun fetchAndPushLocation(userName: String, authToken: String, myStateObj: JSONObject) {
        val fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        try {
            fusedLocationClient.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, CancellationTokenSource().token)
                .addOnSuccessListener { location: Location? ->"""

    fetch_replace = """    @SuppressLint("MissingPermission")
    private fun fetchAndPushLocation(userName: String, authToken: String, myStateObj: JSONObject) {
        val fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        try {
            val locationRequest = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1000L).setMaxUpdates(1).build()

            fusedLocationClient.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, CancellationTokenSource().token)
                .addOnSuccessListener { location: Location? ->
                    if (location != null) {
                        processLocation(location, userName, authToken, myStateObj)
                    } else {
                        // Fallback 1: request location updates
                        val callback = object : LocationCallback() {
                            override fun onLocationResult(result: LocationResult) {
                                result.lastLocation?.let {
                                    processLocation(it, userName, authToken, myStateObj)
                                }
                                fusedLocationClient.removeLocationUpdates(this)
                            }
                        }
                        fusedLocationClient.requestLocationUpdates(locationRequest, callback, Looper.getMainLooper())
                    }
                }
                .addOnFailureListener {
                    // Fallback 2: last known location
                    fusedLocationClient.lastLocation.addOnSuccessListener { location: Location? ->
                        location?.let { processLocation(it, userName, authToken, myStateObj) }
                    }
                }
        } catch (e: Exception) {
            Log.e("CoupleService", "Failed to fetch location", e)
        }
    }

    private fun processLocation(location: Location, userName: String, authToken: String, myStateObj: JSONObject) {"""

    content = content.replace(fetch_search, fetch_replace)

    bracket_search = """                        lastLocationUpdateTime = System.currentTimeMillis()
                    } catch (e: Exception) {
                        Log.e("CoupleService", "Failed to calculate location math", e)
                    }
                }
        } catch (e: Exception) {
            Log.e("CoupleService", "Failed to fetch location", e)
        }
    }"""

    bracket_replace = """                        lastLocationUpdateTime = System.currentTimeMillis()
                    } catch (e: Exception) {
                        Log.e("CoupleService", "Failed to calculate location math", e)
                    }
    }"""

    content = content.replace(bracket_search, bracket_replace)

    with open("app/src/main/java/com/together/app/CoupleService.kt", 'w') as f:
        f.write(content)

if __name__ == "__main__":
    main()
