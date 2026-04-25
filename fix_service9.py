import sys

def main():
    with open("app/src/main/java/com/together/app/CoupleService.kt", 'r') as f:
        content = f.read()

    # Clean up sseUrl
    sse_search = """            val sseUrl = "$firebaseUrl/forceUpdate/$localUserName.json\""""
    sse_replace = """            // sseUrl no longer needed"""
    content = content.replace(sse_search, sse_replace)

    with open("app/src/main/java/com/together/app/CoupleService.kt", 'w') as f:
        f.write(content)

if __name__ == "__main__":
    main()
