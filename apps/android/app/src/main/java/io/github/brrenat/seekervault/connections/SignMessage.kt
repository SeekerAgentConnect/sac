package io.github.brrenat.seekervault.connections

import com.google.protobuf.ByteString
import io.github.brrenat.seekervault.request.v1.Action
import io.github.brrenat.seekervault.request.v1.ActionRequest
import io.github.brrenat.seekervault.request.v1.Approval
import io.github.brrenat.seekervault.request.v1.SignMessageAction
import io.github.brrenat.seekervault.request.v1.approval
import java.security.MessageDigest

/**
 * What a `sign_message` request asks for (docs/protocol.md#actions), read from the request the
 * sidecar sent. The phone works from these bytes and nothing else: it never re-encodes, trims, or
 * normalizes the message, because the wallet signs exactly what the agent sent.
 */
fun SignMessageAction.messageBytes(): ByteString =
    when (contentCase) {
        // Protobuf already holds the text's UTF-8 encoding; this reproduces it byte for byte.
        SignMessageAction.ContentCase.TEXT -> ByteString.copyFromUtf8(text)
        SignMessageAction.ContentCase.DATA -> data
        else -> ByteString.EMPTY
    }

/** The request's message, or null when it isn't a message-signing request. */
fun ActionRequest.signMessage(): SignMessageAction? =
    if (action.kindCase == Action.KindCase.SIGN_MESSAGE) action.signMessage else null

/**
 * The owner's approval of exactly what they reviewed: the SHA-256 of the message bytes, with
 * `prepared_version` 0, since a message has nothing prepared. The sidecar refuses an approval whose
 * hash isn't the request's own, so a changed message can never be carried by an old approval.
 */
fun ActionRequest.messageApproval(): Approval? {
    val message = signMessage() ?: return null
    val digest = MessageDigest.getInstance("SHA-256").digest(message.messageBytes().toByteArray())
    return approval {
        preparedVersion = 0
        contentHash = ByteString.copyFrom(digest)
    }
}
