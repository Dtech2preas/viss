export default {
  async fetch(request, env) {
    // Add CORS headers to all responses
    const corsHeaders = {
      'Access-Control-Allow-Origin': '*',
      'Access-Control-Allow-Methods': 'GET, HEAD, POST, OPTIONS',
      'Access-Control-Allow-Headers': 'Content-Type, Authorization',
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

    // POST /api/auth - Authenticate user and return a token
    if (request.method === 'POST' && url.pathname === '/api/auth') {
      try {
        const body = await request.json();

        let authDataStr = await env.US_KV.get('auth_answers');
        let authAnswers = {
          q1: 'owami',
          q2: 'jonas',
          q3: '41',
          q4: 'red and blue'
        };

        if (authDataStr) {
          authAnswers = JSON.parse(authDataStr);
        } else {
          await env.US_KV.put('auth_answers', JSON.stringify(authAnswers));
        }

        const isQ1Correct = body.q1 && body.q1.toLowerCase().trim() === authAnswers.q1.toLowerCase();
        const isQ2Correct = body.q2 && body.q2.toLowerCase().trim() === authAnswers.q2.toLowerCase();
        const isQ3Correct = body.q3 && body.q3.toLowerCase().trim() === authAnswers.q3.toLowerCase();

        const q4Input = body.q4 ? body.q4.toLowerCase().trim() : '';
        const isQ4Correct = q4Input.includes('red') && q4Input.includes('blue');

        if (isQ1Correct && isQ2Correct && isQ3Correct && isQ4Correct) {
          const token = 'auth_token_jonas_owami_secure_2024';
          return new Response(JSON.stringify({ success: true, token: token }), {
            headers: { ...corsHeaders, 'Content-Type': 'application/json' },
          });
        } else {
          return new Response(JSON.stringify({ success: false, message: 'Incorrect answers.' }), {
            status: 401,
            headers: { ...corsHeaders, 'Content-Type': 'application/json' },
          });
        }
      } catch (error) {
        return new Response(JSON.stringify({ success: false, message: 'Server error' }), { status: 500, headers: corsHeaders });
      }
    }

    // Auth Middleware: Check for valid token on /api/couple
    if (url.pathname.startsWith('/api/couple')) {
      const authHeader = request.headers.get('Authorization');
      if (!authHeader || authHeader !== 'Bearer auth_token_jonas_owami_secure_2024') {
        return new Response(JSON.stringify({ error: 'Unauthorized' }), {
          status: 401,
          headers: { ...corsHeaders, 'Content-Type': 'application/json' }
        });
      }
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
