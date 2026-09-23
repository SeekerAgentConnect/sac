/** The direct adapter's common request draft (SEE-108). */
import type { Action } from "../gen/seekervault/request/v1/request_pb.js";
import { RequestError } from "../gen/seekervault/request/v1/request_pb.js";
import type { NewRequest } from "../storage/request-store.ts";
import { RequestFailure } from "./failure.ts";

export const COMMON_REQUEST_CONTRACT = 1;

export interface PrivateRequestDraft {
  readonly contractVersion: 1;
  readonly presentation: {
    readonly title: string;
    readonly description: string;
    readonly category: "request";
  };
  readonly capability: {
    readonly id: "ack" | "sign_message" | "transfer" | "swap" | "staking";
    readonly version: 1;
    readonly action: Action;
  };
  /** Declarations only. An owner's values are never source-authored. */
  readonly ownerInputs: readonly [];
  readonly audience: { readonly kind: "private" };
  readonly resultHandling: "return_to_origin";
  readonly idempotencyKey: string;
  readonly expiresInSeconds?: number;
}

export function privateRequest(
  action: Action,
  description: string,
  idempotencyKey: string,
  expiresInSeconds?: number,
): PrivateRequestDraft {
  const kind = action.kind.case;
  const capability = kind === "signMessage" ? "sign_message" : kind;
  if (
    capability !== "ack" &&
    capability !== "sign_message" &&
    capability !== "transfer" &&
    capability !== "swap" &&
    capability !== "staking"
  ) {
    throw new RequestFailure(
      RequestError.INVALID_PARAMETERS,
      "action is missing",
    );
  }
  return {
    contractVersion: COMMON_REQUEST_CONTRACT,
    presentation: {
      title: title(capability),
      description,
      category: "request",
    },
    capability: { id: capability, version: 1, action },
    ownerInputs: [],
    audience: { kind: "private" },
    resultHandling: "return_to_origin",
    idempotencyKey,
    expiresInSeconds,
  };
}

/** Compatibility adapter into the durable ActionRequest store. */
export function legacyPrivateRequest(draft: PrivateRequestDraft): NewRequest {
  if (
    draft.contractVersion !== COMMON_REQUEST_CONTRACT ||
    draft.presentation.category !== "request" ||
    draft.capability.version !== 1 ||
    draft.audience.kind !== "private" ||
    draft.resultHandling !== "return_to_origin" ||
    draft.ownerInputs.length !== 0
  ) {
    throw new RequestFailure(
      RequestError.INVALID_PARAMETERS,
      "the direct adapter requires a private request whose result returns to its origin",
    );
  }
  return {
    action: draft.capability.action,
    agentNote: draft.presentation.description,
    idempotencyKey: draft.idempotencyKey,
    expiresInSeconds: draft.expiresInSeconds,
  };
}

function title(capability: PrivateRequestDraft["capability"]["id"]): string {
  switch (capability) {
    case "ack":
      return "Acknowledgement";
    case "sign_message":
      return "Sign message";
    case "transfer":
      return "Transfer";
    case "swap":
      return "Swap";
    case "staking":
      return "Staking";
  }
}
