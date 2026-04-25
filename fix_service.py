import sys

def replace_block(filename, search_block, replace_block):
    with open(filename, 'r') as f:
        content = f.read()

    if search_block in content:
        content = content.replace(search_block, replace_block)
        with open(filename, 'w') as f:
            f.write(content)
        print(f"Successfully updated {filename}")
    else:
        print(f"Could not find search block in {filename}")

def main():
    with open("app/src/main/java/com/together/app/CoupleService.kt", 'r') as f:
        content = f.read()

    # We will just copy the entire CoupleService.kt.bak and then apply the Firebase fixes back on top of it.
    with open("app/src/main/java/com/together/app/CoupleService.kt.bak", 'r') as f:
        bak_content = f.read()

    with open("app/src/main/java/com/together/app/CoupleService.kt", 'w') as f:
        f.write(bak_content)

if __name__ == "__main__":
    main()
