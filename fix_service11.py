import re

def main():
    with open("app/src/main/java/com/together/app/CoupleService.kt", 'r') as f:
        content = f.read()

    perm_search = """    @SuppressLint("MissingPermission")
    private fun fetchAndPushLocation(userName: String, authToken: String, myStateObj: JSONObject) {"""

    perm_replace = """    @SuppressLint("MissingPermission")
    private fun fetchAndPushLocation(userName: String, authToken: String, myStateObj: JSONObject) {
        if (androidx.core.content.ContextCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_FINE_LOCATION) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            return
        }"""

    content = content.replace(perm_search, perm_replace)

    with open("app/src/main/java/com/together/app/CoupleService.kt", 'w') as f:
        f.write(content)

if __name__ == "__main__":
    main()
