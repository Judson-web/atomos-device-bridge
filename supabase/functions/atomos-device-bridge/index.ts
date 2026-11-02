import { createClient } from "npm:@supabase/supabase-js@2.57.4";

const cors = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Headers": "authorization, x-client-info, apikey, content-type, x-device-token",
  "Access-Control-Allow-Methods": "POST, OPTIONS",
  "Vary": "Origin",
};
const json = (body: unknown, status = 200) => new Response(JSON.stringify(body), {
  status, headers: { ...cors, "Content-Type": "application/json", "Cache-Control": "no-store" },
});
const hex = (b: Uint8Array) => Array.from(b, x => x.toString(16).padStart(2, "0")).join("");
const randomToken = (bytes = 32) => {
  const b = new Uint8Array(bytes); crypto.getRandomValues(b);
  return hex(b);
};
const digest = async (value: string) => new Uint8Array(await crypto.subtle.digest("SHA-256", new TextEncoder().encode(value)));
const bytea = (value: Uint8Array) => "\\x" + hex(value);
const url = Deno.env.get("SUPABASE_URL")!;
const serviceKey = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY")!;
const admin = createClient(url, serviceKey, { auth: { persistSession: false, autoRefreshToken: false } });

async function userFrom(req: Request) {
  const auth = req.headers.get("authorization") || "";
  if (!auth.startsWith("Bearer ")) return null;
  const client = createClient(url, Deno.env.get("SUPABASE_ANON_KEY") || serviceKey, {
    global: { headers: { Authorization: auth } }, auth: { persistSession: false, autoRefreshToken: false },
  });
  const { data, error } = await client.auth.getUser();
  if (error || !data.user) return null;
  // Restrict every account-authenticated action to the owner's immutable GitHub provider ID.
  const identities = data.user.identities || [];
  const isAllowedGitHub = identities.some((identity: { provider?: string; provider_id?: string }) =>
    identity.provider === "github" && String(identity.provider_id || "") === "85796566"
  );
  return isAllowedGitHub ? data.user : null;
}

