import assert from "node:assert/strict";
import { describe, it } from "node:test";

import { RequestState } from "@seeker_agent_connect/server-sdk/protocol";
import {
  callTool,
  connectAgent,
  pairPhone,
  requestClient,
  viewOf,
} from "../testing/clients.ts";
import {
  freePort,
  startSidecarProcess,
  temporaryDatabasePath,
  type SidecarProcess,
} from "../testing/process.ts";
import { GET_REQUEST_TOOL, REQUEST_ACK_TOOL } from "./mcp-tools.ts";

const MCP_TOKEN = "m".repeat(64);
const PHONE_TOKEN = "p".repeat(64);

describe("durable requests across sidecar restarts", () => {
  it("keeps what each answer confirmed through SIGKILL, and executes nothing on its own", async () => {
    const port = await freePort();
    const databasePath = temporaryDatabasePath();
    const start = (): Promise<SidecarProcess> =>
      startSidecarProcess({
        port,
        mcpToken: MCP_TOKEN,
        phoneToken: PHONE_TOKEN,
        liveCommandTimeoutSeconds: 30,
        databasePath,
        demoTools: true,
      });
    const ack = { text: "Survives a crash", idempotency_key: "restart-1" };
    let sidecar = await start();
    try {
      const { connectionId, phoneToken } = await pairPhone(
        sidecar.url,
        databasePath,
      );
      const agent = await connectAgent(sidecar.url, MCP_TOKEN);
      const created = viewOf(await callTool(agent, REQUEST_ACK_TOOL, ack));
      // Killed right after the tool answered: the request must already be on disk.
      await sidecar.stop("SIGKILL");
      await agent.close().catch(() => undefined);

      sidecar = await start();
      assert.match(
        sidecar.output(),
        new RegExp(`paired phone: connection ${connectionId}`),
      );
      assert.doesNotMatch(
        sidecar.output(),
        /request [0-9a-f-]{36}/,
        "nothing runs at startup",
      );
      // The phone's credential survived the restart too.
      const phone = requestClient(sidecar.url, phoneToken);
      const { requests } = await phone.listPending({ connectionId });
      assert.deepEqual(
        requests.map((request) => request.ref?.requestId),
        [created.request_id],
      );
      const { request } = await phone.submitResult({
        ref: { connectionId, requestId: created.request_id },
        result: { case: "acknowledgement", value: {} },
      });
      assert.equal(request?.state, RequestState.COMPLETED);
      // Killed right after the phone's answer was confirmed: the result must be on disk too.
      await sidecar.stop("SIGKILL");

      sidecar = await start();
      const reader = await connectAgent(sidecar.url, MCP_TOKEN);
      try {
        const result = viewOf(
          await callTool(reader, GET_REQUEST_TOOL, {
            request_id: created.request_id,
          }),
        );
        assert.equal(result.status, "COMPLETED");
        assert.equal(result.terminal, true);
        const retried = viewOf(await callTool(reader, REQUEST_ACK_TOOL, ack));
        assert.equal(retried.request_id, created.request_id);
        assert.equal(retried.status, "COMPLETED");
      } finally {
        await reader.close();
      }
    } finally {
      await sidecar.stop("SIGKILL");
    }
  });
});
