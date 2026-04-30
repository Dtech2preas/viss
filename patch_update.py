import re

with open('location.html', 'r') as f:
    content = f.read()

updateLocOld = """        async function updateLocation() {
            if (navigator.geolocation) {
                const options = {
                    enableHighAccuracy: true,
                    timeout: 20000,
                    maximumAge: 0
                };

                navigator.geolocation.getCurrentPosition(
                    async position => {
                        const newLocation = {
                            lat: position.coords.latitude,
                            lng: position.coords.longitude,
                            timestamp: Date.now(),
                            isTripMode: isTripMode
                        };

                        const userName = getCurrentUser();

                        // Update current location in Firebase
                        // We preserve closest/furthest distances by reading them first or using a multi-path update
                        const myLocRef = db.ref(`locations/${userName}`);
                        const snapshot = await myLocRef.once('value');
                        const currentData = snapshot.val() || {};

                        const updates = {
                            ...newLocation,
                            closestDistance: currentData.closestDistance || null,
                            furthestDistance: currentData.furthestDistance || null,
                            wasFarApart: currentData.wasFarApart || false,
                            timesTogether: currentData.timesTogether || 0,
                            lastTimeTogether: currentData.lastTimeTogether || null
                        };

                        await myLocRef.set(updates);

                        // Update globalState
                        if (!globalState[userName]) globalState[userName] = {};
                        globalState[userName] = { ...updates, location: updates };

                        // Add to history
                        db.ref(`history/${userName}`).push(newLocation);

                        updateUserLocationDisplay({
                            ...newLocation,
                            timestamp: new Date(newLocation.timestamp).toISOString()
                        });

                        try {
                            const address = await getAddressFromCoords(newLocation.lat, newLocation.lng);
                            document.getElementById('userAddress').textContent = address;
                            showNotification('Location updated successfully');
                        } catch (error) {
                            console.error('Error updating address:', error);
                        }

                        // Get partner location for distance calculation
                        const partnerName = getPartnerName();
                        const partnerSnapshot = await db.ref(`locations/${partnerName}`).once('value');
                        const partnerState = partnerSnapshot.val();

                        if (partnerState && partnerState.lat) {
                            calculateDistance(newLocation, partnerState);
                            updateMapMarkers(newLocation, partnerState);
                        } else {
                            updateMapMarkers(newLocation, null);
                        }

                        // Update history display (fetch last 5)
                        const historySnapshot = await db.ref(`history/${userName}`).limitToLast(5).once('value');
                        const historyData = historySnapshot.val() || {};
                        const historyArray = Object.values(historyData).sort((a, b) => b.timestamp - a.timestamp);
                        updateLocationHistory(historyArray);
                    },
                    error => {
                        let errorMessage = "Error getting location";
                        if (error.code === 1) errorMessage = "Location permission denied by user. Check browser settings.";
                        else if (error.code === 2) errorMessage = "Location position unavailable.";
                        else if (error.code === 3) errorMessage = "Location request timed out.";
                        showNotification(errorMessage);
                    },
                    options
                );
            } else {
                showNotification('Geolocation is not supported by this browser');
            }
        }"""

updateLocNew = """        async function updateLocation() {
            if (navigator.geolocation) {
                const options = {
                    enableHighAccuracy: true,
                    timeout: 20000,
                    maximumAge: 0
                };

                navigator.geolocation.getCurrentPosition(
                    async position => {
                        const newLocation = {
                            lat: position.coords.latitude,
                            lng: position.coords.longitude,
                            timestamp: Date.now(),
                            isTripMode: isTripMode
                        };

                        const userName = getCurrentUser();

                        // Using update allows us to modify only these fields, preserving closestDistance, etc.
                        await db.ref(`locations/${userName}`).update(newLocation);
                        await db.ref(`history/${userName}`).push(newLocation);

                        showNotification('Location updated successfully');
                        // The .on('value') listener will handle updating UI, maps, history, and distances.
                    },
                    error => {
                        let errorMessage = "Error getting location";
                        if (error.code === 1) errorMessage = "Location permission denied by user. Check browser settings.";
                        else if (error.code === 2) errorMessage = "Location position unavailable.";
                        else if (error.code === 3) errorMessage = "Location request timed out.";
                        showNotification(errorMessage);
                    },
                    options
                );
            } else {
                showNotification('Geolocation is not supported by this browser');
            }
        }"""

content = content.replace(updateLocOld, updateLocNew)

with open('location.html', 'w') as f:
    f.write(content)
