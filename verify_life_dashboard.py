from playwright.sync_api import sync_playwright
import time
import json

with sync_playwright() as p:
    browser = p.chromium.launch(args=['--disable-web-security'])
    context = browser.new_context(ignore_https_errors=True)
    page = context.new_page()

    page.on("console", lambda msg: print(f"Browser console ({msg.type}): {msg.text}"))

    # Intercept auth-check to avoid redirecting away
    page.route('**/auth-check.js', lambda r: r.fulfill(body=''))

    # Get dynamic "today" date
    from datetime import datetime
    today_str = datetime.now().strftime('%Y-%m-%d')
    today_ms = int(datetime.now().timestamp() * 1000)

    mock_couple_data = {
        "Jonas": {
            "lifeStatsLogs": [
                {"activity": "sleep", "date": today_str, "startTime": today_ms - 28800000, "endTime": today_ms, "durationSeconds": 28800},
                {"activity": "dtech", "date": today_str, "startTime": today_ms - 36000000, "endTime": today_ms - 28800000, "durationSeconds": 7200},
                {"activity": "owami", "date": "2026-03-11", "startTime": today_ms - 86400000, "endTime": today_ms - 80000000, "durationSeconds": 10800}
            ],
            "activeLifeActivity": {"activity": "stop"}
        }
    }

    page.route('**/api/couple', lambda route: route.fulfill(status=200, json=mock_couple_data))

    mock_fetch = """
    window.authenticatedFetch = async function(url, options) {
        return fetch(url, options);
    };
    """
    page.add_init_script(mock_fetch)

    # Go to blank local domain to set localStorage
    page.goto('http://localhost:8000')
    page.evaluate("localStorage.setItem('togetherProfile', JSON.stringify({name: 'Jonas'}));")
    page.evaluate("localStorage.setItem('together_auth_token', 'mock_token');")

    # Now go to the dashboard
    page.goto('http://localhost:8000/life-dashboard.html')

    # Wait for the charts and data to load
    try:
        page.wait_for_selector('#dashboardContent', state='visible', timeout=10000)
    except Exception as e:
        print("Wait for dashboardContent failed!")
        page.screenshot(path='life-dashboard-error.png', full_page=True)
        raise e

    page.wait_for_timeout(2000)

    # Click "This Week" filter to show more data in the graphs
    page.click('#btn-week')
    page.wait_for_timeout(1000)

    page.screenshot(path='life-dashboard-updated.png', full_page=True)

    browser.close()