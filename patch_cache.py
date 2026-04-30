import re

with open('location.html', 'r') as f:
    content = f.read()

# Add caches globally
cache_vars = """        let hasCelebratedReunited = false;

        // Caches to prevent redundant network requests
        const addressCache = {};
        const weatherCache = {};
        const osrmCache = {};
"""
content = content.replace("        let hasCelebratedReunited = false;", cache_vars)

# Fix getAddressFromCoords
getAddressOld = """        async function getAddressFromCoords(lat, lng) {
            try {
                const response = await fetch(`https://nominatim.openstreetmap.org/reverse?format=json&lat=${lat}&lon=${lng}&zoom=18&addressdetails=1`);
                const data = await response.json();

                if (data && data.display_name) {
                    return data.display_name;
                } else {
                    return "Address not available";
                }
            } catch (error) {
                console.error('Error getting address:', error);
                return "Address not available";
            }
        }"""

getAddressNew = """        async function getAddressFromCoords(lat, lng) {
            const cacheKey = `${lat.toFixed(4)},${lng.toFixed(4)}`;
            if (addressCache[cacheKey]) {
                return addressCache[cacheKey];
            }
            try {
                const response = await fetch(`https://nominatim.openstreetmap.org/reverse?format=json&lat=${lat}&lon=${lng}&zoom=18&addressdetails=1`);
                const data = await response.json();

                if (data && data.display_name) {
                    addressCache[cacheKey] = data.display_name;
                    return data.display_name;
                } else {
                    return "Address not available";
                }
            } catch (error) {
                console.error('Error getting address:', error);
                return "Address not available";
            }
        }"""
content = content.replace(getAddressOld, getAddressNew)

# Fix calculateDistance
calcDistanceOld = """        async function calculateDistance(myLocation, partnerLocation) {
            if (!myLocation || !partnerLocation) return;

            // Fetch distance from OSRM
            try {
                // OSRM requires format: lon,lat;lon,lat
                const osrmUrl = `https://router.project-osrm.org/route/v1/${currentTravelMode}/${myLocation.lng},${myLocation.lat};${partnerLocation.lng},${partnerLocation.lat}?overview=full&geometries=geojson`;

                const response = await fetch(osrmUrl);
                const data = await response.json();"""

calcDistanceNew = """        async function calculateDistance(myLocation, partnerLocation) {
            if (!myLocation || !partnerLocation) return;

            // Fetch distance from OSRM
            try {
                const cacheKey = `${currentTravelMode}_${myLocation.lat.toFixed(4)},${myLocation.lng.toFixed(4)}_${partnerLocation.lat.toFixed(4)},${partnerLocation.lng.toFixed(4)}`;
                let data = osrmCache[cacheKey];

                if (!data) {
                    // OSRM requires format: lon,lat;lon,lat
                    const osrmUrl = `https://router.project-osrm.org/route/v1/${currentTravelMode}/${myLocation.lng},${myLocation.lat};${partnerLocation.lng},${partnerLocation.lat}?overview=full&geometries=geojson`;

                    const response = await fetch(osrmUrl);
                    data = await response.json();

                    if (data.code === 'Ok') {
                        osrmCache[cacheKey] = data;
                    }
                }"""
content = content.replace(calcDistanceOld, calcDistanceNew)

# Fix updatePartnerWeather
weatherOld = """        async function updatePartnerWeather(lat, lng) {
            try {
                // Using Open-Meteo free API
                const url = `https://api.open-meteo.com/v1/forecast?latitude=${lat}&longitude=${lng}&current=temperature_2m,weather_code&timezone=auto`;
                const response = await fetch(url);
                const data = await response.json();

                if (data && data.current) {
                    const temp = data.current.temperature_2m;
                    const code = data.current.weather_code;

                    document.getElementById('weatherTemp').textContent = `${Math.round(temp)}°C`;

                    // Simple weather code mapping (WMO weather interpretation codes)
                    let icon = '☁️';
                    let desc = 'Cloudy';

                    if (code === 0) { icon = '☀️'; desc = 'Clear sky'; }
                    else if (code === 1 || code === 2 || code === 3) { icon = '⛅'; desc = 'Partly cloudy'; }
                    else if (code === 45 || code === 48) { icon = '🌫️'; desc = 'Foggy'; }
                    else if (code >= 51 && code <= 55) { icon = '🌧️'; desc = 'Drizzle'; }
                    else if (code >= 61 && code <= 65) { icon = '🌧️'; desc = 'Rain'; }
                    else if (code >= 71 && code <= 77) { icon = '❄️'; desc = 'Snow'; }
                    else if (code >= 80 && code <= 82) { icon = '🌦️'; desc = 'Rain showers'; }
                    else if (code >= 95 && code <= 99) { icon = '⛈️'; desc = 'Thunderstorm'; }

                    document.getElementById('weatherIcon').textContent = icon;
                    document.getElementById('weatherDesc').textContent = desc;
                }
            } catch (error) {
                console.error("Weather error:", error);
                document.getElementById('weatherDesc').textContent = "Unavailable";
            }
        }"""

weatherNew = """        function renderWeather(temp, code) {
            document.getElementById('weatherTemp').textContent = `${Math.round(temp)}°C`;

            // Simple weather code mapping (WMO weather interpretation codes)
            let icon = '☁️';
            let desc = 'Cloudy';

            if (code === 0) { icon = '☀️'; desc = 'Clear sky'; }
            else if (code === 1 || code === 2 || code === 3) { icon = '⛅'; desc = 'Partly cloudy'; }
            else if (code === 45 || code === 48) { icon = '🌫️'; desc = 'Foggy'; }
            else if (code >= 51 && code <= 55) { icon = '🌧️'; desc = 'Drizzle'; }
            else if (code >= 61 && code <= 65) { icon = '🌧️'; desc = 'Rain'; }
            else if (code >= 71 && code <= 77) { icon = '❄️'; desc = 'Snow'; }
            else if (code >= 80 && code <= 82) { icon = '🌦️'; desc = 'Rain showers'; }
            else if (code >= 95 && code <= 99) { icon = '⛈️'; desc = 'Thunderstorm'; }

            document.getElementById('weatherIcon').textContent = icon;
            document.getElementById('weatherDesc').textContent = desc;
        }

        async function updatePartnerWeather(lat, lng) {
            const cacheKey = `${lat.toFixed(2)},${lng.toFixed(2)}`;
            const cached = weatherCache[cacheKey];

            // Re-fetch after 30 minutes
            if (cached && (Date.now() - cached.timestamp < 30 * 60 * 1000)) {
                renderWeather(cached.temp, cached.code);
                return;
            }

            try {
                // Using Open-Meteo free API
                const url = `https://api.open-meteo.com/v1/forecast?latitude=${lat}&longitude=${lng}&current=temperature_2m,weather_code&timezone=auto`;
                const response = await fetch(url);
                const data = await response.json();

                if (data && data.current) {
                    const temp = data.current.temperature_2m;
                    const code = data.current.weather_code;

                    weatherCache[cacheKey] = {
                        temp: temp,
                        code: code,
                        timestamp: Date.now()
                    };

                    renderWeather(temp, code);
                }
            } catch (error) {
                console.error("Weather error:", error);
                document.getElementById('weatherDesc').textContent = "Unavailable";
            }
        }"""
content = content.replace(weatherOld, weatherNew)

with open('location.html', 'w') as f:
    f.write(content)
