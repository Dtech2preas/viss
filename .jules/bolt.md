## 2024-05-18 - [Optimization] InnerHTML loop concatenation
**Learning:** Found O(n^2) DOM manipulations due to `element.innerHTML += string` within a loop in files like `coupons.html`. Doing this parses the DOM and rebuilds it every iteration.
**Action:** Replaced `.innerHTML +=` inside loops with a local string variable `let html = ''`, appending to it, and setting `innerHTML` outside the loop.
