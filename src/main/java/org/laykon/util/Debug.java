package org.laykon.util;

public final class Debug {

    private static boolean enabled = false;

    private Debug() {
    }

    public static void setEnabled(boolean on) {
        enabled = on;
    }

    public static void log(String message) {
        if (enabled) {
            System.out.println("[debug] " + message);
        }
    }
}
