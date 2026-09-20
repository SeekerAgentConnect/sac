/**
 * vault_create_pairing_link: the operator CLI's pairing code, issued over MCP so a
 * hosted agent can show the owner the deep link and HTTPS landing page. It does not
 * pair, prepare, approve, or revoke. The phone still has to confirm.
 */
import type { McpServer } from "@modelcontextprotocol/sdk/server/mcp.js";
import type { CallToolResult } from "@modelcontextprotocol/sdk/types.js";
import { z } from "zod";

import { pairingHttpsUrl, type IssuedPairing } from "@seeker-vault/server-sdk";

export const CREATE_PAIRING_LINK_TOOL = "vault_create_pairing_link";

export interface PairingLinkView {
  readonly pairing_uri: string;
  readonly https_url: string;
  readonly server_url: string;
  readonly expires_at: string;
  readonly replaces?: string;
}

const PAIRING_LINK_SCHEMA = {
  pairing_uri: z
    .string()
    .describe(
      "The seekervault://pair deep link. Open it on the phone or paste it under Add connection.",
    ),
  https_url: z
    .string()
    .describe(
      "The same code as an https://…/pair link. Opening it in a browser opens the app.",
    ),
  server_url: z
    .string()
    .describe(
      "The sidecar origin the phone will call after the owner confirms.",
    ),
  expires_at: z
    .string()
    .describe("When this one-use code stops working, as RFC 3339."),
  replaces: z
    .string()
    .optional()
    .describe(
      "The currently paired connection this code would replace, if a phone is paired now.",
    ),
};

const PAIRING_LINK_DESCRIPTION =
  "Issues a one-use pairing code for Seeker Agent Connect, the same code the operator CLI " +
  "prints. Returns pairing_uri (the seekervault://pair deep link) and https_url (this " +
  "server's /pair landing page, which opens that deep link). The code works once, for a " +
  "few minutes, and a newer code voids an unused one. Whoever opens it first becomes the " +
  "paired phone and revokes the phone paired now. It does not pair by itself: the owner " +
  "still confirms on the phone. Keep the links private; they carry the pairing token.";

export function registerPairingLinkTool(
  server: McpServer,
  issue: () => IssuedPairing,
  log: (message: string) => void,
): void {
  server.registerTool(
    CREATE_PAIRING_LINK_TOOL,
    {
      title: "Create a pairing link for the Seeker app",
      description: PAIRING_LINK_DESCRIPTION,
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
      const view: PairingLinkView = {
        pairing_uri: issued.uri,
        https_url: pairingHttpsUrl(issued),
        server_url: issued.serverUrl,
        expires_at: new Date(issued.expiresAtMs).toISOString(),
        ...(issued.replaces === undefined
          ? {}
          : { replaces: issued.replaces.connectionId }),
      };
      return {
        content: [{ type: "text", text: JSON.stringify(view) }],
        structuredContent: { ...view },
      };
    },
  );
}
