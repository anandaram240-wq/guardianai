// ═══════════════════════════════════════════════════════════
// Supabase Edge Function: Geofence Checker
// Triggered on INSERT to location_history
// Detects enter/exit events using Haversine formula
// ═══════════════════════════════════════════════════════════
import { serve } from "https://deno.land/std@0.177.0/http/server.ts";
import { createClient } from "https://esm.sh/@supabase/supabase-js@2";

const SUPABASE_URL = Deno.env.get("SUPABASE_URL")!;
const SERVICE_KEY  = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY")!;
const NTFY_URL     = Deno.env.get("NTFY_URL") || "http://ntfy:80";

// ─── Haversine Distance (meters) ──────────────────────────
function haversine(lat1: number, lon1: number, lat2: number, lon2: number): number {
  const R = 6371000; // Earth radius in meters
  const toRad = (deg: number) => (deg * Math.PI) / 180;
  const dLat = toRad(lat2 - lat1);
  const dLon = toRad(lon2 - lon1);
  const a =
    Math.sin(dLat / 2) ** 2 +
    Math.cos(toRad(lat1)) * Math.cos(toRad(lat2)) * Math.sin(dLon / 2) ** 2;
  return R * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
}

serve(async (req) => {
  const supabase = createClient(SUPABASE_URL, SERVICE_KEY);

  // Parse the webhook payload (triggered by INSERT on location_history)
  const { record } = await req.json();
  if (!record) {
    return new Response("No record", { status: 400 });
  }

  const { child_id, latitude, longitude } = record;
  if (!child_id || !latitude || !longitude) {
    return new Response("Missing location data", { status: 400 });
  }

  // Get child info
  const { data: child } = await supabase
    .from("children")
    .select("name, family_id, metadata")
    .eq("id", child_id)
    .single();

  if (!child) return new Response("Child not found", { status: 404 });

  // Get all active geofences for this family
  const { data: geofences } = await supabase
    .from("geofences")
    .select("*")
    .eq("family_id", child.family_id)
    .eq("is_active", true);

  if (!geofences || geofences.length === 0) {
    return new Response("No geofences", { status: 200 });
  }

  // Previous geofence state (which zones was child in?)
  const prevState: Record<string, boolean> = child.metadata?.geofence_state || {};
  const newState: Record<string, boolean> = {};
  const events: Array<{ geofence_id: string; event_type: string; name: string; zone_type: string }> = [];

  for (const fence of geofences) {
    const distance = haversine(latitude, longitude, fence.latitude, fence.longitude);
    const isInside = distance <= fence.radius_meters;
    newState[fence.id] = isInside;

    const wasInside = prevState[fence.id] ?? false;

    // Detect transitions
    if (isInside && !wasInside) {
      events.push({ geofence_id: fence.id, event_type: "enter", name: fence.name, zone_type: fence.zone_type });
    } else if (!isInside && wasInside) {
      events.push({ geofence_id: fence.id, event_type: "exit", name: fence.name, zone_type: fence.zone_type });
    }
  }

  // Process events
  for (const ev of events) {
    // Insert geofence_events record
    await supabase.from("geofence_events").insert({
      child_id,
      geofence_id:  ev.geofence_id,
      event_type:   ev.event_type,
      location_lat: latitude,
      location_lng: longitude,
    });

    // Create alert
    const isRestricted = ev.zone_type === "restricted";
    const severity = (ev.event_type === "enter" && isRestricted) ? "critical"
                   : (ev.event_type === "exit" && !isRestricted) ? "warning"
                   : "info";

    const title = ev.event_type === "enter"
      ? `📍 ${child.name} entered "${ev.name}"`
      : `📍 ${child.name} left "${ev.name}"`;

    const mapLink = `https://maps.google.com/?q=${latitude},${longitude}`;

    await supabase.from("alerts").insert({
      child_id,
      type:     "geofence_breach",
      severity,
      title,
      body:     `${child.name} ${ev.event_type === "enter" ? "entered" : "left"} the ${ev.zone_type} zone "${ev.name}".\n📍 ${mapLink}`,
      metadata: { geofence_id: ev.geofence_id, event_type: ev.event_type, distance: 0, zone_type: ev.zone_type },
    });

    // Send ntfy push
    const priority = severity === "critical" ? "5" : severity === "warning" ? "4" : "3";
    await fetch(`${NTFY_URL}/guardian_${child.family_id}`, {
      method: "POST",
      headers: {
        "Title":    title,
        "Priority": priority,
        "Tags":     ev.event_type === "enter" ? "round_pushpin" : "warning",
        "Actions":  `view, Open Map, ${mapLink}`,
      },
      body: `${child.name} ${ev.event_type === "enter" ? "arrived at" : "left"} ${ev.name}`,
    });
  }

  // Update child metadata with current geofence state
  await supabase.from("children").update({
    metadata: { ...child.metadata, geofence_state: newState },
  }).eq("id", child_id);

  return new Response(JSON.stringify({
    processed: events.length,
    events: events.map(e => `${e.event_type}: ${e.name}`),
  }), {
    headers: { "Content-Type": "application/json" },
  });
});
