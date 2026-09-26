/**
 * What an agent is told about a pairing link, in one place (SEE-148, shared by SEE-149).
 *
 * A pairing link is only useful if it reaches the owner whole: a model that shortens, wraps or
 * escapes `https_url` hands them a fragment the page then refuses, and the owner reads "this
 * pairing link is damaged or incomplete" without knowing why. That is a wording problem, so the
 * wording lives here rather than being written out again by every server that serves the page.
 *
 * There is no MCP or schema library in this file. The tool itself is registered by the host, which
 * is the only part that knows about MCP; this is the text and the view it registers.
 */
import type { IssuedPairing } from "../direct-server.ts";
import { pairingLandingUrl, REPLACEMENT_WARNING } from "./landing.ts";

/** The structured content a pairing-link tool returns, whatever the host calls the tool. */
export interface PairingLinkView {
  readonly pairing_uri: string;
  readonly https_url: string;
  readonly server_url: string;
  readonly expires_at: string;
  readonly replaces?: string;
  readonly warning?: string;
}

/** Each output field, described for the agent that reads it. */
export const PAIRING_LINK_FIELDS = {
  pairing_uri:
    "Complete seekervault://pair deep link. Copy and paste this entire value under Add connection if the page's button does not open the app. Do not omit or escape any character.",
  https_url:
    "Complete https://<origin>/pair#<fragment> link. A Connect your phone label is allowed only when this entire string is the link target. Do not insert an ellipsis, line break, or escape sequence. The page rejects a shortened fragment.",
  server_url: "The server origin the phone will call after the owner confirms.",
  expires_at: "When this one-use code stops working, as RFC 3339.",
  replaces:
    "The currently paired connection this code would replace, if a phone is paired now. Issuing or opening the link does not disconnect it.",
  warning:
    "Show this replacement warning together with the link when a phone is already paired. Creating or opening the link does not disconnect that phone.",
} as const;

const DESCRIPTION =
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

/**
 * The tool's description. `about` is appended when a server needs to say which connection this
 * link pairs — an owner with two servers has two connections, and the agent should say which.
 */
export function pairingLinkToolDescription(about?: string): string {
  return about === undefined ? DESCRIPTION : `${DESCRIPTION} ${about}`;
}

/** The one line a server's MCP instructions carry about its own pairing-link tool. */
export function pairingLinkInstruction(toolName: string): string {
  return (
    `${toolName} issues a one-use pairing code: show the returned text, or a link labeled Connect ` +
    "your phone whose target is the complete https_url. Do not omit, wrap, escape, or replace any " +
    "part of https_url or pairing_uri; a shortened visible label is not a shortened URL, and the " +
    "page rejects a damaged fragment. pairing_uri is the copy/paste fallback. Always display any " +
    "warning with the link. The owner still confirms on the phone."
  );
}

/** The issued code as an agent sees it: the complete links, and the warning when there is one. */
export function pairingLinkView(issued: IssuedPairing): PairingLinkView {
  const replacing = issued.replaces;
  return {
    pairing_uri: issued.uri,
    https_url: pairingLandingUrl(issued),
    server_url: issued.serverUrl,
    expires_at: new Date(issued.expiresAtMs).toISOString(),
    ...(replacing === undefined
      ? {}
      : { replaces: replacing.connectionId, warning: REPLACEMENT_WARNING }),
  };
}

/** The same view as text, for a client that shows an agent's words rather than its structure. */
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
