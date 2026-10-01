## 2024-05-18 - [Fix] Prototype Pollution in mergeDeep
**Vulnerability:** Found a prototype pollution vulnerability in the `mergeDeep` function in `worker.js`. The function recursively merges objects without checking if the keys are reserved properties like `__proto__`, `constructor`, or `prototype`.
**Learning:** This codebase uses a custom `mergeDeep` function to merge JSON state payloads from the frontend. Because Cloudflare Workers parse JSON input directly into this merge function, an attacker could inject `__proto__` to alter the global Object prototype, leading to unpredictable behavior or security bypasses across the worker instance.
**Prevention:** Always sanitize keys during recursive object merges by explicitly skipping `__proto__`, `constructor`, and `prototype`.
