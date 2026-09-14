/**
 * The optional Firebase Admin transport. SAW-054 configures this sender but deliberately gives it
 * no request-lifecycle caller: token ownership is SAW-055 and invalidation dispatch is SAW-056.
 *
 * Credentials come only from Application Default Credentials. They are not an argument or return
 * value of this module and are never logged here.
 */
import { randomUUID } from "node:crypto";

import {
  applicationDefault,
  deleteApp,
  initializeApp,
} from "firebase-admin/app";
import { getMessaging, type Message } from "firebase-admin/messaging";

export type FcmMessage = Message;

interface MessagingClient {
  send(message: Message): Promise<string>;
}

/** One Firebase Admin app and its messaging client, with idempotent shutdown. */
export class FcmSender {
  readonly #messaging: MessagingClient;
  readonly #deleteApp: () => Promise<void>;
  #closing: Promise<void> | undefined;

  constructor(messaging: MessagingClient, deleteApp: () => Promise<void>) {
    this.#messaging = messaging;
    this.#deleteApp = deleteApp;
  }

  /** Hands one already-bounded message to FCM; payload policy belongs to the later caller. */
  send(message: Message): Promise<string> {
    return this.#messaging.send(message);
  }

  close(): Promise<void> {
    this.#closing ??= this.#deleteApp();
    return this.#closing;
  }
}

/**
 * Creates a named Admin app so tests and multiple sidecars in one process never share credentials
 * or lifecycle. Constructing it does not send a message or fetch an access token.
 */
export function createFcmSender(projectId: string): FcmSender {
  const app = initializeApp(
    { credential: applicationDefault(), projectId },
    `seeker-vault-sidecar-${randomUUID()}`,
  );
  return new FcmSender(getMessaging(app), () => deleteApp(app));
}
