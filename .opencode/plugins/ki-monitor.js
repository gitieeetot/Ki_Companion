// Ki's opt-in OpenCode V2 status bridge.
// Only event.type is sent; event payloads are intentionally never inspected.
import fs from "node:fs";
import path from "node:path";

const allowed = new Set([
  "session.created", "session.updated", "session.status", "session.idle",
  "session.error", "permission.asked", "permission.replied",
  "tool.execute.before", "tool.execute.after",
]);

async function sendEvent(type) {
  try {
    const local = process.env.LOCALAPPDATA;
    if (!local || !allowed.has(type)) return;
    const bridgeFile = path.join(local, "Ki", "opencode_monitor.json");
    const bridge = JSON.parse(fs.readFileSync(bridgeFile, "utf8"));
    if (bridge.protocol === 2) return;
    if (!Number.isInteger(bridge.port) || typeof bridge.token !== "string") return;
    await fetch(`http://127.0.0.1:${bridge.port}/event`, {
      method: "POST",
      headers: { "Authorization": `Bearer ${bridge.token}`, "Content-Type": "application/json" },
      body: JSON.stringify({ type }),
      signal: AbortSignal.timeout(1000),
    });
  } catch {
    // Ki may be closed or monitoring may be disabled; OpenCode remains unaffected.
  }
}

export default {
  id: "ki.local-status-monitor",
  setup(ctx) {
    const controller = new AbortController();
    void (async () => {
      try {
        for await (const event of ctx.event.subscribe({ signal: controller.signal })) {
          if (event && allowed.has(event.type)) void sendEvent(event.type);
        }
      } catch {
        // OpenCode can stop the subscription when the plugin unloads.
      }
    })();
    return () => controller.abort();
  },
};
