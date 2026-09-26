package io.github.brrenat.seekervault.plugins.actions

import java.net.URI
import java.net.URISyntaxException

/**
 * The one rule for a destination a publisher may name (SEE-157).
 *
 * A publisher names where its provider's own app and site keep this operation, so the owner can
 * carry on there instead of in a browser that has to be pointed at the right page. That is the
 * whole of what these are for, and it is the first thing in the prediction payload that is meant to
 * leave this app at all — so what may be written here is bounded here, once, in core, before any
 * provider is consulted.
 *
 * ## What this rule is, and what it is not
 *
 * It is the *shape* check: a bounded absolute URI, a scheme an app may claim, a host when the
 * scheme is one the web uses, and nothing that smuggles a second destination or a credential past
 * a reader. It says nothing about whose link it is.
 *
 * **Whose link it is belongs to the provider**, which is the half that matters and the half core
 * cannot do: a link that passes this rule and points somewhere other than the provider's own
 * property is a publisher sending the owner wherever it likes, and it is the adapter that knows
 * what "its own" means (`JupiterPredictionAction.destinations`). Core reads the field; the provider
 * decides whether it is real (docs/wiki/jupiter-prediction.md#where-the-owner-continues).
 *
 * ## Why these schemes
 *
 * `https` is the web, and a native app claims its own web addresses through Android App Links, so
 * an ordinary `https` address is also how most providers' apps are opened. A private scheme —
 * `something:` — is the other way an app claims a destination, and is allowed for the providers
 * that publish one.
 *
 * Everything else is refused, and the refusals are the point: `http` is the same page without the
 * guarantee, and `javascript`, `data`, `file`, `content` and `intent` are not destinations at all —
 * they are code, inline content, this phone's own storage, another app's private storage, and, in
 * `intent`'s case, an arbitrary component with arbitrary extras. None of them is somewhere to
 * continue an order, and a publisher naming one is not offering navigation.
 */
fun isProviderLink(value: String): Boolean {
    if (value.isEmpty() || value.length > MOST_LINK_LENGTH) return false
    // A space or a control character means the value was assembled rather than written, and a
    // reader that trims one is a reader two programs disagree about.
    if (value.any { it.isWhitespace() || it.isISOControl() }) return false
    val uri =
        try {
            URI(value)
        } catch (e: URISyntaxException) {
            return false
        }
    if (!uri.isAbsolute) return false
    val scheme = uri.scheme.lowercase()
    if (!SCHEME.matches(scheme) || scheme in REFUSED_SCHEMES) return false
    // A credential in a URL is either a leak or a lure, and no destination needs one.
    if (uri.rawUserInfo != null) return false
    return if (scheme in WEB_SCHEMES) {
        // The web's schemes are meaningless without an authority, and an address whose host this
        // build cannot read is one it cannot tell a provider's own property from.
        uri.host != null && uri.host.isNotEmpty()
    } else {
        // A private scheme is the app's own, and its shape is the app's own too. The one thing
        // asked of it is that there is something after the colon to open.
        value.length > scheme.length + 1
    }
}

/**
 * Whether [value] is an `https` address whose host is [host] or a subdomain of it.
 *
 * The check an adapter makes about its own property, written here because it is the same check for
 * every one of them and because getting it wrong — matching the end of the string, say, and so
 * accepting `notjup.ag` — is the whole of the attack.
 */
fun isSecureLinkTo(value: String, host: String): Boolean {
    if (!isProviderLink(value)) return false
    val uri =
        try {
            URI(value)
        } catch (e: URISyntaxException) {
            return false
        }
    if (uri.scheme?.lowercase() != "https") return false
    val named = uri.host?.lowercase() ?: return false
    val own = host.lowercase()
    return named == own || named.endsWith(".$own")
}

/**
 * The longest link that may be written. A proposal value is capped at 512 bytes by the protocol;
 * this is the same bound said in the one place a link is read, so a document that is within the
 * protocol and outside this rule is refused for the reason it actually broke.
 */
const val MOST_LINK_LENGTH: Int = 512

private val SCHEME = Regex("""[a-z][a-z0-9+.-]{0,31}""")

/** The schemes whose meaning is "the web", and which therefore must name a host. */
private val WEB_SCHEMES = setOf("https")

/** Not destinations. See the rule above for why each one is here. */
private val REFUSED_SCHEMES =
    setOf("http", "javascript", "data", "file", "content", "intent", "android-app", "jar", "about")
