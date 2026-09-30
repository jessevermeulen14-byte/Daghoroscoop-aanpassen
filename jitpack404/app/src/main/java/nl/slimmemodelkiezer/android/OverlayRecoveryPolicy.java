package nl.slimmemodelkiezer.android;

/** Pure state policy; a stalled send never silently resubmits the original text. */
final class OverlayRecoveryPolicy {
    static final long RECOVERY_DELAY_MS = 6000L;
    private OverlayRecoveryPolicy() {}

    /** IME focus must not hide ChatGPT when its application window is still visible. */
    static boolean chatVisibleBehindKeyboard(boolean chatWindowPresent,
                                             boolean anotherAppForeground) {
        return chatWindowPresent && !anotherAppForeground;
    }

    /** A native 24px icon must never shrink the legible SLIM button. */
    static int visibleButtonSize(int minimumPx, int nativePx) {
        return Math.max(1, Math.max(minimumPx, nativePx));
    }

    /** Screen-level button stays above the composer, never in ChatGPT's overflow menu.
     * Both a one-letter and a long prompt use the same geometry. */
    static int floatingButtonY(int screenHeight, int buttonSize, int margin,
                               int composerTop, int gap, int fallbackBottomOffset) {
        int target = composerTop >= 0 ? composerTop - buttonSize - gap
            : screenHeight - buttonSize - fallbackBottomOffset;
        return Math.max(margin, Math.min(screenHeight - buttonSize - margin, target));
    }

    static boolean showSmartButton(boolean enabled, boolean foreground,
                                   boolean processing, boolean dispatched,
                                   long bypassUntil, long now) {
        return enabled && foreground && !processing && !dispatched && now >= bypassUntil;
    }

    static boolean showManualRecovery(boolean enabled, boolean foreground,
                                      boolean processing, boolean dispatched,
                                      long dispatchedAt, long now) {
        return enabled && foreground && !processing && dispatched && dispatchedAt > 0L &&
            now - dispatchedAt >= RECOVERY_DELAY_MS;
    }
}
