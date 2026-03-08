import time
import json
from playwright.sync_api import sync_playwright

def verify_dashboard():
    with sync_playwright() as p:
        browser = p.chromium.launch(headless=True, args=['--disable-web-security'])
        context = browser.new_context(ignore_https_errors=True)
        page = context.new_page()

        # Disable navigation to index.html from auth-check
        context.route("**/auth-check.js", lambda route: route.fulfill(body="console.log('auth disabled');", status=200))

        # We must intercept the fetch to the API to simulate a successful load
        def mock_api(route):
            mock_data = {
                "Jonas": {"studyLogs": []},
                "Owami": {"studyLogs": []}
            }
            route.fulfill(status=200, json=mock_data)

        context.route("**/api/couple", mock_api)

        page.add_init_script("""
            const mock_profile = { "name": "Jonas", "partner": {"name": "Owami"} };
            localStorage.setItem('togetherProfile', JSON.stringify(mock_profile));
            localStorage.setItem('together_auth_token', 'mock_token');
        """)

        # Now go to study dashboard
        page.goto("http://localhost:8000/study-dashboard.html")

        # Wait a bit for charts to render and API to try to fetch
        time.sleep(3)

        # Check if we were redirected
        print("Current URL:", page.url)

        # Do NOT force display of the content block manually. Let the page load itself.
        # This will verify that the loading spinner hides and the chart renders without errors.
        time.sleep(2)

        # Click through the timeframes to test UI toggling
        page.click("id=time-month")
        time.sleep(1)

        page.screenshot(path="verification_month_fixed.png", full_page=True)

        page.click("id=time-year")
        time.sleep(1)

        page.screenshot(path="verification_year_fixed.png", full_page=True)

        page.click("id=time-week")
        time.sleep(1)
        page.screenshot(path="verification_week_fixed.png", full_page=True)

        browser.close()

if __name__ == "__main__":
    verify_dashboard()