Deno.serve(async (req) => {
  if (req.method === "OPTIONS") return new Response("ok", { headers: cors });
  if (req.method !== "POST") return json({ error: "method_not_allowed" }, 405);
  let body: Record<string, unknown>;
  try {
    const raw = await req.text();
    if (new TextEncoder().encode(raw).length > 16_384) return json({ error: "request_too_large" }, 413);
    body = JSON.parse(raw);
  } catch { return json({ error: "invalid_json" }, 400); }
  if (!body || typeof body !== "object" || Array.isArray(body)) return json({ error: "invalid_request" }, 400);

  if (body.action === "create_pairing") {
    const user = await userFrom(req);
    if (!user) return json({ error: "unauthorized" }, 401);
    const deviceName = String(body.device_name || "Android device").trim().slice(0, 80);
    if (!deviceName) return json({ error: "device_name_required" }, 400);
    const { count, error: rateError } = await admin.schema("private").from("pairing_challenges")
      .select("id", { count: "exact", head: true }).eq("owner_id", user.id)
      .gt("created_at", new Date(Date.now() - 15 * 60_000).toISOString());
    if (rateError) return json({ error: "pairing_rate_check_failed" }, 503);
    if ((count || 0) >= 5) return json({ error: "pairing_rate_limited", retry_after_seconds: 900 }, 429);
    const code = randomToken(6).toUpperCase();
    const expires = new Date(Date.now() + 180_000).toISOString();
    const { error } = await admin.schema("private").from("pairing_challenges").insert({
      owner_id: user.id, code_hash: bytea(await digest(code)), device_name: deviceName,
      app_version: String(body.app_version || "").slice(0, 32), expires_at: expires,
    });
    if (error) return json({ error: "pairing_create_failed" }, 500);
    return json({ code, expires_at: expires, expires_in_seconds: 180 });
  }

  if (body.action === "claim_pairing") {
    const user = await userFrom(req);
    if (!user) return json({ error: "unauthorized" }, 401);
    const code = String(body.code || "").trim().toUpperCase();
    const deviceName = String(body.device_name || "Android device").trim().slice(0, 80);
    if (!/^[A-F0-9]{12}$/.test(code) || !deviceName) return json({ error: "invalid_pairing_code" }, 400);
    const hash = bytea(await digest(code));
    const { data: candidate } = await admin.schema("private").from("pairing_challenges")
      .select("id,owner_id,app_version").eq("owner_id", user.id).eq("code_hash", hash).is("consumed_at", null)
      .gt("expires_at", new Date().toISOString()).lt("failed_attempts", 5).maybeSingle();
    if (!candidate) return json({ error: "pairing_code_invalid_or_expired" }, 401);

    // Claim the challenge before creating any records. The conditional UPDATE is atomic,
    // so two simultaneous requests cannot both register devices from one code.
    const { data: claimed, error: claimError } = await admin.schema("private").from("pairing_challenges")
      .update({ consumed_at: new Date().toISOString() }).eq("id", candidate.id)
      .is("consumed_at", null).gt("expires_at", new Date().toISOString())
      .select("id,owner_id,app_version").maybeSingle();
    if (claimError || !claimed) return json({ error: "pairing_code_already_used" }, 409);

    const { data: device, error: deviceError } = await admin.schema("private").from("device_registry").insert({
      owner_id: claimed.owner_id, device_name: deviceName, app_version: claimed.app_version, status: "active",
    }).select("id").single();
    if (deviceError || !device) return json({ error: "device_registration_failed_reissue_code" }, 500);

    const token = randomToken();
    const { error: sessionError } = await admin.schema("private").from("device_sessions").insert({
      device_id: device.id, token_hash: bytea(await digest(token)),
      expires_at: new Date(Date.now() + 30 * 86400000).toISOString(),
    });
    if (sessionError) {
      await admin.schema("private").from("device_registry").delete().eq("id", device.id);
      return json({ error: "session_create_failed_reissue_code" }, 500);
    }
    return json({ device_id: device.id, device_token: token, expires_in_seconds: 2592000 });
  }

  if (body.action === "list_devices") {
    const user = await userFrom(req);
    if (!user) return json({ error: "unauthorized" }, 401);
    const { data, error } = await admin.schema("private").from("device_registry")
      .select("id,device_name,platform,app_version,status,paired_at,last_seen_at,revoked_at")
      .eq("owner_id", user.id).order("created_at", { ascending: false }).limit(50);
    if (error) return json({ error: "device_list_failed" }, 500);
    return json({ devices: data || [] });
  }

  if (body.action === "revoke_device") {
    const user = await userFrom(req);
    if (!user) return json({ error: "unauthorized" }, 401);
    const id = String(body.device_id || "");
    const { data: device, error } = await admin.schema("private").from("device_registry")
      .update({ status: "revoked", revoked_at: new Date().toISOString(), updated_at: new Date().toISOString() })
      .eq("id", id).eq("owner_id", user.id).eq("status", "active").select("id").maybeSingle();
    if (error || !device) return json({ error: "device_not_found_or_already_revoked" }, 404);
    await admin.schema("private").from("device_sessions").update({
      revoked_at: new Date().toISOString(), revoked_reason: "owner_revoked",
    }).eq("device_id", id).is("revoked_at", null);
    return json({ revoked: true, device_id: id });
  }

  if (body.action === "device_status") {
    const raw = req.headers.get("x-device-token") || "";
    if (!/^[a-f0-9]{64}$/.test(raw)) return json({ error: "device_unauthorized" }, 401);
    const { data: session } = await admin.schema("private").from("device_sessions")
      .select("id,device_id,expires_at,revoked_at,device_registry!inner(status)")
      .eq("token_hash", bytea(await digest(raw))).is("revoked_at", null)
      .gt("expires_at", new Date().toISOString()).maybeSingle();
    if (!session || session.device_registry?.status !== "active") return json({ error: "device_unauthorized" }, 401);
    const now = new Date().toISOString();
    await admin.schema("private").from("device_sessions").update({ last_seen_at: now }).eq("id", session.id);
    await admin.schema("private").from("device_registry").update({ last_seen_at: now }).eq("id", session.device_id);
    return json({ authenticated: true, device_id: session.device_id, expires_at: session.expires_at });
  }
  return json({ error: "unknown_action" }, 400);
});
