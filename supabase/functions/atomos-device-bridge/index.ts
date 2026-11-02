import "jsr:@supabase/functions-js/edge-runtime.d.ts";

Deno.serve((_req: Request) => new Response(JSON.stringify({
  error: "retired_endpoint",
  message: "The legacy Atomos cloud-device bridge has been retired. This Android app is local-only."
}), {
  status: 410,
  headers: {
    "Content-Type": "application/json",
    "Cache-Control": "no-store"
  }
}));
