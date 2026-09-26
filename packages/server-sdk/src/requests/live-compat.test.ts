/**
 * The durable request contract leaves the Stage 1 live diagnostic as it was (docs/protocol.md):
 * its own package, the same two RPCs, and no dependency in either direction.
 */
import assert from "node:assert/strict";
import { describe, it } from "node:test";

import type { DescFile } from "@bufbuild/protobuf";

import {
  LiveCommandService,
  file_seekervault_live_v1_live,
} from "../gen/seekervault/live/v1/live_pb.js";
import { file_seekervault_request_v1_request } from "../gen/seekervault/request/v1/request_pb.js";
import {
  PairingService,
  RequestService,
  file_seekervault_request_v1_service,
} from "../gen/seekervault/request/v1/service_pb.js";
import {
  UpdateService,
  file_seekervault_update_v1_update,
} from "../gen/seekervault/update/v1/update_pb.js";

function imports(file: DescFile): string[] {
  return file.dependencies.map((dependency) => dependency.name);
}

describe("compatibility with the Stage 1 live diagnostic", () => {
  it("keeps LiveCommandService's stream and acknowledgement", () => {
    assert.deepEqual(
      LiveCommandService.methods.map((method) => [
        method.name,
        method.methodKind,
      ]),
      [
        ["WatchCommands", "server_streaming"],
        ["AcknowledgeCommand", "unary"],
      ],
    );
  });

  it("keeps the live and durable contracts independent", () => {
    assert.deepEqual(imports(file_seekervault_live_v1_live), [
      "google/protobuf/timestamp",
    ]);
    assert.deepEqual(imports(file_seekervault_request_v1_request), [
      "google/protobuf/timestamp",
    ]);
    // The service also carries what a server says about itself (SEE-88), which is its own
    // package because every kind of server publishes one, including the ones that serve no
    // RequestService at all.
    assert.deepEqual(imports(file_seekervault_request_v1_service), [
      "seekervault/request/v1/request",
      "seekervault/server/v1/manifest",
    ]);
  });

  it("keeps pairing and RequestService unary", () => {
    assert.deepEqual(
      [...PairingService.methods, ...RequestService.methods].map((method) => [
        method.name,
        method.methodKind,
      ]),
      [
        ["Pair", "unary"],
        ["GetConnectionCapabilities", "unary"],
        ["GetServerManifest", "unary"],
        ["SetFcmToken", "unary"],
        ["SetRelayHandle", "unary"],
        ["RevokeConnection", "unary"],
        ["ListPending", "unary"],
        ["GetRequest", "unary"],
        ["PrepareRequest", "unary"],
        ["SubmitResult", "unary"],
        ["CheckStatus", "unary"],
        ["PublishWallet", "unary"],
      ],
    );
  });

  it("defines production updates as one bidirectional RPC and one unary recovery RPC", () => {
    assert.deepEqual(imports(file_seekervault_update_v1_update), [
      "google/protobuf/timestamp",
      "seekervault/request/v1/request",
    ]);
    assert.deepEqual(
      UpdateService.methods.map((method) => [method.name, method.methodKind]),
      [
        ["Subscribe", "bidi_streaming"],
        ["Sync", "unary"],
      ],
    );
  });
});
