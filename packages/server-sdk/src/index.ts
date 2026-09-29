export {
  openDirectServer,
  startPhoneApi,
  type DirectPairing,
  type DirectServer,
  type DirectServerLogger,
  type IssuedPairing,
  type OpenDirectServerOptions,
  type PhoneApiHandler,
  type PhoneHandlerOptions,
  type StartedPhoneApi,
  type StartPhoneApiOptions,
} from "./direct-server.ts";
export {
  LiveCommandBridge,
  LiveCommandFailure,
  type LiveCommandBridgeOptions,
} from "./live/bridge.ts";
export { MAX_COMMAND_TEXT_BYTES } from "./live/command.ts";
export {
  MAX_BASE_UNITS,
  MAX_MESSAGE_BYTES,
  MAX_NOTE_BYTES,
  actionBinding,
  decodeBase58,
  encodeBase58,
  invalidActionReason,
  messageBytes,
  parseBaseUnits,
  stakingOperationName,
} from "./requests/action.ts";
export {
  COMMON_REQUEST_CONTRACT,
  privateRequest,
  type PrivateRequestDraft,
} from "./requests/developer-api.ts";
export { RequestFailure } from "./requests/failure.ts";
export { isTerminal } from "./requests/lifecycle.ts";
export { verifySignature } from "./requests/signature.ts";
export {
  MAX_EXPIRES_IN_SECONDS,
  MIN_EXPIRES_IN_SECONDS,
  networkName,
} from "./storage/request-store.ts";
export type {
  AgentConfirmations,
  AgentRequests,
  AgentTransfers,
} from "./requests/agent-api.ts";
export {
  ProviderUnavailable,
  UnsupportedPreparation,
  type BuiltPreparation,
  type ConfirmationCommitment,
  type ConfirmationProvider,
  type ProviderSignatureStatus,
  type ProviderTransaction,
  type StakingProvider,
  type TransferProvider,
} from "./providers.ts";
export type {
  InvalidationMessage,
  InvalidationSender,
} from "./push/invalidation.ts";
export {
  FCM_INVALIDATION_COLLAPSE_KEY,
  FCM_INVALIDATION_DATA,
  FCM_INVALIDATION_TTL_MS,
} from "./push/invalidation.ts";
// The gateway relay (SEE-144). GatewayRelaySender is exported because a host may want to build
// one itself; the dispatcher is not, because it is wired by openDirectServer and there is nothing
// a caller does with one.
export type {
  RelayConfiguration,
  RelayHint,
  RelayInvalidation,
  RelayOutcome,
  RelaySender,
} from "./push/relay.ts";
export {
  GatewayRelaySender,
  RELAY_PATH,
  RELAY_PROTOCOL_VERSION,
  invalidRelayReason,
} from "./push/relay.ts";
export {
  SERVER_PROTOCOL_VERSION,
  // The networks a server declares in its manifest (SEE-174). The enum itself is in
  // `./protocol`; these are how a host reads and checks a configured list.
  SOLANA_NETWORK_NAMES,
  canonicalSupportedNetworks,
  parseSupportedNetworks,
  solanaNetworkName,
  type SolanaNetworkName,
} from "./manifest.ts";
export type { PairingCode, ParsedPairingUri } from "./pairing/uri.ts";
export type { PairedPhone } from "./storage/pairing-store.ts";
export {
  invalidServerUrlReason,
  normalizeServerUrl,
  pairingHttpsUrl,
  pairingUri,
  parsePairingUri,
} from "./pairing/uri.ts";
// The HTTPS pairing link and the page it lands on (SEE-140/SEE-148), shared by every server that
// offers the owner one (SEE-149). The host registers its own MCP tool and routes its own /pair;
// what the token travels in, what the page does, and what an agent is told about the link are the
// same wherever it is served.
export {
  CONDITIONAL_REPLACEMENT_WARNING,
  REPLACEMENT_WARNING,
  decodeLandingFragment,
  decodePairingFragment,
  encodePairingFragment,
  landingUrlHasCredential,
  pairingLandingUrl,
  type FragmentDecode,
  type PairingFragmentPayload,
} from "./pairing/landing.ts";
export {
  PAIRING_LINK_PATH,
  SEEKER_MCP_PAIRING_PAGE,
  handlePairingLink,
  isPairingLinkPath,
  type PairingLinkOptions,
  type PairingPageIdentity,
} from "./pairing/link.ts";
export {
  PAIRING_LINK_FIELDS,
  humanPairingLink,
  pairingLinkInstruction,
  pairingLinkToolDescription,
  pairingLinkView,
  type PairingLinkView,
} from "./pairing/link-text.ts";
// The page's own browser module, exported so a host can drive the page it serves in a test.
export {
  bootPairingPage,
  trustedOriginFromDocument,
} from "./pairing/page/page.js";
