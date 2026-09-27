/**
 * vault_create_pairing_link: the operator CLI's pairing code, issued over MCP so a
 * hosted agent can show the owner the deep link and HTTPS landing page. It does not
 * pair, prepare, approve, or revoke. The phone still has to confirm.
 *
 * The link, the page it lands on and what an agent is told about both are the SDK's (SEE-149);
 * this file is the MCP registration, which is the only part that knows about MCP.
 */
import type { McpServer } from "@modelcontextprotocol/sdk/server/mcp.js";
import type { CallToolResult } from "@modelcontextprotocol/sdk/types.js";
import { z } from "zod";

import {
  PAIRING_LINK_FIELDS,
  humanPairingLink,
  pairingLinkToolDescription,
  pairingLinkView,
  type IssuedPairing,
  type PairingLinkView,
} from "@seeker_agent_connect/server-sdk";

export const CREATE_PAIRING_LINK_TOOL = "vault_create_pairing_link";

export type { PairingLinkView };
export { humanPairingLink };

const PAIRING_LINK_SCHEMA = {
  pairing_uri: z.string().describe(PAIRING_LINK_FIELDS.pairing_uri),
  https_url: z.string().describe(PAIRING_LINK_FIELDS.https_url),
  server_url: z.string().describe(PAIRING_LINK_FIELDS.server_url),
  expires_at: z.string().describe(PAIRING_LINK_FIELDS.expires_at),
  replaces: z.string().optional().describe(PAIRING_LINK_FIELDS.replaces),
  warning: z.string().optional().describe(PAIRING_LINK_FIELDS.warning),
};

export function registerPairingLinkTool(
  server: McpServer,
  issue: () => IssuedPairing,
  log: (message: string) => void,
): void {
  server.registerTool(
    CREATE_PAIRING_LINK_TOOL,
    {
      title: "Create a pairing link for the Seeker app",
      description: pairingLinkToolDescription(),
      inputSchema: {},
      outputSchema: PAIRING_LINK_SCHEMA,
      annotations: {
        readOnlyHint: false,
        destructiveHint: true,
        idempotentHint: false,
        openWorldHint: false,
      },
    },
    (): CallToolResult => {
      const issued = issue();
      log("pairing link issued");
      const view = pairingLinkView(issued);
      return {
        content: [{ type: "text", text: humanPairingLink(view) }],
        structuredContent: { ...view },
      };
    },
  );
}
