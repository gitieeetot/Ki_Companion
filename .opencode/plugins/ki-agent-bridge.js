// Ki Companion OpenCode agent bridge. Phone actions remain explicit and one-shot.
import fs from "node:fs";
import path from "node:path";

const allowedEvents = new Set([
  "session.created", "session.updated", "session.status", "session.idle",
  "session.error", "permission.asked", "permission.replied",
  "tool.execute.before", "tool.execute.after",
]);

function bridgeConfig() {
  const local = process.env.LOCALAPPDATA;
  if (!local) return null;
  try {
    const value = JSON.parse(
      fs.readFileSync(path.join(local, "Ki", "opencode_monitor.json"), "utf8"),
    );
    return Number.isInteger(value.port) && typeof value.token === "string" &&
      value.protocol === 2 ? value : null;
  } catch {
    return null;
  }
}

async function bridgeRequest(bridge, endpoint, options = {}) {
  const response = await fetch(`http://127.0.0.1:${bridge.port}${endpoint}`, {
    ...options,
    headers: {
      Authorization: `Bearer ${bridge.token}`,
      ...(options.body ? { "Content-Type": "application/json" } : {}),
      ...options.headers,
    },
    signal: AbortSignal.timeout(2000),
  });
  if (!response.ok && response.status !== 204) {
    throw new Error(`Ki bridge returned HTTP ${response.status}.`);
  }
  return response.status === 204 ? null : response.json();
}

function sessionId(properties) {
  return properties.sessionID ?? properties.info?.sessionID ??
    properties.info?.id ?? properties.session?.id ?? "";
}

function eventPayload(event, directory) {
  const properties = event.properties ?? {};
  const info = properties.info ?? properties.session ?? {};
  const projectDirectory = info.directory ?? properties.directory ?? directory ?? "";
  const record = {
    type: event.type,
    session_id: sessionId(properties),
    session_title: info.title ?? properties.title ?? "",
    project_name: projectDirectory ? path.basename(projectDirectory) : "",
    status: properties.status?.type ?? properties.status ?? "",
    tool_name: properties.tool ?? properties.name ?? "",
  };
  if (event.type.startsWith("permission.")) {
    const permission = properties.permission && typeof properties.permission === "object"
      ? properties.permission : {};
    record.permission_id = properties.permissionID ?? properties.requestID ??
      properties.id ?? permission.id ?? "";
    record.permission_title = permission.title ?? properties.permission ??
      properties.title ?? properties.action ?? "";
    record.permission_type = permission.type ?? properties.action ??
      (typeof properties.permission === "string" ? properties.permission : "");
    record.permission_patterns = permission.patterns ?? properties.patterns ??
      properties.resources ?? [];
  }
  return record;
}

async function readMessages(client, sessionID) {
  const result = await client.session.messages({
    path: { id: sessionID },
    query: { limit: 20 },
    throwOnError: true,
  });
  const rows = Array.isArray(result) ? result : result?.data;
  if (!Array.isArray(rows)) throw new Error("OpenCode returned an unreadable message list.");
  return rows.slice(-20).map((item) => {
    const info = item?.info ?? {};
    const parts = Array.isArray(item?.parts) ? item.parts : [];
    const content = parts
      .filter((part) => part?.type === "text" && typeof part.text === "string")
      .map((part) => part.text)
      .join("\n")
      .slice(0, 6000);
    return {
      role: info.role === "assistant" ? "assistant" : "user",
      content,
    };
  }).filter((item) => item.content);
}

async function executeCommand(client, command) {
  if (command.action === "messages") {
    return { messages: await readMessages(client, command.session_id) };
  }
  if (command.action === "permission") {
    if (!["once", "reject"].includes(command.response)) {
      throw new Error("Only one-time approval or denial is supported.");
    }
    if (typeof client.permission?.reply !== "function") {
      throw new Error("This OpenCode version does not expose the permission reply API Ki requires.");
    }
    await client.permission.reply({
      sessionID: command.session_id,
      requestID: command.permission_id,
      decision: command.response === "once" ? "once" : "reject",
    });
    return { accepted: true };
  }
  if (command.action === "prompt") {
    const text = command.text.trim();
    if (!text || text.length > 2000) throw new Error("Instructions must contain 1–2,000 characters.");
    await client.session.promptAsync({
      path: { id: command.session_id },
      body: { parts: [{ type: "text", text }] },
      throwOnError: true,
    });
    return { accepted: true };
  }
  throw new Error("Unsupported Ki phone action.");
}

async function pollCommands(client) {
  const bridge = bridgeConfig();
  if (!bridge) return;
  try {
    const command = await bridgeRequest(bridge, "/command");
    if (!command) return;
    let result;
    try {
      result = { request_id: command.request_id, ok: true, ...await executeCommand(client, command) };
    } catch (error) {
      result = {
        request_id: command.request_id,
        ok: false,
        error: String(error?.message ?? "OpenCode could not complete the action.").slice(0, 500),
      };
    }
    await bridgeRequest(bridge, "/command-result", {
      method: "POST",
      body: JSON.stringify(result),
    });
  } catch {
    // Ki may be closed or its phone bridge may be disabled.
  }
}

export default async ({ client, directory }) => {
  let polling = false;
  const timer = setInterval(async () => {
    if (polling) return;
    polling = true;
    try {
      await pollCommands(client);
    } finally {
      polling = false;
    }
  }, 500);

  return {
    event: async ({ event }) => {
      if (!event || !allowedEvents.has(event.type)) return;
      const record = eventPayload(event, directory);
      if (typeof record.session_id !== "string" || !record.session_id) return;
      const bridge = bridgeConfig();
      if (!bridge) return;
      try {
        await bridgeRequest(bridge, "/event", {
          method: "POST",
          body: JSON.stringify(record),
        });
      } catch {
        // Ki may be closed or monitoring may be disabled.
      }
    },
  };
};
