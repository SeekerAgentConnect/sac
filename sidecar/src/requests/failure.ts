/** The error every durable request operation refuses with (docs/protocol.md#request-errors). */
import {
  RequestError,
  type ActionRequest,
} from "../gen/seekervault/request/v1/request_pb.js";

/**
 * A refused operation: the RequestError that MCP and Connect both report, and the request as it
 * is now, when there is one.
 */
export class RequestFailure extends Error {
  readonly error: RequestError;
  readonly request: ActionRequest | undefined;

  constructor(error: RequestError, message: string, request?: ActionRequest) {
    super(message);
    this.name = "RequestFailure";
    this.error = error;
    this.request = request;
  }

  /** The error's name as agents see it, for example `NOT_FOUND`. */
  get code(): string {
    return RequestError[this.error];
  }
}
