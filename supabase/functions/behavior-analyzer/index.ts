// ═══════════════════════════════════════════════════════════
// Supabase Edge Function: Behavior Analyzer (Weekly Cron)
// Generates weekly behavior reports with AI risk scoring
// ═══════════════════════════════════════════════════════════
import { serve } from "https://deno.land/std@0.177.0/http/server.ts";
import { createClient } from "https://esm.sh/@supabase/supabase-js@2";

const SUPABASE_URL  = Deno.env.get("SUPABASE_URL")!;
const SERVICE_KEY   = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY")!;
const NTFY_URL      = Deno.env.get("NTFY_URL") || "http://ntfy:80";

serve(async (req) => {
  const supabase = createClient(SUPABASE_URL, SERVICE_KEY);

  // Get all children
  const { data: children, error } = await supabase
    .from("children")
    .select("id, name, family_id");

  if (error || !children) {
    return new Response(JSON.stringify({ error: error?.message }), { status: 500 });
  }

  const results = [];

  for (const child of children) {
    try {
      // Call the DB function to generate report
      const { data: reportId, error: funcErr } = await supabase
        .rpc("generate_weekly_report", { p_child_id: child.id });

      if (funcErr) {
        console.error(`Report failed for ${child.name}:`, funcErr);
        results.push({ child: child.name, status: "error", error: funcErr.message });
        continue;
      }

      // Get the generated report
      const { data: report } = await supabase
        .from("behavior_reports")
        .select("*")
        .eq("id", reportId)
        .single();

      if (report) {
        // Send weekly summary notification to parent
        const screenHours = Math.round(report.total_screen_time / 3600);
        const riskEmoji = report.risk_score < 30 ? "🟢" : report.risk_score < 70 ? "🟡" : "🔴";

        const message = [
          `📊 Weekly Report for ${child.name}`,
          `${riskEmoji} Risk Score: ${report.risk_score}/100`,
          `⏱️ Screen Time: ${screenHours}h total`,
          `🚫 Content Blocked: ${report.blocked_count} times`,
          report.anomalies?.length > 0 ? `⚡ Anomalies: ${report.anomalies.length} detected` : "✅ No anomalies",
        ].join("\n");

        const priority = report.risk_score > 70 ? "4" : report.risk_score > 40 ? "3" : "2";

        await fetch(`${NTFY_URL}/guardian_${child.family_id}`, {
          method: "POST",
          headers: {
            "Title": `📊 Weekly Report: ${child.name}`,
            "Priority": priority,
            "Tags": "chart_with_upwards_trend",
          },
          body: message,
        });

        results.push({ child: child.name, status: "success", reportId, riskScore: report.risk_score });
      }
    } catch (e) {
      results.push({ child: child.name, status: "error", error: e.message });
    }
  }

  return new Response(JSON.stringify({ processed: results.length, results }), {
    headers: { "Content-Type": "application/json" },
  });
});
