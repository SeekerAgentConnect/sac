export const PAIRING_FRAGMENT_VERSION: 1;
export const MAX_FRAGMENT_BYTES: number;
export const MAX_DISPLAY_CHARS: number;
export const REPLACEMENT_WARNING: string;
export const CONDITIONAL_REPLACEMENT_WARNING: string;

export interface PairingFragmentPayload {
  readonly v: 1;
  readonly pairing_uri: string;
  readonly expires_at: string;
  readonly warning?: string;
  readonly replaces?: string;
}

export interface PairingCodeFields {
  readonly serverUrl: string;
  readonly serverId: string;
  readonly token: string;
}

export type FragmentDecode =
  | {
      readonly ok: true;
      readonly payload: PairingFragmentPayload;
      readonly code: PairingCodeFields;
    }
  | { readonly ok: false; readonly reason: string };

export type ParsedCustomUri =
  | { readonly ok: true; readonly code: PairingCodeFields }
  | { readonly ok: false; readonly reason: string };

export function encodePairingFragment(payload: PairingFragmentPayload): string;
export function decodePairingFragment(fragment: string): FragmentDecode;
export function parseCustomPairingUri(text: string): ParsedCustomUri;
export function parseLegacyPairingQuery(search: string): ParsedCustomUri;
export function pairingUriFromCode(code: PairingCodeFields): string;
export function originOf(url: string): string;
export function sameOrigin(serverUrl: string, trustedOrigin: string): boolean;
