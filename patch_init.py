import re

with open('location.html', 'r') as f:
    content = f.read()

# Replace initApp to setup Firebase listeners properly and remove loadLocationData call
initAppOld = """        function initApp() {
            const savedProfile = localStorage.getItem('togetherProfile');
            if (!savedProfile) {
                showNotification('Please set up your profile first');
                setTimeout(() => window.location.href = 'index.html', 2000);
                return;
            }

            localUser = JSON.parse(savedProfile);

            document.getElementById('personalGistSection').style.display = 'none';

            initMap();
            loadLocationData();

            // Subscribe to partner's location via Firebase
            const partnerName = getPartnerName();
            const myUserName = getCurrentUser();
            if (partnerName) {
                const partnerLocationRef = db.ref(`locations/${partnerName}`);
                partnerLocationRef.on('value', (snapshot) => {
                    const location = snapshot.val();
                    if (location && location.lat && location.lng) {
                        if (!globalState[partnerName]) globalState[partnerName] = {};
                        globalState[partnerName].location = location; // For compatibility with existing distance logic if any

                        updatePartnerLocationDisplay(location);

                        // We also need my location to update distance and map
                        db.ref(`locations/${myUserName}`).once('value', (mySnapshot) => {
                            const myLoc = mySnapshot.val();
                            if (myLoc) {
                                if (!globalState[myUserName]) globalState[myUserName] = {};
                                globalState[myUserName].location = myLoc;
                            }
                            updateMapMarkers(myLoc, location);
                            if (myLoc) {
                                calculateDistance(myLoc, location);
                            }
                        });
                    }
                });

                // Listen for force update flag
                const forceUpdateRef = db.ref(`forceUpdate/${myUserName}`);
                forceUpdateRef.on('value', (snapshot) => {
                    if (snapshot.val() === true || (snapshot.val() && snapshot.val().requestId !== -1)) {
                        // Clear the flag and trigger location fetch
                        forceUpdateRef.set({ requestId: -1, timestamp: Date.now() });
                        updateLocation();
                    }
                });
            }

            // Setup intervals based on trip mode
            setupIntervals();
            startTimerUpdate();
        }"""

initAppNew = """        function initApp() {
            const savedProfile = localStorage.getItem('togetherProfile');
            if (!savedProfile) {
                showNotification('Please set up your profile first');
                setTimeout(() => window.location.href = 'index.html', 2000);
                return;
            }

            localUser = JSON.parse(savedProfile);
            document.getElementById('personalGistSection').style.display = 'none';
            initMap();

            const myUserName = getCurrentUser();
            const partnerName = getPartnerName();

            // Set up listener for My Location
            db.ref(`locations/${myUserName}`).on('value', (snapshot) => {
                const myState = snapshot.val() || {};

                if (!globalState[myUserName]) globalState[myUserName] = {};
                globalState[myUserName] = { ...myState, location: myState };

                if (myState.closestDistance) {
                    document.getElementById('closestDistance').textContent = myState.closestDistance.toFixed(2) + ' km';
                }
                if (myState.furthestDistance) {
                    document.getElementById('furthestDistance').textContent = myState.furthestDistance.toFixed(2) + ' km';
                }

                if (myState.lat && myState.lng) {
                    updateUserLocationDisplay(myState);
                }

                const partnerState = globalState[partnerName] && globalState[partnerName].location;
                if (partnerState && partnerState.lat && partnerState.lng) {
                    if (myState.lat && myState.lng) {
                        calculateDistance(myState, partnerState);
                        updateMapMarkers(myState, partnerState);
                    }
                } else {
                     updateMapMarkers(myState, null);
                }
            });

            // Set up listener for Partner Location
            if (partnerName) {
                db.ref(`locations/${partnerName}`).on('value', (snapshot) => {
                    const partnerState = snapshot.val();
                    if (partnerState && partnerState.lat && partnerState.lng) {
                        if (!globalState[partnerName]) globalState[partnerName] = {};
                        globalState[partnerName] = { ...partnerState, location: partnerState };

                        updatePartnerLocationDisplay(partnerState);

                        const myState = globalState[myUserName] && globalState[myUserName].location;
                        if (myState && myState.lat && myState.lng) {
                            calculateDistance(myState, partnerState);
                            updateMapMarkers(myState, partnerState);
                        } else {
                            updateMapMarkers(null, partnerState);
                        }
                    }
                });

                // Listen for force update flag
                const forceUpdateRef = db.ref(`forceUpdate/${myUserName}`);
                forceUpdateRef.on('value', (snapshot) => {
                    if (snapshot.val() === true || (snapshot.val() && snapshot.val().requestId !== -1)) {
                        // Clear the flag and trigger location fetch
                        forceUpdateRef.set({ requestId: -1, timestamp: Date.now() });
                        updateLocation();
                    }
                });
            }

            // Set up listener for History
            db.ref(`history/${myUserName}`).limitToLast(5).on('value', (snapshot) => {
                const historyData = snapshot.val() || {};
                const historyArray = Object.values(historyData).sort((a, b) => new Date(a.timestamp) - new Date(b.timestamp));
                updateLocationHistory(historyArray);
            });

            // Setup intervals based on trip mode
            setupIntervals();
            startTimerUpdate();
        }"""
content = content.replace(initAppOld, initAppNew)

# Remove loadLocationData
loadDataOld = """        async function loadLocationData() {
            try {
                const myUserName = getCurrentUser();
                const partnerName = getPartnerName();

                // Load from Firebase
                const myLocationSnapshot = await db.ref(`locations/${myUserName}`).once('value');
                const myState = myLocationSnapshot.val() || {};

                const partnerLocationSnapshot = await db.ref(`locations/${partnerName}`).once('value');
                const partnerState = partnerLocationSnapshot.val() || {};

                // Update globalState for existing logic
                globalState[myUserName] = { ...myState, location: myState };
                globalState[partnerName] = { ...partnerState, location: partnerState };

                if (myState.closestDistance) {
                    document.getElementById('closestDistance').textContent = myState.closestDistance.toFixed(2) + ' km';
                }
                if (myState.furthestDistance) {
                    document.getElementById('furthestDistance').textContent = myState.furthestDistance.toFixed(2) + ' km';
                }

                if (partnerState.lat && partnerState.lng) {
                    updatePartnerLocationDisplay(partnerState);
                }

                if (myState.lat && myState.lng) {
                    updateUserLocationDisplay(myState);
                }

                if (myState.lat && partnerState.lat) {
                    calculateDistance(myState, partnerState);
                }

                updateMapMarkers(myState, partnerState);

                // Load history
                const historySnapshot = await db.ref(`history/${myUserName}`).limitToLast(5).once('value');
                const historyData = historySnapshot.val() || {};
                const historyArray = Object.values(historyData).sort((a, b) => new Date(a.timestamp) - new Date(b.timestamp));
                updateLocationHistory(historyArray);

            } catch (error) {
                console.error('Error loading location data:', error);
                showNotification('Error loading location data');
            }
        }"""

content = content.replace(loadDataOld, "")

with open('location.html', 'w') as f:
    f.write(content)
