export default {
  async fetch(request, env) {
    // Add CORS headers to all responses
    const corsHeaders = {
      'Access-Control-Allow-Origin': '*',
      'Access-Control-Allow-Methods': 'GET, HEAD, POST, OPTIONS',
      'Access-Control-Allow-Headers': 'Content-Type',
    };

    // Handle CORS preflight requests
    if (request.method === 'OPTIONS') {
      return new Response(null, { headers: corsHeaders });
    }

    const url = new URL(request.url);

    // Hardcoded single couple key for Jonas and Owami
    const COUPLE_KEY = 'couple_jonas_owami';

    // Helper to deep merge objects
    function isObject(item) {
      return (item && typeof item === 'object' && !Array.isArray(item));
    }

    function mergeDeep(target, ...sources) {
      if (!sources.length) return target;
      const source = sources.shift();

      if (isObject(target) && isObject(source)) {
        for (const key in source) {
          if (isObject(source[key])) {
            if (!target[key]) Object.assign(target, { [key]: {} });
            mergeDeep(target[key], source[key]);
          } else {
            Object.assign(target, { [key]: source[key] });
          }
        }
      }

      return mergeDeep(target, ...sources);
    }

    // POST /api/couple - Save or update the shared couple data
    if (request.method === 'POST' && url.pathname === '/api/couple') {
      try {
        const body = await request.json();

        const existingDataStr = await env.US_KV.get(COUPLE_KEY);
        let existingData = {};
        if (existingDataStr) {
            existingData = JSON.parse(existingDataStr);
        }

        // Deep merge the new data with existing
        const mergedData = mergeDeep({}, existingData, body);

        await env.US_KV.put(COUPLE_KEY, JSON.stringify(mergedData));
        return new Response(JSON.stringify({ success: true }), {
          headers: { ...corsHeaders, 'Content-Type': 'application/json' },
        });
      } catch (error) {
        return new Response('Error processing request', { status: 500, headers: corsHeaders });
      }
    }

    // GET /api/couple - Fetch the shared couple data
    if (request.method === 'GET' && url.pathname === '/api/couple') {
      const coupleDataStr = await env.US_KV.get(COUPLE_KEY);
      if (!coupleDataStr) {
        // Return empty object if not initialized yet
        return new Response(JSON.stringify({}), {
          status: 200,
          headers: { ...corsHeaders, 'Content-Type': 'application/json' }
        });
      }

      return new Response(coupleDataStr, {
        headers: { ...corsHeaders, 'Content-Type': 'application/json' },
      });
    }

    return new Response('Not found', { status: 404, headers: corsHeaders });
  },
};
