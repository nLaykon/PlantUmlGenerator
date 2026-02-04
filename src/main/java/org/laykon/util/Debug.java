package org.laykon.util;

public final class Debug {

    private static boolean enabled = false;
    private static long lastNanos = 0L;

    private Debug() {
    }

    public static void setEnabled(boolean on) {
        enabled = on;
        if (on) {
            lastNanos = System.nanoTime();
        }
    }

    public static void log(String message) {
        if (enabled) {
            long now = System.nanoTime();
            long elapsedMs = (now - lastNanos) / 1_000_000L;
            lastNanos = now;
            System.out.println("[debug +" + elapsedMs + "ms] " + message);
        }
    }
}
