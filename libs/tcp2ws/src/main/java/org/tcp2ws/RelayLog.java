package org.tcp2ws;

/**
 * Log sink for the relay.
 *
 * The relay used to report every failure with `System.out.println` and
 * `Throwable.printStackTrace()`, i.e. to the process stdout. On Android stdout is
 * not collected by the app's own log file, so a user who reports "the built-in ws
 * proxy keeps reconnecting" has no way to say *why*: the reason (upstream domain
 * unreachable, Worker returned 5xx, unsupported destination, TLS failure) is
 * written into a stream nobody reads.
 *
 * The module therefore has no logging dependency of its own - it exposes this
 * sink, and the embedding app installs its own logger once at start-up
 * ([tw.nekomimi.nekogram.helpers.WebSocketHelper] wires it to
 * `org.telegram.messenger.FileLog`). Without an installed logger the relay stays
 * silent, which keeps this library usable outside the app.
 */
public final class RelayLog {

    /** Sink implemented by the host application. */
    public interface Logger {
        void d(String message);

        void e(String message, Throwable error);
    }

    private static volatile Logger logger;

    private RelayLog() {
    }

    /** Installs the host logger. Pass null to fall back to silence. */
    public static void setLogger(Logger sink) {
        logger = sink;
    }

    public static void d(String message) {
        final Logger sink = logger;
        if (sink != null) {
            try {
                sink.d(message);
            } catch (Throwable ignored) {
                // A failing log call must never take a relay thread down.
            }
        }
    }

    public static void e(String message, Throwable error) {
        final Logger sink = logger;
        if (sink != null) {
            try {
                sink.e(message, error);
            } catch (Throwable ignored) {
                // See above.
            }
        }
    }

    /** True when a logger is installed; lets callers skip building messages. */
    public static boolean isEnabled() {
        return logger != null;
    }
}
