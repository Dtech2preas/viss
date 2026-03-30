from playwright.sync_api import sync_playwright
import time
import json

def verify_location_page():
    with sync_playwright() as p:
        browser = p.chromium.launch(headless=True, args=['--disable-web-security'])
        context = browser.new_context(ignore_https_errors=True)
        page = context.new_page()

        # Mock auth
        profile_data = {
            "name": "jonas",
            "partner": {
                "name": "owami"
            }
        }

        # Inject script to set auth and mock API response
        page.add_init_script(f"""
            localStorage.setItem('togetherProfile', '{json.dumps(profile_data)}');
            localStorage.setItem('together_auth_token', 'mock_token');

            // Mock window.authenticatedFetch to return dummy globalState
            window.authenticatedFetch = async function(url, options) {{
                console.log("Mock fetched:", url);
                return {{
                    ok: true,
                    json: async () => ({{
                        jonas: {{
                            location: {{ lat: -33.9249, lng: 18.4241 }} // Cape Town
                        }},
                        owami: {{
                            location: {{ lat: -26.2041, lng: 28.0473 }} // Johannesburg
                        }}
                    }})
                }};
            }};
        """)

        # Block actual auth check redirect
        page.route('**/auth-check.js', lambda route: route.fulfill(body=''))

        # Go to location page
        page.goto('http://localhost:8000/location.html')

        # Wait for map to render
        page.wait_for_selector('#map')
        time.sleep(2) # Give leaflet time to load tiles

        # The checkbox has opacity: 0 and width/height 0 in the CSS (.toggle-switch input)
        # So we evaluate JS to check it directly
        page.evaluate("document.getElementById('tripToggle').checked = true; handleTripToggle();")
        time.sleep(2) # Give time for route to be drawn

        page.screenshot(path='location_verified.png', full_page=True)

        browser.close()

if __name__ == '__main__':
    verify_location_page()