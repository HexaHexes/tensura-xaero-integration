package org.bitwisemadness.tensuraxaerointegration.net;

/**
 * Hard wire limits, independent of config.
 *
 * <p>Config can only make payloads smaller than these caps, never larger: a
 * decoder rejects a count above the limit before allocating, so a malformed or
 * hostile packet cannot make the client or server allocate an arbitrary array.</p>
 */
public final class NetworkLimits {

    /** Maximum chunk entries in one subscribe/unsubscribe payload. */
    public static final int MAX_SUBSCRIPTION_ENTRIES = 512;

    /** Maximum chunk entries in one server-to-client update payload. */
    public static final int MAX_UPDATE_ENTRIES = 512;

    private NetworkLimits() {
    }
}
