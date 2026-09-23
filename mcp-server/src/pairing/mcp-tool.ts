/**
 * vault_create_pairing_link: the operator CLI's pairing code, issued over MCP so a
 * hosted agent can show the owner the deep link and HTTPS landing page. It does not
 * pair, prepare, approve, or revoke. The phone still has to confirm.
 */
import type { McpServer } from "@modelcontextprotocol/sdk/server/mcp.js";
import type { CallToolResult } from "@modelcontextprotocol/sdk/types.js";
import { z } from "zod";

import type { IssuedPairing } from "@seeker-vault/server-sdk";

import { pairingLandingUrl, REPLACEMENT_WARNING } from "./fragment.ts";

export const CREATE_PAIRING_LINK_TOOL = "vault_create_pairing_link";

export interface PairingLinkView {
  readonly pairing_uri: string;
  readonly https_url: string;
  readonly server_url: string;
  readonly expires_at: string;
  readonly replaces?: string;
  readonly warning?: string;
}

const PAIRING_LINK_SCHEMA = {
  pairing_uri: z
    .string()
    .describe(
      "Complete seekervault://pair deep link. Copy and paste this entire value under Add connection if the page's button does not open the app. Do not omit or escape any character.",
    ),
  https_url: z
    .string()
    .describe(
      "Complete https://<origin>/pair#<fragment> link. A Connect your phone label is allowed only when this entire string is the link target. Do not insert an ellipsis, line break, or escape sequence. The page rejects a shortened fragment.",
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
      "The currently paired connection this code would replace, if a phone is paired now. Issuing or opening the link does not disconnect it.",
    ),
  warning: z
    .string()
    .optional()
    .describe(
      "Show this replacement warning together with the link when a phone is already paired. Creating or opening the link does not disconnect that phone.",
    ),
};

const PAIRING_LINK_DESCRIPTION =
  "Issues a one-use pairing code for Seeker Agent Connect, the same code the operator CLI " +
  "prints. Returns the complete https_url (https://<origin>/pair#<fragment>; show the " +
  "returned text, or a link labeled Connect your phone whose target is this entire value) and " +
  "pairing_uri (the complete seekervault://pair copy/paste fallback). Do not omit, wrap, escape, " +
  "or replace any part of https_url or pairing_uri. A shortened visible label is not a shortened " +
  "URL, and the page rejects a damaged fragment. The HTTPS link opens a page; the page's button " +
  "opens the app. Do not promise automatic Android App Link behaviour. Always display any warning " +
  "field together with the link. The code works once, expires at expires_at, and must be kept " +
  "private. A newer code voids an unused one. Whoever completes pairing first becomes the paired " +
  "phone and revokes the phone paired now; creating or opening the link does not disconnect it. " +
  "It does not pair by itself: the owner still confirms on the phone.";

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
      const expiresAt = new Date(issued.expiresAtMs).toISOString();
      const replacing = issued.replaces;
      const view: PairingLinkView = {
        pairing_uri: issued.uri,
        https_url: pairingLandingUrl(issued),
        server_url: issued.serverUrl,
        expires_at: expiresAt,
        ...(replacing === undefined
          ? {}
          : {
              replaces: replacing.connectionId,
              warning: REPLACEMENT_WARNING,
            }),
      };
      return {
        content: [{ type: "text", text: humanPairingLink(view) }],
        structuredContent: { ...view },
      };
    },
  );
}

export function humanPairingLink(view: PairingLinkView): string {
  const lines = [
    "Connect your phone",
    "",
    "Open this HTTPS link on the phone. It opens a pairing page on this server. The page's button opens Seeker Agent Connect. Opening the app is not guaranteed in every browser.",
    "",
    `[Connect your phone](${view.https_url})`,
    "",
    "The address itself, as one unbroken line:",
    "",
    "```",
    view.https_url,
    "```",
    "",
    "If the app does not open, copy this pairing code and paste it under Add connection:",
    "",
    "```",
    view.pairing_uri,
    "```",
    "",
    `This code works once and expires at ${view.expires_at}. Keep it private; it carries the pairing token. Sharing the link shares the code. The fragment keeps the token out of the initial HTTP request, but the link is still a secret.`,
  ];
  if (view.warning !== undefined) {
    lines.push("", view.warning);
  }
  return lines.join("\n");
}
