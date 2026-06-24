// ═══════════════════════════════════════════════════════════
// Supabase Edge Function: Alert Deduplicator
// Triggered on INSERT to alerts table
// Prevents notification flooding — deduplicates similar alerts
// ═══════════════════════════════════════════════════════════
import { serve } from "https://deno.land/std@0.177.0/http/server.ts";
import { createClient } from "https://esm.sh/@supabase/supabase-js@2";

const SUPABASE_URL = Deno.env.get("SUPABASE_URL")!;
const SERVICE_KEY  = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY")!;
const NTFY_URL     = Deno.env.get("NTFY_URL") || "http://ntfy:80";

const DEDUP_WINDOW_MS   = 30 * 60 * 1000;  // 30 minutes
const MAX_CRITICAL_HOUR = 10;               // Max 10 critical per hour

serve(async (req) => {
  const supabase = createClient(SUPABASE_URL, SERVICE_KEY);

  const { record: alert } = await req.json();
  if (!alert) return new Response("No alert", { status: 400 });

  const { child_id, type, severity, title, body } = alert;

  // 1. Check for duplicates (same child + same type in last 30 mins)
  const windowStart = new Date(Date.now() - DEDUP_WINDOW_MS).toISOString();
  const { data: recent, error } = await supabase
    .from("alerts")
    .select("id, created_at")
    .eq("child_id", child_id)
    .eq("type", type)
    .gte("created_at", windowStart)
    .neq("id", alert.id)
    .order("created_at", { ascending: false });

  if (recent && recent.length > 0) {
    // Duplicate — skip notification (alert still saved in DB)
    console.log(`Dedup: Skipped notification for ${type} (${recent.length} recent)`);
    return new Response(JSON.stringify({ deduped: true, recentCount: recent.length }));
  }

  // 2. Rate limit critical alerts (max 10/hour)
  if (severity === "critical") {
    const hourStart = new Date(Date.now() - 60 * 60 * 1000).toISOString();
    const { count } = await supabase
      .from("alerts")
      .select("id", { count: "exact", head: true })
      .eq("child_id", child_id)
      .eq("severity", "critical")
      .gte("created_at", hourStart);

    if ((count || 0) > MAX_CRITICAL_HOUR) {
      console.log(`Rate limited: ${count} critical alerts in last hour`);
      return new Response(JSON.stringify({ rateLimited: true, count }));
    }
  }

  // 3. Get child's family for ntfy topic
  const { data: child } = await supabase
    .from("children")
    .select("name, family_id")
    .eq("id", child_id)
    .single();

  if (!child) return new Response("Child not found", { status: 404 });

  // 4. Build notification
  const emojiMap: Record<string, string> = {
    adult_content:   "🔞",
    grooming:        "🚨",
    self_harm:       "💔",
    cyberbullying:   "🚫",
    sos:             "🆘",
    geofence_breach: "📍",
    dangerous_keyword: "⚠️",
    unknown_contact: "📞",
    late_night:      "🌙",
    new_app:         "📱",
    device_tampering: "🔧",
  };

  const priorityMap: Record<string, string> = {
    critical: "5",
    warning:  "4",
    info:     "3",
  };

  const emoji = emojiMap[type] || "⚠️";
  const ntfyTitle = `${emoji} ${title}`;
  const ntfyPriority = priorityMap[severity] || "3";

  // 5. Send push notification via ntfy
  try {
    await fetch(`${NTFY_URL}/guardian_${child.family_id}`, {
      method: "POST",
      headers: {
        "Title":    ntfyTitle,
        "Priority": ntfyPriority,
        "Tags":     severity === "critical" ? "rotating_light,warning" : "shield",
      },
      body: `${child.name}: ${body}`,
    });
  } catch (e) {
    console.error("ntfy error:", e);
  }

  return new Response(JSON.stringify({ notified: true, type, severity }), {
    headers: { "Content-Type": "application/json" },
  });
});
