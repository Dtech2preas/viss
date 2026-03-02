// Global Authentication Checker for Together Forever App
// This script should be included in the <head> of every HTML file.

const AUTH_TOKEN_KEY = 'together_auth_token';
const AUTH_PAGE = 'index.html'; // Assuming index.html is the auth page

// Check authentication immediately
function checkAuth() {
    const isAuthPage = window.location.pathname.endsWith(AUTH_PAGE) ||
                       window.location.pathname === '/' ||
                       window.location.pathname === '';

    const token = localStorage.getItem(AUTH_TOKEN_KEY);

    if (!token && !isAuthPage) {
        // Not authenticated, redirect to auth page
        window.location.replace(AUTH_PAGE);
    } else if (token && isAuthPage) {
        // Already authenticated, redirect to main app page
        window.location.replace('you.html');
    }
}

// Run check
checkAuth();

// Expose token for API calls
window.getAuthToken = function() {
    return localStorage.getItem(AUTH_TOKEN_KEY);
};

// Helper for authenticated fetch
window.authenticatedFetch = async function(url, options = {}) {
    const token = window.getAuthToken();

    // Add token to headers
    const headers = {
        ...options.headers,
        'Authorization': `Bearer ${token}`
    };

    const newOptions = {
        ...options,
        headers
    };

    const response = await fetch(url, newOptions);

    // If token is invalid, clear it and redirect
    if (response.status === 401) {
        localStorage.removeItem(AUTH_TOKEN_KEY);
        window.location.replace(AUTH_PAGE);
    }

    return response;
};
