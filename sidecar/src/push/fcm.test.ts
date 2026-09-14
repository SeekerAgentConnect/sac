import assert from "node:assert/strict";
import { describe, it } from "node:test";

import type { Message } from "firebase-admin/messaging";

import { createFcmSender, FcmSender } from "./fcm.ts";

describe("FCM sender", () => {
  it("hands the exact message to Firebase and returns its opaque ID", async () => {
    const calls: Message[] = [];
    const message = {
      token: "not-a-real-registration-token",
      data: { kind: "invalidation" },
    } satisfies Message;
    const sender = new FcmSender(
      {
        send(candidate) {
          calls.push(candidate);
          return Promise.resolve("projects/example/messages/opaque");
        },
      },
      () => Promise.resolve(),
    );

    assert.equal(
      await sender.send(message),
      "projects/example/messages/opaque",
    );
    assert.deepEqual(calls, [message]);
  });

  it("deletes its Firebase app at most once", async () => {
    let closes = 0;
    const sender = new FcmSender(
      { send: () => Promise.resolve("unused") },
      () => {
        closes += 1;
        return Promise.resolve();
      },
    );

    await Promise.all([sender.close(), sender.close()]);
    assert.equal(closes, 1);
  });

  it("can initialize and close without loading or contacting credentials", async () => {
    const sender = createFcmSender("seeker-vault-test-123");
    await sender.close();
  });
});
