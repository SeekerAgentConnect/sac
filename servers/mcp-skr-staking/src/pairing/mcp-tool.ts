/**
 * skr_create_pairing_link: a one-use pairing code for this server, issued over MCP so a hosted
 * agent can show the owner an HTTPS link instead of the owner reading a QR code out of a container
 * console (SEE-149). It does not pair, prepare, approve, or revoke. The phone still has to confirm.
 *
 * The name is this server's own. An agent may be connected to the general MCP server as well, and
 * `vault_create_pairing_link` there pairs a different database, a different connection and a
 * different set of tools; two links called the same thing would be two links an agent could hand
 * the owner in the wrong order without ever noticing.
 *
 * The link, the page it lands on and what an agent is told about both are the SDK's, shared with
 * that server; this file is the MCP registration.
 */
import type { McpServer } from "@modelcontextprotocol/sdk/server/mcp.js";
import type { CallToolResult } from "@modelcontextprotocol/sdk/types.js";
import {
  PAIRING_LINK_FIELDS,
  humanPairingLink,
  pairingLinkToolDescription,
  pairingLinkView,
  type IssuedPairing,
} from "@seeker_agent_connect/server-sdk";
import { z } from "zod";

export const CREATE_PAIRING_LINK_TOOL = "skr_create_pairing_link";

/** What the owner is connecting to, which is not the general Seeker Agent Connect MCP server. */
export const PAIRING_LINK_SUBJECT =
  "This link pairs a phone with the SKR staking server, which the owner sees as its own " +
  "connection: it is separate from any Seeker Agent Connect MCP server they also run, and pairing " +
  "here neither creates nor disturbs that other connection.";

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
      title: "Create a pairing link for the SKR staking server",
      description: pairingLinkToolDescription(PAIRING_LINK_SUBJECT),
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
