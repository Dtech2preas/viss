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

    // POST /api/user - Save or update user data
    if (request.method === 'POST' && url.pathname === '/api/user') {
      try {
        const body = await request.json();
        const username = body.name || body.requester?.name;

        if (!username) {
          return new Response('Missing name', { status: 400, headers: corsHeaders });
        }

        const existingDataStr = await env.US_KV.get(`user_${username}`);
        let existingData = {};
        if (existingDataStr) {
            existingData = JSON.parse(existingDataStr);
        }

        // Merge the new data with existing
        const mergedData = { ...existingData, ...body };

        await env.US_KV.put(`user_${username}`, JSON.stringify(mergedData));
        return new Response(JSON.stringify({ success: true }), {
          headers: { ...corsHeaders, 'Content-Type': 'application/json' },
        });
      } catch (error) {
        return new Response('Error processing request', { status: 500, headers: corsHeaders });
      }
    }

    // GET /api/user - Fetch user data
    if (request.method === 'GET' && url.pathname === '/api/user') {
      const name = url.searchParams.get('name');
      if (!name) {
        return new Response('Missing name', { status: 400, headers: corsHeaders });
      }

      const userData = await env.US_KV.get(`user_${name}`);
      if (!userData) {
        return new Response(JSON.stringify(null), { status: 404, headers: corsHeaders });
      }

      return new Response(userData, {
        headers: { ...corsHeaders, 'Content-Type': 'application/json' },
      });
    }

    // POST /api/pair/request - Send a pairing request
    if (request.method === 'POST' && url.pathname === '/api/pair/request') {
      try {
        const body = await request.json();
        const { requester, target } = body;

        // Check if target user exists
        const targetDataStr = await env.US_KV.get(`user_${target.name}`);
        if (!targetDataStr) {
          return new Response('Target user not found', { status: 404, headers: corsHeaders });
        }

        const targetData = JSON.parse(targetDataStr);
        if (targetData.surname !== target.surname || String(targetData.age) !== String(target.age)) {
             return new Response('Target user details mismatch', { status: 400, headers: corsHeaders });
        }

        // Create a pending request on target user
        if (!targetData.pendingRequests) {
            targetData.pendingRequests = [];
        }

        // Avoid duplicates
        const exists = targetData.pendingRequests.find(req => req.requester.name === requester.name);
        if (!exists) {
             targetData.pendingRequests.push({ requester });
             await env.US_KV.put(`user_${target.name}`, JSON.stringify(targetData));
        }

        return new Response(JSON.stringify({ success: true }), {
          headers: { ...corsHeaders, 'Content-Type': 'application/json' },
        });
      } catch (error) {
        return new Response('Error processing request', { status: 500, headers: corsHeaders });
      }
    }

    // GET /api/pair/pending - Get pending requests
    if (request.method === 'GET' && url.pathname === '/api/pair/pending') {
      const name = url.searchParams.get('name');

      const userDataStr = await env.US_KV.get(`user_${name}`);
      if (!userDataStr) return new Response(JSON.stringify([]), { headers: corsHeaders });

      const userData = JSON.parse(userDataStr);
      return new Response(JSON.stringify(userData.pendingRequests || []), {
        headers: { ...corsHeaders, 'Content-Type': 'application/json' },
      });
    }

    // POST /api/pair/accept - Accept a request
    if (request.method === 'POST' && url.pathname === '/api/pair/accept') {
      try {
        const { accepter, requester } = await request.json();

        // Update accepter
        const accepterDataStr = await env.US_KV.get(`user_${accepter.name}`);
        const accepterData = JSON.parse(accepterDataStr);
        accepterData.partner = requester;
        accepterData.pendingRequests = accepterData.pendingRequests.filter(req => req.requester.name !== requester.name);
        await env.US_KV.put(`user_${accepter.name}`, JSON.stringify(accepterData));

        // Update requester
        const requesterDataStr = await env.US_KV.get(`user_${requester.name}`);
        if(requesterDataStr) {
            const requesterData = JSON.parse(requesterDataStr);
            requesterData.partner = accepter;
            await env.US_KV.put(`user_${requester.name}`, JSON.stringify(requesterData));
        }

        return new Response(JSON.stringify({ success: true }), {
          headers: { ...corsHeaders, 'Content-Type': 'application/json' },
        });
      } catch(err) {
        return new Response('Error', { status: 500, headers: corsHeaders });
      }
    }

    return new Response('Not found', { status: 404, headers: corsHeaders });
  },
};
