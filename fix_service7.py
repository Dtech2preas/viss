import re

def main():
    with open("app/src/main/java/com/together/app/CoupleService.kt", 'r') as f:
        content = f.read()

    # 9. JSON issue
    json_search = """                        // Always clear force flag after updating
                        val forceReqBody = "{\"requestId\":-1,\"timestamp\":${System.currentTimeMillis()}}".toRequestBody(mediaType)
                        val forcePostReq = Request.Builder()
                            .url("$firebaseUrl/forceUpdate/$userName.json")
                            .put(forceReqBody)
                            .build()
                        client.newCall(forcePostReq).execute()"""

    json_replace = """                        // Always clear force flag after updating
                        val ts = System.currentTimeMillis()
                        val forceReqBody = "{\\"requestId\\":-1,\\"timestamp\\":$ts}".toRequestBody(mediaType)
                        val forcePostReq = Request.Builder()
                            .url("$firebaseUrl/forceUpdate/$userName.json")
                            .put(forceReqBody)
                            .build()
                        client.newCall(forcePostReq).execute()"""

    content = content.replace(json_search, json_replace)

    with open("app/src/main/java/com/together/app/CoupleService.kt", 'w') as f:
        f.write(content)

if __name__ == "__main__":
    main()
