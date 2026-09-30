package nl.slimmemodelkiezer.android;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.graphics.Path;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import android.widget.TextView;
import android.widget.Toast;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Experimental, opt-in send interception. A visible accessibility overlay catches the user's
 * Send tap BEFORE ChatGPT can send. Never automatically transmits a draft if model selection
 * cannot be verified. Nothing is uploaded, persisted, or read from other packages.
 */
public final class ChatGptAccessibilityService extends AccessibilityService {
    private String activePackage = "";
    private final Handler main = new Handler(Looper.getMainLooper());
    private WindowManager windows;
    private TextView sendOverlay;
    private WindowManager.LayoutParams overlayParams;
    // Separate, always-visible green button beside the composer when native Send is covered.
    private TextView floatingOverlay;
    private WindowManager.LayoutParams floatingParams;
    private String draft = "";
    private ModelRule.Level target = ModelRule.Level.INSTANT;
    private boolean processing = false;
    private long bypassUntil = 0L;
    private long deadline = 0L;
    private int verificationAttempts = 0;
    private boolean menuInspection = false;
    private boolean screenHeaderAttempted = false;
    private boolean screenMenuAttempted = false;
    private boolean overflowAttempted = false;
    private boolean configureAttempted = false;
    private long startedAt = 0L;
    private String seenChoices = "";
    private String verifiedLabel = "";
    private String clickedChoice = "";
    private final Runnable refreshTask = this::refreshOverlay;
    // Keep checking while the service is alive: some ChatGPT versions do not emit
    // a usable window event after returning from Android accessibility settings.
    private final Runnable visibilityPoll = new Runnable() {
        @Override public void run() {
            // Independent watchdog: a missed accessibility callback must not leave
            // the floating button hidden forever after an interrupted attempt.
            if (processing && SystemClock.uptimeMillis() >= deadline) {
                HistoryStore.diagnostic(ChatGptAccessibilityService.this, "SELECTION_TIMEOUT");
                fail("Modelkeuze verlopen. Je vraag blijft staan.");
                return;
            }
            // Poll the actual ChatGPT window even when Android never delivered a
            // ChatGPT event (common after toggling the service or showing the keyboard).
            // Previously chatForeground stayed false, so the green button never appeared.
            if (enabled() && !processing) {
                RemoteRules.refresh(ChatGptAccessibilityService.this, false);
                chatRoot();
                refreshOverlay();
            }
            main.postDelayed(this, chatForeground ? 350L : 900L);
        }
    };
    private boolean firstCharacterRequestLogged = false;
    private String lastConnectionState = "";
    private boolean chatEventSeenInSession = false;
    private AccessibilityNodeInfo latestChatEventRoot;
    // A live ChatGPT editor remains usable when Samsung temporarily hides the window root.
    private AccessibilityNodeInfo latestLiveEditor;
    private long latestEditorEventAt = 0L;
    // ChatGPT Compose can invalidate its AccessibilityNode between the user's
    // last keystroke and the SLIM tap. Keep only the latest in-memory draft
    // observed from ChatGPT itself so an invalidated node does not dead-end the tap.
    private String latestDraftText = "";
    private boolean chatForeground = false;
    private long lastChatEventAt = 0L;
    private int pickerAttempts = 0;
    private boolean headerTapAttempted = false;
    private boolean keyboardDismissAttempted = false;
    private boolean sendDispatched = false;
    private long dispatchedAt = 0L;
    // Preserve button position during transient null roots and keyboard animations.
    private Rect lastComposerBounds;
    private final SendAttemptGate sendGate = new SendAttemptGate();
    private void connectionState(String state) {
        if (!state.equals(lastConnectionState)) {
            lastConnectionState = state;
            HistoryStore.diagnostic(this, state);
        }
    }

    @Override protected void onServiceConnected() {
        super.onServiceConnected();
        windows = (WindowManager) getSystemService(WINDOW_SERVICE);
        RemoteRules.initialize(this);
        // The Android accessibility permission is the sole on/off consent.
        // Reinstalling must not leave a second, hidden in-app switch disabled.
        getSharedPreferences("modelkiezer_settings", MODE_PRIVATE).edit()
            .putBoolean("enabled", true).commit();
        getSharedPreferences("connection_health", MODE_PRIVATE).edit()
            .putLong("service_connected_at", System.currentTimeMillis()).commit();
        HistoryStore.diagnostic(this, "SERVICE_CONNECTED");
        HistoryStore.diagnostic(this, "ACCESSIBILITY_SERVICE_READY_COMPOSE_CACHE_FIX_20260930_V7");
        processing = false;
        sendDispatched = false;
        dispatchedAt = 0L;
        sendGate.reset();
        firstCharacterRequestLogged = false;
        chatForeground = false;
        latestChatEventRoot = null;
        latestLiveEditor = null;
        latestEditorEventAt = 0L;
        latestDraftText = "";
        lastComposerBounds = null;
        main.post(this::refreshOverlay);
        main.removeCallbacks(visibilityPoll);
        // Check immediately after the service comes online, even before typing.
        main.post(visibilityPoll);
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null) return;
        if (event.getPackageName() == null) {
            main.removeCallbacks(refreshTask);
            main.postDelayed(refreshTask, 100L);
            return;
        }
        if (!enabled()) { connectionState("AUTOMATION_DISABLED"); removeOverlay(); return; }
        String eventPackage = event.getPackageName().toString();
        if (!SupportedAiApps.enabled(this, eventPackage)) {
            // Only a real foreground change may invalidate the cached ChatGPT root.
            if (event.getEventType() == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
                // IME/system windows also emit state changes. Check the actual foreground
                // application before invalidating a still-visible ChatGPT tree.
                // An input-method or system dialog is NOT an application switch.
                // Samsung and third-party keyboards have different package names.
                List<AccessibilityWindowInfo> currentWindows = getWindows();
                if (currentWindows != null) for (AccessibilityWindowInfo window : currentWindows) {
                    if (window.getType() != AccessibilityWindowInfo.TYPE_APPLICATION ||
                        (!window.isActive() && !window.isFocused())) continue;
                    AccessibilityNodeInfo appRoot = window.getRoot();
                    if (appRoot != null && appRoot.getPackageName() != null &&
                        !SupportedAiApps.enabled(this, appRoot.getPackageName().toString()) &&
                        !isCurrentKeyboard(appRoot.getPackageName().toString())) {
                        activePackage = "";
                        removeOverlay();
                        chatForeground = false;
                        latestChatEventRoot = null;
                        lastComposerBounds = null;
                    }
                    break;
                }
            }
            // Check ONLY foreground changes; never read content from any other app.
            if (event.getEventType() == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
                event.getEventType() == AccessibilityEvent.TYPE_WINDOWS_CHANGED) {
                main.removeCallbacks(refreshTask);
                main.postDelayed(refreshTask, 75L);
            }
            return;
        }
        // A conversation switch may replace the composer without first emitting
        // an empty text event. The old send was already dispatched; release its
        // gate so the next user-initiated SLIM tap works in the new conversation.
        if (sendDispatched && event.getEventType() == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
                && eventPackage.equals(activePackage)) {
            releaseCompletedAttempt();
            latestLiveEditor = null;
            latestChatEventRoot = null;
            HistoryStore.diagnostic(this, "CHAT_WINDOW_CHANGED_NEXT_SEND_READY");
        }
        // Samsung/ChatGPT can fail to expose a foreground root when the keyboard
        // owns focus. A visible, editable first-character event from the
        // selected AI app is enough to create our OWN Android overlay immediately.
        // This is not a button inserted into ChatGPT's interface.
        if (event.getEventType() == AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED) {
            AccessibilityNodeInfo firstEditor = event.getSource();
            if (firstEditor != null && firstEditor.isEditable() &&
                    firstEditor.isVisibleToUser() && firstEditor.getText() != null &&
                    firstEditor.getText().length() > 0 &&
                    firstEditor.getPackageName() != null &&
                    eventPackage.contentEquals(firstEditor.getPackageName())) {
                activatePackage(eventPackage);
                chatForeground = true;
                lastChatEventAt = SystemClock.uptimeMillis();
                Rect firstBounds = new Rect();
                firstEditor.getBoundsInScreen(firstBounds);
                if (!firstBounds.isEmpty()) lastComposerBounds = firstBounds;
                latestLiveEditor = firstEditor;
                latestEditorEventAt = SystemClock.uptimeMillis();
                latestDraftText = firstEditor.getText().toString();
                if (!menuInspection) showIndependentButton();
                HistoryStore.diagnostic(this, "FIRST_CHARACTER_NATIVE_OVERLAY_ATTEMPT");
            }
        }
        // Ignore late events from a background AI app; the foreground root is authoritative.
        AccessibilityNodeInfo eventForeground = chatRoot();
        if (eventForeground != null && eventForeground.getPackageName() != null &&
            !eventPackage.contentEquals(eventForeground.getPackageName())) return;
        activatePackage(eventPackage);
        chatForeground = true;
        lastChatEventAt = SystemClock.uptimeMillis();
        // Cache the live editor location BEFORE rendering the independent button.
        // This makes the first typed character position it above the composer,
        // without waiting for ChatGPT's expanding toolbar or native Send node.
        AccessibilityNodeInfo changed = event.getSource();
        if (changed != null && changed.isEditable() && changed.getPackageName() != null &&
            activePackage.contentEquals(changed.getPackageName())) {
            // Keep the actual composer from every text event, not just the first one.
            // A focused empty field in a later window snapshot can otherwise mask the draft.
            latestLiveEditor = changed;
            latestEditorEventAt = SystemClock.uptimeMillis();
            latestDraftText = changed.getText() == null ? "" : changed.getText().toString();
            Rect editorRect = new Rect();
            changed.getBoundsInScreen(editorRect);
            if (!editorRect.isEmpty()) lastComposerBounds = editorRect;
        }
        // Render the separate SLIM control synchronously on the FIRST character;
        // no waiting for ChatGPT's send icon or expansion toolbar.
        if (event.getEventType() == AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED &&
            changed != null && changed.getText() != null &&
            changed.getText().length() >= 1) {
            // Never fill the 100-entry audit trail with one entry per keystroke:
            // that erased the actual overlay success/failure diagnostics.
            if (floatingOverlay == null && !firstCharacterRequestLogged) {
                firstCharacterRequestLogged = true;
                HistoryStore.diagnostic(this, "SLIM_FIRST_CHARACTER_RENDER_REQUESTED");
            }
        }
        // An accessibility overlay is our own native Android window: create it
        // immediately, not as a child of ChatGPT's collapsible action menu.
        if (!menuInspection) showIndependentButton();
        // A real empty composer event is stronger evidence of a completed send
        // than the temporary loss of the active accessibility root.
        if (sendDispatched && changed != null && changed.isEditable() &&
            changed.getText() != null && !draft.isEmpty() &&
            changed.getText().length() == 0) {
            releaseCompletedAttempt();
            HistoryStore.diagnostic(this, "COMPOSER_CLEARED_NEXT_SEND_READY");
        } else if (sendDispatched && changed != null && changed.isEditable() &&
            changed.getText() != null && changed.getText().length() > 0 &&
            !draft.isEmpty() && !draft.contentEquals(changed.getText())) {
            // Opening another conversation does not always emit an empty-composer event.
            // A different non-empty draft proves the user has moved on, so a stale send
            // gate must never block the SLIM button in the new chat.
            releaseCompletedAttempt();
            HistoryStore.diagnostic(this, "NEW_DRAFT_RELEASED_STALE_SEND_GATE");
        }
        // On several ChatGPT Android versions the active-window API returns null
        // even though an event source exposes the full ChatGPT accessibility tree.
        AccessibilityNodeInfo source = event.getSource();
        if (source != null && source.getPackageName() != null &&
            activePackage.contentEquals(source.getPackageName())) {
            AccessibilityNodeInfo parent;
            int depth = 0;
            while (depth++ < 35 && (parent = source.getParent()) != null &&
                    parent.getPackageName() != null &&
                    activePackage.contentEquals(parent.getPackageName())) source = parent;
            latestChatEventRoot = source;
        }
        // Log the first real ChatGPT event once per service session, not on every keystroke.
        if (!chatEventSeenInSession) {
            chatEventSeenInSession = true;
            connectionState("AI_APP_EVENT_RECEIVED");
            getSharedPreferences("connection_health", MODE_PRIVATE).edit()
                .putLong("chatgpt_event_at", System.currentTimeMillis()).apply();
        }
        if (processing) return;
        // Never wait for an expanded editor or ChatGPT's overflow toolbar.
        // Show our own visible control on the very first text-change event.
        if (event.getEventType() == AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED && chatForeground) {
            showIndependentButton();
        }
        // Refresh the guarded send button on layout and draft changes.
        main.removeCallbacks(refreshTask);
        main.postDelayed(refreshTask,
            event.getEventType() == AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED ? 16L : 45L);
    }

    /** Switching providers discards all cached nodes and never transfers draft text. */
    private void activatePackage(String pkg) {
        if (!SupportedAiApps.enabled(this, pkg) || pkg.equals(activePackage)) return;
        if (processing || sendDispatched) {
            processing = false;
            sendDispatched = false;
            sendGate.reset();
            main.removeCallbacksAndMessages(null);
            main.postDelayed(visibilityPoll, 1200L);
        }
        removeOverlay();
        latestChatEventRoot = null;
        latestDraftText = "";
        lastComposerBounds = null;
        lastChatEventAt = 0L;
        chatForeground = false;
        activePackage = pkg;
        connectionState("ACTIVE_PROVIDER_" + SupportedAiApps.displayName(pkg).toUpperCase(Locale.ROOT));
    }

    private boolean enabled() {
        // Android accessibility permission is the sole required opt-in.
        return true;
    }

    /** Samsung keyboards occasionally appear as the focused application window. */
    private boolean isCurrentKeyboard(String pkg) {
        if (pkg == null || pkg.isEmpty()) return false;
        String ime = android.provider.Settings.Secure.getString(
            getContentResolver(), android.provider.Settings.Secure.DEFAULT_INPUT_METHOD);
        return ime != null && ime.startsWith(pkg + "/");
    }

    /** Prefer the foreground ChatGPT root; never stop at the first unrelated window. */
    private AccessibilityNodeInfo chatRoot() {
        AccessibilityNodeInfo active = getRootInActiveWindow();
        if (active != null && active.getPackageName() != null &&
            SupportedAiApps.enabled(this, active.getPackageName().toString())) {
            activatePackage(active.getPackageName().toString());
            chatForeground = true;
            return active;
        }
        List<AccessibilityWindowInfo> all = getWindows();
        if (all == null) {
            if (recentChatEditorEvent() && SupportedAiApps.enabled(this, activePackage))
                return eventRootFallback();
            return null;
        }
        // The keyboard can take input focus while the ChatGPT application
        // remains fully visible. Search its application window even if that
        // window temporarily reports neither focused nor active.
        AccessibilityNodeInfo chatCandidate = null;
        boolean otherForegroundApp = false;
        for (AccessibilityWindowInfo w : all) {
            if (w.getType() != AccessibilityWindowInfo.TYPE_APPLICATION) continue;
            AccessibilityNodeInfo root = w.getRoot();
            if (root == null || root.getPackageName() == null) continue;
            if (SupportedAiApps.enabled(this, root.getPackageName().toString())) {
                if (chatCandidate == null || w.isFocused() || w.isActive()) chatCandidate = root;
            } else if ((w.isFocused() || w.isActive()) &&
                    !isCurrentKeyboard(root.getPackageName().toString())) {
                otherForegroundApp = true;
            }
        }
        // An overlay, keyboard or Android system panel can own focus while
        // ChatGPT remains visible. A visible ChatGPT application window wins.
        if (chatCandidate != null) {
            activatePackage(chatCandidate.getPackageName().toString());
            chatForeground = true;
            return chatCandidate;
        }
        if (otherForegroundApp) {
            // Samsung may briefly report the IME/transition as an application.
            // Keep the independent button during a fresh ChatGPT text event,
            // then let the watchdog remove it if the user really switched apps.
            if (recentChatEditorEvent() &&
                    SystemClock.uptimeMillis() - lastChatEventAt < 1200L &&
                    SupportedAiApps.enabled(this, activePackage)) {
                chatForeground = true;
                connectionState("SLIM_TRANSIENT_FOREGROUND_GRACE");
                return eventRootFallback();
            }
            activePackage = "";
            removeOverlay();
            chatForeground = false;
            latestChatEventRoot = null;
            lastComposerBounds = null;
            return null;
        }
        // A cached ChatGPT event alone must not keep the button floating over
        // other apps indefinitely when Android stops reporting windows.
        if (!recentChatEditorEvent()) {
            activePackage = "";
            removeOverlay();
            chatForeground = false;
            latestChatEventRoot = null;
            return null;
        }
        // Root snapshots are sometimes missing while the keyboard opens.
        // The independent control needs only a recent event from the selected
        // AI app, not a full editable accessibility tree.
        if (SupportedAiApps.enabled(this, activePackage)) {
            chatForeground = true;
            return eventRootFallback();
        }
        return null;
    }

    private AccessibilityNodeInfo eventRootFallback() {
        if (chatForeground && latestChatEventRoot != null &&
            latestChatEventRoot.refresh() &&
            latestChatEventRoot.getPackageName() != null &&
            SupportedAiApps.enabled(this, activePackage) &&
            activePackage.contentEquals(latestChatEventRoot.getPackageName())) {
            return latestChatEventRoot;
        }
        return null;
    }

    /** Search ChatGPT-owned popup/dialog windows as well as the conversation root. */
    private List<AccessibilityNodeInfo> modelWindowNodes() {
        List<AccessibilityNodeInfo> result = flatten(chatRoot());
        // A freshly opened Compose bottom sheet can emit its own ChatGPT event
        // before getRootInActiveWindow/getWindows switch away from the composer.
        // Keep that event tree in the search, including its model rows.
        AccessibilityNodeInfo eventRoot = eventRootFallback();
        if (eventRoot != null) for (AccessibilityNodeInfo node : flatten(eventRoot)) {
            if (result.size() >= 750) break;
            if (!result.contains(node)) result.add(node);
        }
        List<AccessibilityWindowInfo> all = getWindows();
        if (all != null) for (AccessibilityWindowInfo window : all) {
            AccessibilityNodeInfo root = window.getRoot();
            if (root == null || !activePackage.contentEquals(root.getPackageName())) continue;
            for (AccessibilityNodeInfo node : flatten(root)) {
                if (result.size() >= 750) break;
                if (!result.contains(node)) result.add(node);
            }
        }
        return result;
    }

    /** Independent, visible button: does not depend on ChatGPT exposing its Send node. */
    private void refreshOverlay() {
        if (!enabled()) { connectionState("AUTOMATION_DISABLED"); removeOverlay(); return; }
        if (chatForeground && !SupportedAiApps.enabled(this, activePackage)) { activePackage = ""; chatForeground = false; removeOverlay(); }
        long now = SystemClock.uptimeMillis();
        // Preserve the interceptor during selection; its click handler rejects
        // concurrent taps through the SendAttemptGate.
        if (processing || (!sendDispatched && now < bypassUntil)) {
            // Never flash/disappear between two short messages or after a failed
            // selection. A guarded overlay remains visible during cooldown.
            if (chatForeground || recentChatEditorEvent()) {
                if (!menuInspection) showIndependentButton();
                if (sendOverlay != null) applyOverlayAppearance(sendOverlay);
            } else removeOverlay();
            return;
        }
        AccessibilityNodeInfo root = chatRoot();
        if (root == null) {
            connectionState("CHATGPT_ROOT_UNAVAILABLE");
            // Render the separate button independently of the official
            // ChatGPT editor tree. A missing editor may prevent safe sending,
            // but must not hide the control itself.
            if (recentChatEditorEvent() && SupportedAiApps.enabled(this, activePackage)) {
                chatForeground = true;
                showIndependentButton();
                removeSendOverlay();
                return;
            }
            // A temporary null root must not remove the only usable send control.
            if (chatForeground || recentChatEditorEvent()) {
                showIndependentButton();
                if (sendOverlay != null) applyOverlayAppearance(sendOverlay);
            } else removeOverlay();
            return;
        }
        // Cover the *real* send button when Android exposes it, so the usual
        // send tap triggers model selection before ChatGPT can submit. Only
        // overlap a semantically verified send node; never guess by coordinates.
        List<AccessibilityNodeInfo> nodes = flatten(root);
        AccessibilityNodeInfo nativeSend = findSend(nodes);
        AccessibilityNodeInfo textEditor = editor(nodes);
        // A successful native click keeps its gate until the composer clears
        // or its text changes, so a late callback cannot resend the same draft.
        if (sendDispatched && textEditor != null && textEditor.getText() != null &&
            !draft.equals(textEditor.getText().toString())) {
            releaseCompletedAttempt();
            HistoryStore.diagnostic(this, "COMPOSER_CHANGED_NEXT_SEND_READY");
        }
        if (sendDispatched) {
            // Preserve native Send coverage until the composer actually changes.
            showIndependentButton();
            if (sendOverlay != null) applyOverlayAppearance(sendOverlay);
            return;
        }
        Rect sendBounds = null;
        Rect editorBounds = null;
        if (textEditor != null && textEditor.isVisibleToUser()) {
            Rect bounds = new Rect();
            textEditor.getBoundsInScreen(bounds);
            if (!bounds.isEmpty()) {
                editorBounds = bounds;
                lastComposerBounds = new Rect(bounds);
            }
        }
        if (nativeSend != null && textEditor != null && textEditor.getText() != null
                && textEditor.getText().length() > 0) {
            Rect bounds = new Rect();
            nativeSend.getBoundsInScreen(bounds);
            if (!bounds.isEmpty()) sendBounds = bounds;
        }
        connectionState(sendBounds != null ? "NATIVE_SEND_INTERCEPTION_READY" :
            "NATIVE_SEND_NOT_ACCESSIBLE_MANUAL_BUTTON");
        // A short prompt (including "1 + 1") is enough. Do not wait for the
        // official ChatGPT composer to expand or reveal additional icons.
        positionOverlay(sendBounds, editorBounds);
    }

    private void positionOverlay(Rect sendBounds) {
        positionOverlay(sendBounds, null);
    }

    private void positionOverlay(Rect sendBounds, Rect editorBounds) {
        if (windows == null) { connectionState("WINDOW_MANAGER_UNAVAILABLE"); return; }
        int size = Math.round(RemoteRules.current().buttonSizeDp * getResources().getDisplayMetrics().density);
        int margin = Math.round(12 * getResources().getDisplayMetrics().density);
        android.util.DisplayMetrics display = getResources().getDisplayMetrics();
        if (overlayParams == null) {
            overlayParams = new WindowManager.LayoutParams(
                size, size,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE |
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL |
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                android.graphics.PixelFormat.TRANSLUCENT);
            overlayParams.gravity = Gravity.TOP | Gravity.LEFT;
        }
        // The normal send tap is intercepted when ChatGPT exposes its Send
        // node. Otherwise show an independent green manual fallback.
        boolean intercepting = sendBounds != null && RemoteRules.current().interceptNativeSend;
        // Keep a full-size, legible SLIM control even when the native send
        // accessibility bounds are a tiny icon; clamp around the verified button.
        int controlWidth = OverlayRecoveryPolicy.visibleButtonSize(size,
            intercepting ? sendBounds.width() : 0);
        int controlHeight = OverlayRecoveryPolicy.visibleButtonSize(size,
            intercepting ? sendBounds.height() : 0);
        overlayParams.x = intercepting
            ? Math.max(0, Math.min(display.widthPixels - controlWidth,
                sendBounds.centerX() - controlWidth / 2))
            : margin;
        // Keep the independent button beside the composer for one-character
        // messages too. Avoid guessing or covering an unverified native Send.
        overlayParams.y = intercepting
            ? Math.max(0, sendBounds.centerY() - controlHeight / 2)
            : Math.min(Math.max(margin, display.heightPixels - size - margin),
                editorBounds != null ? Math.max(margin, editorBounds.top - size - margin) :
                    Math.max(margin, display.heightPixels - size - Math.round(120 * display.density)));
        overlayParams.width = controlWidth;
        overlayParams.height = controlHeight;
        // The independent button is the primary control, whether or not native
        // Send is visible, and must never sit inside ChatGPT's expanding toolbar.
        showIndependentButton();
        if (!intercepting) {
            removeSendOverlay();
            return;
        }
        if (sendOverlay == null) {
            TextView button = new TextView(this);
            button.setText(RemoteRules.current().buttonLabel);
            button.setTextColor(0xffffffff);
            button.setTextSize(sendDispatched ? 10 : 13);
            button.setGravity(Gravity.CENTER);
            button.setContentDescription("Slim verzenden. Kies eerst het model en verstuur daarna het bericht.");
            button.setBackgroundColor(0xff156347);
            button.setOnClickListener(v -> onOverlayTapped());
            applyOverlayAppearance(button);
            sendOverlay = button;
            try {
                windows.addView(sendOverlay, overlayParams);
                HistoryStore.diagnosticDetail(this, "SLIM_OVERLAY_CREATED",
                    (intercepting ? "NATIVE_SEND" : "COMPOSER_FALLBACK") +
                    "_W" + overlayParams.width + "_H" + overlayParams.height);
            } catch (RuntimeException e) {
                sendOverlay = null;
                HistoryStore.diagnosticFailure(this, "OVERLAY_CREATE_FAILED", e);
                connectionState("OVERLAY_CREATE_FAILED");
            }
        } else {
            try {
                applyOverlayAppearance(sendOverlay);
                windows.updateViewLayout(sendOverlay, overlayParams);
            }
            catch (RuntimeException e) { HistoryStore.diagnosticFailure(this, "OVERLAY_UPDATE_FAILED", e); connectionState("OVERLAY_UPDATE_FAILED"); removeOverlay(); }
        }
        // The independent button was already positioned regardless of native Send.
    }

    /** Stable standalone control; independent of prompt length, editor height,
     * ChatGPT's shortcut menu, and whether the native Send node is exposed. */
    private boolean recentChatEditorEvent() {
        return (lastChatEventAt > 0L &&
            SystemClock.uptimeMillis() - lastChatEventAt < 15000L) ||
            recentLiveEditor() != null;
    }

    private void showIndependentButton() {
        // Accessibility events can arrive while the model sheet is open.
        // Recreating this overlay covers the Luna row and defeats screen OCR.
        if (menuInspection) {
            removeFloatingButton();
            return;
        }
        // Once an actual ChatGPT text event has been received, keyboard focus
        // changes must not suppress the independent accessibility button.
        if (!enabled()) return;
        if (windows == null) {
            connectionState("SLIM_WINDOW_MANAGER_MISSING");
            return;
        }
        if (!SupportedAiApps.enabled(this, activePackage) || (!chatForeground && !recentChatEditorEvent())) {
            connectionState("SLIM_NO_VISIBLE_CHATGPT_WINDOW");
            return;
        }
        android.util.DisplayMetrics metrics = getResources().getDisplayMetrics();
        int size = Math.round(RemoteRules.current().buttonSizeDp * metrics.density);
        int margin = Math.round(12 * metrics.density);
        positionFloatingButton(size, margin, metrics);
    }

    private void positionFloatingButton(int size, int margin,
                                        android.util.DisplayMetrics display) {
        if (windows == null) return;
        if (floatingParams == null) {
            floatingParams = new WindowManager.LayoutParams(size, size,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE |
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL |
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                android.graphics.PixelFormat.TRANSLUCENT);
            floatingParams.gravity = Gravity.TOP | Gravity.LEFT;
        }
        floatingParams.width = size;
        floatingParams.height = size;
        // A separate screen-level overlay, never a child of ChatGPT's
        // collapsible actions menu. Keep it directly ABOVE the input row,
        // including when only one character has been typed.
        // A fixed LEFT-side control cannot be concealed by ChatGPT's right-side
        // attachment/overflow actions, even in the compact one-character composer.
        // Build 23: a deterministic, fixed SCREEN-level control. The old
        // composer/keyboard geometry could put the button outside the user's
        // visible ChatGPT area on Samsung and expanded composer variants.
        // It must remain visible even when a ONE-character composer exposes
        // no editor bounds and the ChatGPT toolbar is collapsed.
        // Place it below the status/header area, away from the IME and
        // independently of ChatGPT's overflow/expansion toolbar.
        // Use the LEFT of the conversation, OUTSIDE ChatGPT's expandable right-hand
        // composer toolbar. Do not depend on the native send button or text length.
        // Fixed screen coordinates keep the control tappable when the IME opens.
        RemoteRules.Rules remote = RemoteRules.current();
        floatingParams.x = Math.min(Math.max(0, display.widthPixels - size),
            Math.round(remote.buttonXdp * display.density));
        floatingParams.y = Math.min(Math.max(margin, display.heightPixels - size - margin),
            Math.round(remote.buttonYdp * display.density));
        if (floatingOverlay == null) {
            TextView button = new TextView(this);
            button.setText(RemoteRules.current().buttonLabel);
            button.setTextColor(0xffffffff);
            button.setTextSize(13);
            button.setGravity(Gravity.CENTER);
            button.setBackgroundColor(0xff156347);
            button.setContentDescription("SLIM, eigen groene verzendknop, ook voor één teken.");
            button.setClickable(true);
            button.setFocusable(false);
            button.setOnClickListener(v -> onOverlayTapped());
            applyOverlayAppearance(button);
            try {
                windows.addView(button, floatingParams);
                floatingOverlay = button;
                firstCharacterRequestLogged = false;
                HistoryStore.diagnosticDetail(this, "SLIM_FLOATING_BUTTON_VISIBLE",
                    "x=" + floatingParams.x + ",y=" + floatingParams.y +
                    ",size=" + size + ",screen=" + display.widthPixels + "x" + display.heightPixels);
            } catch (RuntimeException e) {
                HistoryStore.diagnosticFailure(this, "SLIM_FLOATING_BUTTON_FAILED", e);
            }
        } else {
            try {
                floatingOverlay.setClickable(true);
                floatingOverlay.setOnClickListener(v -> onOverlayTapped());
                applyOverlayAppearance(floatingOverlay);
                windows.updateViewLayout(floatingOverlay, floatingParams);
            }
            catch (RuntimeException e) {
                HistoryStore.diagnosticFailure(this, "SLIM_FLOATING_BUTTON_UPDATE_FAILED", e);
                removeFloatingButton();
            }
        }
    }

    /** Find the visible input method only; never inspect the keyboard's content. */
    private int fallbackComposerTopAboveKeyboard(android.util.DisplayMetrics display) {
        List<AccessibilityWindowInfo> all = getWindows();
        if (all == null) return -1;
        for (AccessibilityWindowInfo w : all) {
            if (w.getType() != AccessibilityWindowInfo.TYPE_INPUT_METHOD) continue;
            Rect keyboard = new Rect();
            w.getBoundsInScreen(keyboard);
            if (keyboard.isEmpty() || keyboard.top <= 0) continue;
            // Reserve space for the native ChatGPT input row. Keep SLIM above it.
            return Math.max(0, keyboard.top - Math.round(76 * display.density));
        }
        return -1;
    }

    private void removeFloatingButton() {
        if (floatingOverlay != null && windows != null) {
            try { windows.removeViewImmediate(floatingOverlay); }
            catch (RuntimeException ignored) { }
            floatingOverlay = null;
        }
    }

    private void applyOverlayAppearance(TextView button) {
        long now = SystemClock.uptimeMillis();
        boolean recovery = sendDispatched && OverlayRecoveryPolicy.showManualRecovery(
            enabled(), chatForeground, processing, true, dispatchedAt, now);
        if (processing) {
            button.setText("KIEZEN");
            button.setTextSize(10);
            button.setBackgroundColor(0xff156347);
            button.setContentDescription("Modelkeuze bezig. Je bericht is nog niet verzonden.");
        } else if (sendDispatched) {
            button.setText(recovery ? "HERSTEL" : "WACHT");
            button.setTextSize(10);
            button.setBackgroundColor(recovery ? 0xffa05a00 : 0xff156347);
            button.setContentDescription(recovery
                ? "Verzending niet bevestigd. Tik om de knop te herstellen zonder opnieuw te verzenden."
                : "Verzendklik uitgevoerd. Wacht op bevestiging; niet opnieuw versturen.");
        } else {
            button.setText(RemoteRules.current().buttonLabel);
            button.setTextSize(13);
            button.setBackgroundColor(0xff156347);
            button.setContentDescription(now < bypassUntil
                ? "Slim verzenden is zo opnieuw beschikbaar."
                : "Slim verzenden. Kies het model en verstuur eenmaal.");
        }
    }

    private void onOverlayTapped() {
        // An immediate marker separates "Android did not deliver the tap" from
        // "tap arrived but model selection / ChatGPT accessibility failed".
        HistoryStore.diagnostic(this, "SLIM_BUTTON_TAP_RECEIVED");
        // Refresh remote rules asynchronously; the current tap never waits for network.
        // A successful refresh is used immediately by later steps/taps, while cached rules
        // keep this action deterministic when offline.
        RemoteRules.refresh(this, false);
        // Confirm a REAL Android click immediately, before inspecting the
        // ChatGPT accessibility tree or attempting a model change.
        if (RemoteRules.current().tapFeedback)
            Toast.makeText(this, "SLIM: tik ontvangen", Toast.LENGTH_SHORT).show();
        if (!enabled()) {
            HistoryStore.diagnostic(this, "SLIM_TAP_AUTOMATION_DISABLED");
            Toast.makeText(this, "Zet Automatisch slim verzenden aan in Slimme Modelkiezer.", Toast.LENGTH_LONG).show();
            return;
        }
        if (processing) {
            Toast.makeText(this, "Modelkeuze bezig.", Toast.LENGTH_SHORT).show();
            return;
        }
        if (sendDispatched) {
            if (OverlayRecoveryPolicy.showManualRecovery(
                    enabled(), chatForeground, false, true, dispatchedAt,
                    SystemClock.uptimeMillis())) {
                HistoryStore.diagnostic(this, "MANUAL_RECOVERY_PREVIOUS_SEND_UNCONFIRMED");
                releaseCompletedAttempt();
                Toast.makeText(this,
                    "Knop hersteld. Controleer eerst of het vorige bericht is verzonden.",
                    Toast.LENGTH_LONG).show();
            } else {
                Toast.makeText(this, "Vorige verzendactie nog niet bevestigd.",
                    Toast.LENGTH_SHORT).show();
            }
            return;
        }
        if (SystemClock.uptimeMillis() < bypassUntil) {
            HistoryStore.diagnostic(this, "SLIM_TAP_SHORT_COOLDOWN");
            Toast.makeText(this, "Probeer SLIM opnieuw.", Toast.LENGTH_SHORT).show();
            return;
        }
        // A cached, visible editor from a fresh accessibility event can survive
        // an Android/Compose root disappearing while the keyboard has focus.
        AccessibilityNodeInfo foreground = chatRoot();
        boolean liveForeground = foreground != null && foreground.getPackageName() != null
            && activePackage.contentEquals(foreground.getPackageName());
        boolean liveEditor = recentLiveEditor() != null;
        if (!SupportedAiApps.enabled(this, activePackage) ||
                (!liveForeground && !liveEditor)) {
            HistoryStore.diagnostic(this, "SLIM_TAP_NO_ENABLED_AI_FOREGROUND_ROOT");
            Toast.makeText(this, "ChatGPT-invoerveld niet bereikbaar; open het gesprek en typ opnieuw.", Toast.LENGTH_LONG).show();
            return;
        }
        interceptSend();
    }

    private AccessibilityNodeInfo recentLiveEditor() {
        if (!SupportedAiApps.enabled(this, activePackage) ||
                latestLiveEditor == null) return null;
        try {
            if (latestLiveEditor.refresh() && latestLiveEditor.isEditable() &&
                    latestLiveEditor.isVisibleToUser() &&
                    latestLiveEditor.getPackageName() != null &&
                    activePackage.contentEquals(latestLiveEditor.getPackageName()))
                return latestLiveEditor;
        } catch (RuntimeException ignored) { }
        return null;
    }

    /** A text-change event from the foreground AI app is stronger than a stale
     * Compose node. The cache is memory-only, package-scoped and short-lived. */
    private boolean recentCachedDraft(String expected) {
        if (!SupportedAiApps.enabled(this, activePackage) || expected == null ||
                expected.isEmpty() || !expected.equals(latestDraftText)) return false;
        long age = SystemClock.uptimeMillis() - latestEditorEventAt;
        return latestEditorEventAt > 0L && age >= 0L && age <= 120000L &&
            lastComposerBounds != null && !lastComposerBounds.isEmpty();
    }

    private void interceptSend() {
        if (processing || !sendGate.begin()) {
            HistoryStore.diagnostic(this, "SLIM_TAP_BLOCKED_BY_SEND_GATE");
            Toast.makeText(this, "Vorige verzendpoging is nog bezig. Controleer het logboek.", Toast.LENGTH_LONG).show();
            return;
        }
        AccessibilityNodeInfo root = chatRoot();
        List<AccessibilityNodeInfo> nodes = flatten(root);
        AccessibilityNodeInfo editor = editor(nodes);
        if (editor == null || editor.getText() == null) {
            // ChatGPT sometimes gives Android a live text-change source but
            // omits the composer from a subsequent active-window snapshot.
            // Use only a recent, ChatGPT-owned event tree, never another app.
            AccessibilityNodeInfo recent = eventRootFallback();
            if (recent != null) editor = editor(flatten(recent));
        }
        if (editor == null || editor.getText() == null ||
                editor.getText().toString().trim().isEmpty()) {
            AccessibilityNodeInfo live = recentLiveEditor();
            if (live != null && live.getText() != null &&
                    !live.getText().toString().trim().isEmpty()) editor = live;
        }
        if (editor == null || editor.getText() == null) {
            // Compose frequently invalidates the editor node while our own overlay
            // remains tappable. Use the most recent ChatGPT-owned text-change value
            // instead of failing before model selection.
            if (latestDraftText != null && !latestDraftText.trim().isEmpty() &&
                    recentCachedDraft(latestDraftText)) {
                draft = latestDraftText;
                HistoryStore.diagnostic(this, "SLIM_TAP_USING_CACHED_COMPOSER_TEXT");
            } else {
                sendGate.reset();
                HistoryStore.diagnostic(this, root == null ? "SLIM_TAP_CHATGPT_ROOT_UNAVAILABLE" : "SLIM_TAP_EDITOR_NOT_ACCESSIBLE");
                Toast.makeText(this, "Het tekstvak van de AI-app is niet toegankelijk. Open een gesprek en typ je bericht.", Toast.LENGTH_LONG).show();
                return;
            }
        } else {
            draft = editor.getText().toString();
        }
        if (draft.trim().isEmpty()) {
            sendGate.reset();
            HistoryStore.diagnostic(this, "EMPTY_DRAFT");
            Toast.makeText(this, "Typ eerst je bericht in ChatGPT.", Toast.LENGTH_SHORT).show();
            return;
        }
        HistoryStore.diagnostic(this, "SMART_SEND_TAPPED");
        startedAt = SystemClock.uptimeMillis();
        sendDispatched = false;
        dispatchedAt = 0L;
        pickerAttempts = 0;
        verificationAttempts = 0;
        menuInspection = false;
        screenHeaderAttempted = false;
        screenMenuAttempted = false;
        overflowAttempted = false;
        configureAttempted = false;
        headerTapAttempted = false;
        keyboardDismissAttempted = false;
        seenChoices = "";
        verifiedLabel = "";
        clickedChoice = "";
        target = ModelRule.choose(draft); // Re-evaluate the ENTIRE text at tap time.
        HistoryStore.diagnostic(this, "ROUTED_" + target.name());
        processing = true;
        // Keep the overlay over the native Send control while selecting a model.
        // Removing it here would expose an unguarded normal Send tap.
        if (sendOverlay != null) {
            sendOverlay.setText("...");
            sendOverlay.setClickable(false);
            sendOverlay.setContentDescription(
                "Slimme modelkeuze bezig. Wacht op controle voordat je opnieuw verzendt.");
        }
        // Give immediate visible feedback on the standalone green button too.
        // Earlier builds logged the tap but left this button looking unchanged,
        // which made a successful click appear to do nothing.
        if (floatingOverlay != null) {
            floatingOverlay.setText("...");
            floatingOverlay.setClickable(false);
            floatingOverlay.setContentDescription(
                "Slimme modelkeuze bezig. Model kiezen en daarna verzenden.");
        }
        // Opening Work and a Compose sheet can take several seconds on a real device.
        deadline = SystemClock.uptimeMillis() +
            RemoteRules.current().selectionTimeoutMs;
        main.removeCallbacks(visibilityPoll);
        main.postDelayed(visibilityPoll, 1200L);
        // ChatGPT is the primary supported provider. Its picker is recognized
        // semantically and by the dedicated ChatGPT fallbacks below, so never let
        // a stale remote modelPickerEnabled=false silently bypass model selection.
        // Other providers still require their remote picker definitions.
        RemoteRules.Rules.AppRules provider = RemoteRules.current().forPackage(activePackage);
        if (!SupportedAiApps.CHATGPT.equals(activePackage) && !provider.modelPickerEnabled) {
            HistoryStore.diagnostic(this, "MODEL_PICKER_DISABLED_SEND_CURRENT_MODEL");
            sendWithoutModelChange("PROVIDER_MODEL_PICKER_NOT_VERIFIED");
        } else {
            HistoryStore.diagnostic(this, "CHATGPT_MODEL_PICKER_FORCED");
            openPickerWithRetry();
        }
    }

    /** Wait for a real ChatGPT tree before declaring the model picker missing. */
    private void openPickerWithRetry() {
        if (!processing) return;
        AccessibilityNodeInfo root = chatRoot();
        List<AccessibilityNodeInfo> nodes = modelWindowNodes();
        AccessibilityNodeInfo picker = findPicker(nodes);
        if (picker == null) {
            // The Samsung keyboard can own the accessibility window and hide
            // ChatGPT's top bar. Close only that keyboard, then read a fresh
            // ChatGPT window instead of failing before its animation finishes.
            if (!keyboardDismissAttempted && SupportedAiApps.CHATGPT.equals(activePackage)
                    && inputMethodWindowVisible()) {
                keyboardDismissAttempted = true;
                if (performGlobalAction(GLOBAL_ACTION_BACK)) {
                    HistoryStore.diagnostic(this, "PICKER_KEYBOARD_DISMISSED_RETRY");
                    main.postDelayed(this::openPickerWithRetry, 350L);
                    return;
                }
            }
            if (!headerTapAttempted && SupportedAiApps.CHATGPT.equals(activePackage)) {
                headerTapAttempted = true;
                AccessibilityNodeInfo workHeader = findModelHeaderFallback(nodes);
                if (workHeader != null && (clickUp(workHeader) || tapVisibleNode(workHeader))) {
                    HistoryStore.diagnostic(this, "MODEL_WORK_HEADER_ACTION_DISPATCHED");
                    // The floating overlay can cover the Luna row in the sheet.
                    // The guarded native Send interceptor remains active.
                    menuInspection = true;
                    removeFloatingButton();
                    main.postDelayed(this::selectFromMenu, 380L);
                    return;
                }
                HistoryStore.diagnostic(this, "MODEL_WORK_HEADER_UNAVAILABLE");
            }
            if (!screenHeaderAttempted && SupportedAiApps.CHATGPT.equals(activePackage)) {
                screenHeaderAttempted = true;
                menuInspection = true;
                removeFloatingButton();
                inspectScreenHeader();
                return;
            }
            if (pickerAttempts == 0) {
                HistoryStore.diagnostic(this, root == null ? "PICKER_INITIAL_NO_ROOT" : "PICKER_INITIAL_NO_MATCH");
                HistoryStore.diagnosticDetail(this, "PICKER_TREE", pickerDiagnostic(nodes));
                HistoryStore.diagnosticDetail(this, "WINDOWS", windowDiagnostic());
            }
            // Do not leave Send inert for five seconds when ChatGPT exposes no model menu.
            // Try the current model quickly; dispatchCheckedSend still validates the draft.
            if (++pickerAttempts < RemoteRules.current().pickerRetries && SystemClock.uptimeMillis() < deadline) {
                main.postDelayed(this::openPickerWithRetry, RemoteRules.current().pickerRetryDelayMs);
                return;
            }
            HistoryStore.diagnostic(this, root == null ? "CHATGPT_ROOT_UNAVAILABLE" :
                "MODEL_PICKER_NOT_FOUND");
            HistoryStore.diagnosticDetail(this, "PICKER_DIAG", pickerDiagnostic(nodes));
            if (RemoteRules.current().skipMissingPicker) sendWithoutModelChange("MODEL_PICKER_UNAVAILABLE");
            else fail("Modelmenu niet toegankelijk. Bericht staat nog klaar.");
            return;
        }
        // Only a positively identified mode, never a generic 'GPT' label,
        // is sufficient to skip opening the menu.
        if (pickerShowsTarget(picker, target)) {
            verifiedLabel = safeModelLabel(picker);
            clickedChoice = verifiedLabel;
            sendWhenVerified();
            return;
        }
        if (!clickUp(picker)) {
            HistoryStore.diagnostic(this, "MODEL_PICKER_CLICK_FAILED");
            sendWithoutModelChange("MODEL_PICKER_CLICK_FAILED_FALLBACK");
            return;
        }
        main.postDelayed(this::selectFromMenu, 140L);
    }

    private boolean inputMethodWindowVisible() {
        List<AccessibilityWindowInfo> all = getWindows();
        if (all == null) return false;
        for (AccessibilityWindowInfo w : all)
            if (w.getType() == AccessibilityWindowInfo.TYPE_INPUT_METHOD)
                return true;
        return false;
    }

    /** Counts and model-related vocabulary only: no draft, raw UI text or identifiers. */
    private static String pickerDiagnostic(List<AccessibilityNodeInfo> nodes) {
        int visible = 0, clickable = 0, idCount = 0, modelLabels = 0;
        for (AccessibilityNodeInfo n : nodes) {
            if (!n.isVisibleToUser()) continue;
            visible++;
            if (clickable(n)) clickable++;
            if (n.getViewIdResourceName() != null) idCount++;
            if (!safeModelLabel(n).isEmpty()) modelLabels++;
        }
        StringBuilder ids = new StringBuilder();
        for (AccessibilityNodeInfo n : nodes) {
            if (!n.isVisibleToUser() || n.getViewIdResourceName() == null) continue;
            String id = n.getViewIdResourceName();
            int separator = id.lastIndexOf('/');
            if (separator >= 0) id = id.substring(separator + 1);
            // Identifiers are structural; never record any UI text or prompts.
            id = id.replaceAll("[^A-Za-z0-9_]", "");
            if (id.isEmpty() || ids.indexOf(id) >= 0 || ids.length() + id.length() > 155) continue;
            if (ids.length() > 0) ids.append(",");
            ids.append(id);
        }
        return "V" + visible + "_C" + clickable + "_ID" + idCount + "_M" + modelLabels + "_" + ids;
    }

    private String windowDiagnostic() {
        List<AccessibilityWindowInfo> all = getWindows();
        if (all == null) return "UNAVAILABLE";
        StringBuilder result = new StringBuilder();
        for (AccessibilityWindowInfo w : all) {
            if (result.length() > 180) break;
            AccessibilityNodeInfo root = w.getRoot();
            String pkg = root == null || root.getPackageName() == null ? "none" :
                (activePackage.contentEquals(root.getPackageName()) ? "chatgpt" : "other");
            result.append("T").append(w.getType()).append("A").append(w.isActive() ? 1 : 0)
                .append("F").append(w.isFocused() ? 1 : 0).append("P").append(pkg).append(";");
        }
        return result.toString();
    }

    private void selectFromMenu() {
        if (!processing) return;
        if (SystemClock.uptimeMillis() >= deadline) {
            HistoryStore.diagnostic(this, seenChoices.isEmpty() ? "MODEL_OPTIONS_NOT_EXPOSED" : "MODEL_OPTION_NOT_FOUND");
            sendWithoutModelChange("MODEL_OPTION_UNAVAILABLE"); return;
        }
        List<AccessibilityNodeInfo> nodes = modelWindowNodes();
        seenChoices = findAvailableChoices(nodes);
        if (verificationAttempts++ == 0) {
            HistoryStore.diagnosticDetail(this, "MENU_TREE", pickerDiagnostic(nodes));
            HistoryStore.diagnosticDetail(this, "MENU_WINDOWS", windowDiagnostic());
            HistoryStore.diagnosticDetail(this, "MENU_MODELS", seenChoices);
        }
        AccessibilityNodeInfo choice = findChoice(nodes, target);
        if (choice != null && clickUp(choice)) {
            HistoryStore.diagnosticDetail(this, "MODEL_OPTION_ACTION_DISPATCHED",
                safeModelLabel(choice));
            clickedChoice = safeModelLabel(choice);
            verificationAttempts = 0;
            main.postDelayed(this::verifyThenSend, 65L);
        } else {
            if (!screenMenuAttempted && SupportedAiApps.CHATGPT.equals(activePackage)) {
                screenMenuAttempted = true;
                inspectScreenMenu();
                return;
            }
            main.postDelayed(this::selectFromMenu, 160L);
        }
    }

    /** Screen OCR is a local fallback when Compose does not expose its visible menu rows. */
    private void inspectScreenHeader() {
        if (!processing) return;
        ScreenModelReader.read(this, main, new ScreenModelReader.Callback() {
            @Override public void failure(String code) {
                HistoryStore.diagnostic(ChatGptAccessibilityService.this, code);
                sendWithoutModelChange("SCREEN_HEADER_CAPTURE_FAILED");
            }
            @Override public void success(android.graphics.Bitmap image,
                    List<ScreenModelReader.Item> items) {
                if (!processing) return;
                // A sheet may already be open after a semantic/header tap.
                if (hasMenuTitle(items)) {
                    HistoryStore.diagnostic(thisService(), "SCREEN_MENU_ALREADY_OPEN");
                    inspectScreenMenu();
                    return;
                }
                ScreenModelReader.Item header = screenHeaderItem(items, image);
                if (header == null) {
                    HistoryStore.diagnostic(thisService(), "SCREEN_MODEL_HEADER_NOT_FOUND");
                    sendWithoutModelChange("SCREEN_MODEL_HEADER_UNAVAILABLE");
                    return;
                }
                String value = header.text == null ? "" :
                    header.text.trim().toLowerCase(Locale.ROOT);
                HistoryStore.diagnostic(thisService(),
                    value.equals("work") ? "SCREEN_WORK_OBSERVED" : "SCREEN_MODEL_HEADER_OBSERVED");
                tapScreen(header.bounds.centerX(), header.bounds.centerY(),
                    () -> main.postDelayed(ChatGptAccessibilityService.this::selectFromMenu, 450L),
                    "Modelmenu kon niet worden geopend.");
            }
        });
    }

    /** Visible top-bar fallback for both a normal ChatGPT conversation and Work. */
    private static ScreenModelReader.Item screenHeaderItem(
            List<ScreenModelReader.Item> items, android.graphics.Bitmap image) {
        ScreenModelReader.Item generic = null;
        for (ScreenModelReader.Item item : items) {
            if (item.text == null || item.bounds == null ||
                    item.bounds.centerY() >= image.getHeight() / 4) continue;
            String text = item.text.trim().toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9. ]+", " ").replaceAll("\\s+", " ").trim();
            if (text.isEmpty()) continue;
            boolean explicit = text.equals("work") ||
                text.contains("6 luna") || text.contains("6 sol") || text.contains("6 astra") ||
                text.contains("5.6 luna") || text.contains("5.6 sol") || text.contains("5.6 astra") ||
                text.equals("instant") || text.equals("medium") || text.equals("standard") ||
                text.equals("high") || text.equals("extended") ||
                text.startsWith("gpt 5") || text.startsWith("gpt-5") ||
                text.startsWith("chatgpt 5");
            if (explicit && Math.abs(item.bounds.centerX() - image.getWidth() / 2) <
                    image.getWidth() * 0.44f) return item;
            if (generic == null && (text.equals("chatgpt") || text.equals("model")) &&
                    Math.abs(item.bounds.centerX() - image.getWidth() / 2) <
                    image.getWidth() * 0.44f) generic = item;
        }
        return generic;
    }

    private ChatGptAccessibilityService thisService() { return this; }

    private String screenTargetName() {
        if (target == ModelRule.Level.INSTANT) return "instant";
        if (target == ModelRule.Level.THINKING) return "medium";
        return "high";
    }

    /** Current ChatGPT builds can expose either reasoning-mode labels or
     * product/codename labels. Accept only aliases for the requested level. */
    private String[] screenTargetAliases() {
        if (target == ModelRule.Level.INSTANT)
            return new String[]{"instant", "luna", "5.6 luna", "6 luna"};
        if (target == ModelRule.Level.THINKING)
            return new String[]{"medium", "thinking medium", "thinking standard",
                "standard", "sol", "5.6 sol", "6 sol"};
        return new String[]{"high", "thinking high", "thinking extended",
            "extended", "astra", "5.6 astra", "6 astra"};
    }

    private ScreenModelReader.Item screenTargetItem(List<ScreenModelReader.Item> items) {
        for (String alias : screenTargetAliases()) {
            ScreenModelReader.Item item = screenItem(items, alias);
            if (item != null) return item;
        }
        return null;
    }

    private static ScreenModelReader.Item screenDoneItem(List<ScreenModelReader.Item> items) {
        for (String alias : new String[]{"klaar", "done", "gereed", "toepassen", "apply"}) {
            ScreenModelReader.Item item = screenItem(items, alias);
            if (item != null) return item;
        }
        return null;
    }

    private static ScreenModelReader.Item screenItem(List<ScreenModelReader.Item> items,
            String value) {
        String needle = value.toLowerCase(Locale.ROOT);
        for (ScreenModelReader.Item item : items)
            if (needle.equals(item.text.trim().toLowerCase(Locale.ROOT)))
                return item;
        // OCR can split the leading "6" from the distinctive model name.
        if (needle.startsWith("6 ")) {
            String name = needle.substring(2);
            for (ScreenModelReader.Item item : items) {
                String words = item.text.toLowerCase(Locale.ROOT)
                    .replaceAll("[^a-z0-9]+", " ").trim();
                if (words.equals(name) || words.equals("6 " + name))
                    return item;
            }
        }
        return null;
    }

    private static ScreenModelReader.Item screenConfigureItem(
            List<ScreenModelReader.Item> items) {
        for (ScreenModelReader.Item item : items) {
            String text = item.text == null ? "" :
                item.text.trim().toLowerCase(Locale.ROOT);
            if (text.equals("configureren") || text.equals("configure") ||
                text.equals("model configureren") || text.equals("configure model"))
                return item;
        }
        return null;
    }

    private static boolean hasMenuTitle(List<ScreenModelReader.Item> items) {
        if (screenConfigureItem(items) != null) return true;
        int modes = 0;
        for (String alias : new String[]{"instant", "medium", "standard", "high", "extended",
                "luna", "sol", "astra"}) {
            if (screenItem(items, alias) != null) modes++;
        }
        return modes >= 2;
    }

    private void recordScreenMenu(List<ScreenModelReader.Item> items) {
        String flags = "T" + (hasMenuTitle(items) ? 1 : 0) +
            "_I" + ((screenItem(items, "instant") != null || screenItem(items, "luna") != null) ? 1 : 0) +
            "_M" + ((screenItem(items, "medium") != null || screenItem(items, "sol") != null) ? 1 : 0) +
            "_H" + ((screenItem(items, "high") != null || screenItem(items, "astra") != null) ? 1 : 0) +
            "_D" + (screenDoneItem(items) != null ? 1 : 0) +
            "_N" + items.size();
        HistoryStore.diagnosticDetail(this, "SCREEN_MENU_DIAG", flags);
    }

    private void inspectScreenMenu() {
        if (!processing) return;
        ScreenModelReader.read(this, main, new ScreenModelReader.Callback() {
            @Override public void failure(String code) {
                HistoryStore.diagnostic(thisService(), code);
                fail("Modelmenu niet leesbaar. Je bericht staat nog klaar.");
            }
            @Override public void success(android.graphics.Bitmap image,
                    List<ScreenModelReader.Item> items) {
                if (!processing) return;
                recordScreenMenu(items);

                ScreenModelReader.Item option = screenTargetItem(items);
                if (option != null) {
                    clickedChoice = screenTargetName();
                    HistoryStore.diagnosticDetail(thisService(),
                        "SCREEN_MODEL_OPTION_OBSERVED", clickedChoice);
                    tapScreen(image.getWidth() / 2, option.bounds.centerY(),
                        () -> main.postDelayed(
                            ChatGptAccessibilityService.this::afterScreenTargetTap, 360L),
                        "Modeloptie kon niet worden aangetikt.");
                    return;
                }

                // The first Work sheet can visibly contain a Configure row while
                // Compose exposes no usable accessibility node for it. OCR is the
                // authoritative observation here: tap only the row actually seen.
                ScreenModelReader.Item configure = screenConfigureItem(items);
                if (configure != null) {
                    HistoryStore.diagnostic(thisService(),
                        "SCREEN_CONFIGURE_CONTROL_OBSERVED");
                    tapScreen(configure.bounds.centerX(), configure.bounds.centerY(),
                        () -> main.postDelayed(
                            ChatGptAccessibilityService.this::inspectScreenMenu, 260L),
                        "Configureren kon niet worden aangetikt.");
                    return;
                }

                // If neither the model rows nor Configure are visible, try the
                // semantic accessibility/overflow route once before failing.
                openConfigureMenu(0);
            }
        });
    }

    /**
     * ChatGPT has used both menu styles: one with a separate Done/Klaar button
     * and one where selecting a row closes the sheet immediately. Support both.
     */
    private void afterScreenTargetTap() {
        if (!processing) return;
        ScreenModelReader.read(this, main, new ScreenModelReader.Callback() {
            @Override public void failure(String code) {
                HistoryStore.diagnostic(thisService(), code);
                verifiedLabel = screenTargetName();
                HistoryStore.diagnostic(thisService(),
                    "SCREEN_MODEL_TAP_ACCEPTED_CAPTURE_UNAVAILABLE");
                main.postDelayed(ChatGptAccessibilityService.this::sendWhenVerified, 120L);
            }
            @Override public void success(android.graphics.Bitmap image,
                    List<ScreenModelReader.Item> items) {
                if (!processing) return;
                ScreenModelReader.Item done = screenDoneItem(items);
                ScreenModelReader.Item selected = screenTargetItem(items);
                if (done != null) {
                    verifiedLabel = screenTargetName();
                    HistoryStore.diagnostic(thisService(), "SCREEN_MODEL_TARGET_TAPPED_DONE_VISIBLE");
                    tapScreen(done.bounds.centerX(), done.bounds.centerY(),
                        () -> main.postDelayed(
                            ChatGptAccessibilityService.this::sendWhenVerified, 300L),
                        "Modelmenu kon niet worden gesloten.");
                    return;
                }
                if (!hasMenuTitle(items)) {
                    verifiedLabel = screenTargetName();
                    HistoryStore.diagnostic(thisService(),
                        "SCREEN_MODEL_TARGET_TAPPED_MENU_AUTO_CLOSED");
                    main.postDelayed(ChatGptAccessibilityService.this::sendWhenVerified, 180L);
                    return;
                }
                // Some builds keep the sheet visible without a Done control.
                // The exact target row was observed and Android completed the tap;
                // close the sheet once, then continue to the explicit user send.
                verifiedLabel = screenTargetName();
                HistoryStore.diagnostic(thisService(),
                    selected != null ? "SCREEN_MODEL_TARGET_TAPPED_CLOSE_WITH_BACK" :
                        "SCREEN_MODEL_ROW_MOVED_CLOSE_WITH_BACK");
                if (performGlobalAction(GLOBAL_ACTION_BACK))
                    main.postDelayed(ChatGptAccessibilityService.this::sendWhenVerified, 300L);
                else sendWhenVerified();
            }
        });
    }

    /** Resolve controls from a fresh, owned Android window, never from OCR alone. */
    private AccessibilityNodeInfo menuEntry(boolean configure) {
        List<AccessibilityWindowInfo> all = getWindows();
        if (all == null) return null;
        for (AccessibilityWindowInfo window : all) {
            if (window.getType() != AccessibilityWindowInfo.TYPE_APPLICATION) continue;
            AccessibilityNodeInfo root = window.getRoot();
            if (root == null || root.getPackageName() == null ||
                    !activePackage.contentEquals(root.getPackageName())) continue;
            for (AccessibilityNodeInfo node : flatten(root)) {
                if (!node.isVisibleToUser() || !node.isEnabled() || node.isEditable() ||
                        !clickable(node)) continue;
                String text = node.getText() == null ? "" : node.getText().toString();
                String desc = node.getContentDescription() == null ? "" :
                    node.getContentDescription().toString();
                boolean match = configure
                    ? MenuEntryPolicy.configure(text) || MenuEntryPolicy.configure(desc)
                    : isTopBarNode(node) && (MenuEntryPolicy.overflow(text) ||
                        MenuEntryPolicy.overflow(desc));
                if (match) return node;
            }
        }
        return null;
    }

    private void openConfigureMenu(int poll) {
        if (!processing) return;
        AccessibilityNodeInfo configure = menuEntry(true);
        if (!configureAttempted && configure != null) {
            configureAttempted = true;
            HistoryStore.diagnostic(this, "MODEL_CONFIGURE_CONTROL_OBSERVED");
            if (clickUp(configure)) {
                main.postDelayed(this::inspectScreenMenu, 350L);
                return;
            }
        }
        if (!overflowAttempted && !configureAttempted) {
            overflowAttempted = true;
            AccessibilityNodeInfo overflow = menuEntry(false);
            if (overflow != null && clickUp(overflow)) {
                HistoryStore.diagnostic(this, "MODEL_OVERFLOW_ACTION_DISPATCHED");
                main.postDelayed(() -> openConfigureMenu(1), 150L);
                return;
            }
            HistoryStore.diagnostic(this, "MODEL_OVERFLOW_CONTROL_UNAVAILABLE");
        }
        // Poll for a resulting control; never repeat the overflow click.
        if (poll > 0 && poll < 6 && !configureAttempted &&
                SystemClock.uptimeMillis() < deadline) {
            main.postDelayed(() -> openConfigureMenu(poll + 1), 150L);
            return;
        }
        HistoryStore.diagnostic(this, "MODEL_CONFIGURE_NOT_OPENED");
        fail("Configureren niet bereikbaar. Je bericht is niet verzonden en staat nog klaar.");
    }

    private static int brightPixels(android.graphics.Bitmap image, int y) {
        int left = Math.round(image.getWidth() * .78f);
        int right = Math.round(image.getWidth() * .93f);
        int radius = Math.min(55, image.getHeight() / 30);
        int bright = 0;
        for (int py = Math.max(0, y - radius); py < Math.min(image.getHeight(), y + radius); py += 2)
            for (int px = left; px < right; px += 2) {
                int color = image.getPixel(px, py);
                if (android.graphics.Color.red(color) > 205 &&
                    android.graphics.Color.green(color) > 205 &&
                    android.graphics.Color.blue(color) > 205) bright++;
            }
        return bright;
    }

    private void verifyScreenSelection() {
        if (!processing) return;
        ScreenModelReader.read(this, main, new ScreenModelReader.Callback() {
            @Override public void failure(String code) {
                HistoryStore.diagnostic(thisService(), code);
                fail("Modelkeuze niet te controleren. Je bericht staat nog klaar.");
            }
            @Override public void success(android.graphics.Bitmap image,
                    List<ScreenModelReader.Item> items) {
                if (!processing) return;
                recordScreenMenu(items);
                if (!hasMenuTitle(items)) {
                    fail("Modelmenu verdwenen. Je bericht staat nog klaar.");
                    return;
                }
                ScreenModelReader.Item selected = screenTargetItem(items);
                ScreenModelReader.Item done = screenDoneItem(items);
                int selectedMark = selected == null ? 0 :
                    brightPixels(image, selected.bounds.centerY());
                int competingMark = 0;
                String[][] groups = new String[][]{
                    {"instant", "luna", "5.6 luna", "6 luna"},
                    {"medium", "thinking medium", "sol", "5.6 sol", "6 sol"},
                    {"high", "thinking high", "astra", "5.6 astra", "6 astra"}
                };
                for (String[] group : groups) {
                    boolean targetGroup = false;
                    for (String alias : screenTargetAliases())
                        for (String candidate : group)
                            if (alias.equals(candidate)) targetGroup = true;
                    if (targetGroup) continue;
                    for (String candidate : group) {
                        ScreenModelReader.Item row = screenItem(items, candidate);
                        if (row != null) competingMark = Math.max(competingMark,
                            brightPixels(image, row.bounds.centerY()));
                    }
                }
                if (selected == null || done == null) {
                    HistoryStore.diagnostic(thisService(), "SCREEN_MODEL_TARGET_OR_DONE_MISSING");
                    fail("Modelkeuze niet bevestigd. Je bericht staat nog klaar.");
                    return;
                }
                // Some ChatGPT themes no longer render a high-contrast check mark that
                // survives screenshot/OCR verification. We already observed the exact
                // requested row and Android confirmed that the tap gesture completed.
                // Prefer a strong visual mark when available, but do not dead-end solely
                // because the decorative selection indicator changed.
                boolean visualMarkConfirmed = selectedMark >= 8 &&
                    selectedMark > competingMark * 2 + 5;
                verifiedLabel = screenTargetName();
                HistoryStore.diagnostic(thisService(), visualMarkConfirmed
                    ? "SCREEN_MODEL_CHECK_CONFIRMED"
                    : "SCREEN_MODEL_ACCEPTED_AFTER_TARGET_TAP");
                tapScreen(done.bounds.centerX(), done.bounds.centerY(),
                    () -> main.postDelayed(ChatGptAccessibilityService.this::sendWhenVerified, 350L),
                    "Modelmenu kon niet worden gesloten.");
            }
        });
    }

    private void tapScreen(int x, int y, Runnable completed, String error) {
        if (!processing || x < 0 || y < 0) return;
        Path path = new Path();
        path.moveTo(x, y);
        boolean started = dispatchGesture(new GestureDescription.Builder()
            .addStroke(new GestureDescription.StrokeDescription(path, 0, 65)).build(),
            new GestureResultCallback() {
                @Override public void onCancelled(GestureDescription gesture) {
                    main.post(() -> fail(error));
                }
                @Override public void onCompleted(GestureDescription gesture) {
                    // Completion proves only that Android injected the tap.
                    main.post(completed);
                }
            }, main);
        if (!started) fail(error);
    }

    private void verifyThenSend() {
        if (!processing) return;
        List<AccessibilityNodeInfo> visible = modelWindowNodes();
        AccessibilityNodeInfo picker = findPicker(visible);
        // An open menu can show the selected row near the top of the screen.
        // Never mistake a menu item for the confirmed active header setting.
        if (picker != null && pickerShowsTarget(picker, target) &&
            visibleModeCount(visible) <= 1) {
            verifiedLabel = safeModelLabel(picker);
            sendWhenVerified(); return;
        }
        // If we positively identified and clicked the requested model row and the
        // multi-choice menu is no longer visible, accept that completed semantic
        // action even when this ChatGPT build does not expose the selected model
        // again in its header. This removes the dead-end seen in build 343.
        boolean explicitChoiceCompleted = !clickedChoice.isEmpty() &&
            ModelMenuPolicy.matches(target, clickedChoice) &&
            visibleModeCount(visible) <= 1;
        if (explicitChoiceCompleted && verificationAttempts >= 3) {
            verifiedLabel = clickedChoice;
            HistoryStore.diagnostic(this, "MODEL_SELECTION_ACCEPTED_AFTER_EXPLICIT_CLICK");
            sendWhenVerified();
            return;
        }
        if (verificationAttempts++ < 15) {
            main.postDelayed(this::verifyThenSend, 90L);
        } else {
            HistoryStore.diagnostic(this, "MODEL_VERIFICATION_FAILED");
            sendWithoutModelChange("MODEL_VERIFICATION_UNAVAILABLE");
        }
    }

    /** Stop at the failed step. A draft remains in ChatGPT for manual recovery. */
    private void sendWithoutModelChange(String reason) {
        if (!processing || sendDispatched) return;
        // The SLIM tap is an explicit user send action. If ChatGPT changes its
        // model-picker UI, do not turn the button into a dead control: keep the
        // diagnostic, then send the unchanged draft with the currently active
        // model. Model selection is still attempted first whenever available.
        HistoryStore.diagnostic(this, reason);
        HistoryStore.diagnostic(this, "MODEL_SELECTION_FALLBACK_SEND_CURRENT_MODEL");
        verifiedLabel = "";
        dispatchCheckedSend(false, 0);
    }

    private void sendWhenVerified() {
        dispatchCheckedSend(true, 0);
    }

    /** Never submit stale, blank or modified text. Retry briefly while the
     * Android keyboard and ChatGPT's native Send node settle. */
    private void dispatchCheckedSend(boolean modelVerified, int retry) {
        if (!processing || sendDispatched) return;
        AccessibilityNodeInfo root = chatRoot();
        List<AccessibilityNodeInfo> nodes = flatten(root);
        // Samsung/Compose can expose composer and send controls in separate
        // ChatGPT-owned windows. Never inspect a different application.
        if (editor(nodes) == null || findSend(nodes) == null) {
            nodes = modelWindowNodes();
        }
        AccessibilityNodeInfo e = editor(nodes);
        if (e == null || e.getText() == null) {
            AccessibilityNodeInfo recent = eventRootFallback();
            if (recent != null) {
                nodes = flatten(recent);
                e = editor(nodes);
            }
        }
        if (e == null || e.getText() == null ||
                !draft.equals(e.getText().toString())) {
            AccessibilityNodeInfo live = recentLiveEditor();
            if (live != null && live.getText() != null &&
                    draft.equals(live.getText().toString())) e = live;
        }
        boolean cachedDraftVerified = false;
        if (e == null || e.getText() == null) {
            cachedDraftVerified = recentCachedDraft(draft);
            if (!cachedDraftVerified) {
                if (retry < RemoteRules.current().sendRetries) {
                    main.postDelayed(() -> dispatchCheckedSend(modelVerified, retry + 1), RemoteRules.current().retryDelayMs);
                    return;
                }
                fail("ChatGPT-tekstvak niet toegankelijk. Je bericht is niet verstuurd.");
                return;
            }
            HistoryStore.diagnostic(this, "SEND_DRAFT_VERIFIED_FROM_RECENT_EVENT_CACHE");
        }
        if (draft.isEmpty() || (!cachedDraftVerified && !draft.equals(e.getText().toString()))) {
            fail("Bericht gewijzigd. Je bericht is niet verstuurd.");
            return;
        }
        AccessibilityNodeInfo send = findSend(nodes);
        if (send == null) {
            send = findSend(modelWindowNodes());
        }
        if (send == null) {
            if (retry < RemoteRules.current().sendRetries) {
                main.postDelayed(() -> dispatchCheckedSend(modelVerified, retry + 1), RemoteRules.current().retryDelayMs);
                return;
            }
            HistoryStore.diagnostic(this, "SEND_CONTROL_NOT_EXPOSED");
            HistoryStore.diagnosticDetail(this, "SEND_TREE", pickerDiagnostic(modelWindowNodes()));
            if (SupportedAiApps.CHATGPT.equals(activePackage) &&
                    ((e != null && e.getText() != null && e.isVisibleToUser() &&
                      e.isEditable() && draft.equals(e.getText().toString())) ||
                     (cachedDraftVerified && recentCachedDraft(draft)))) {
                // The user explicitly tapped SLIM. When Compose invalidated the
                // editor node, the latest text event plus saved composer bounds
                // still prove which visible draft this tap belongs to.
                HistoryStore.diagnostic(this, modelVerified
                    ? "SCREEN_SEND_FALLBACK_AFTER_MODEL_VERIFIED"
                    : "SCREEN_SEND_FALLBACK_CURRENT_MODEL");
                if (e != null && e.getText() != null) inspectScreenSend(e);
                else inspectScreenSend(lastComposerBounds);
                return;
            }
            fail("Verzendknop van ChatGPT niet toegankelijk. Je bericht staat nog klaar.");
            return;
        }
        if (!sendGate.markDispatched()) return;
        sendDispatched = true;
        dispatchedAt = SystemClock.uptimeMillis();
        processing = false;
        menuInspection = false;
        main.removeCallbacksAndMessages(null);
        if (sendOverlay != null) applyOverlayAppearance(sendOverlay);
        main.postDelayed(visibilityPoll, 1200L);
        boolean submitted = clickUp(send);
        if (!submitted) {
            // Gesture fallback is only permitted over a semantically identified
            // SEND node, never guessed screen coordinates. Remove our interceptor
            // before the gesture so it cannot receive its own synthetic tap.
            Rect bounds = new Rect();
            send.getBoundsInScreen(bounds);
            if (!bounds.isEmpty() && send.isVisibleToUser() && send.isEnabled()) {
                removeSendOverlay();
                Path path = new Path();
                path.moveTo(bounds.exactCenterX(), bounds.exactCenterY());
                GestureDescription gesture = new GestureDescription.Builder()
                    .addStroke(new GestureDescription.StrokeDescription(path, 0, 65)).build();
                submitted = dispatchGesture(gesture, new GestureResultCallback() {
                    @Override public void onCancelled(GestureDescription g) {
                        HistoryStore.diagnostic(ChatGptAccessibilityService.this, "SEND_GESTURE_CANCELLED");
                        main.post(() -> fail("Verzendactie afgebroken. Controleer je bericht."));
                    }
                    @Override public void onCompleted(GestureDescription g) {
                        HistoryStore.diagnostic(ChatGptAccessibilityService.this, "SEND_GESTURE_COMPLETED");
                    }
                }, main);
                if (submitted) HistoryStore.diagnostic(this, "SEND_GESTURE_DISPATCHED");
            }
        }
        if (!submitted) {
            HistoryStore.diagnostic(this, "SEND_CLICK_AND_GESTURE_FAILED");
            fail("Verzendactie mislukt. Je bericht staat nog klaar.");
        } else {
            HistoryStore.diagnostic(this, modelVerified ? "SEND_MODEL_VERIFIED" : "SEND_EXISTING_MODEL_FALLBACK");
            if (!modelVerified) Toast.makeText(this,
                "Bericht verzenden met huidig model; modelkeuze niet beschikbaar.",
                Toast.LENGTH_LONG).show();
            HistoryStore.add(this, target, verifiedLabel,
                modelVerified ? "SEND_CLICKED" : "SEND_CLICKED_MODEL_NOT_CHANGED",
                SystemClock.uptimeMillis() - startedAt, seenChoices, clickedChoice);
            startedAt = 0L;
            bypassUntil = SystemClock.uptimeMillis() + RemoteRules.current().cooldownMs;
            main.postDelayed(this::refreshOverlay, 950L);
        }
    }

    private void inspectScreenSend(AccessibilityNodeInfo verifiedEditor) {
        Rect composer = new Rect();
        verifiedEditor.getBoundsInScreen(composer);
        inspectScreenSend(composer);
    }

    private void inspectScreenSend(Rect verifiedComposerBounds) {
        Rect composer = verifiedComposerBounds == null ? new Rect() :
            new Rect(verifiedComposerBounds);
        if (!processing || composer.isEmpty() || draft.isEmpty() ||
                !recentCachedDraft(draft)) {
            fail("Concept niet meer bevestigd. Je bericht staat nog klaar.");
            return;
        }
        // The old implementation took a screenshot while our own Send interceptor
        // was still covering ChatGPT's arrow, then rejected the obscured arrow.
        // At this point the exact draft has already been re-read and matched.
        // Remove our overlays first and tap once inside the composer's right-hand
        // action area. This works for both regular ChatGPT and Work.
        float density = getResources().getDisplayMetrics().density;
        int inset = Math.round(24 * density);
        int x = Math.max(composer.left + inset, composer.right - inset);
        int y = composer.centerY();
        android.util.DisplayMetrics display = getResources().getDisplayMetrics();
        if (composer.width() < Math.round(120 * density) ||
                composer.height() > Math.round(190 * density) ||
                y < display.heightPixels / 2 ||
                x <= composer.left || x >= display.widthPixels) {
            HistoryStore.diagnostic(this, "SCREEN_SEND_COMPOSER_GEOMETRY_REJECTED");
            fail("Verzendpositie niet betrouwbaar. Je bericht staat nog klaar.");
            return;
        }
        HistoryStore.diagnostic(this, "SCREEN_SEND_COMPOSER_POSITION_CONFIRMED");
        if (!sendGate.markDispatched()) return;
        removeSendOverlay();
        removeFloatingButton();
        sendDispatched = true;
        dispatchedAt = SystemClock.uptimeMillis();
        processing = false;
        menuInspection = false;
        main.postDelayed(() -> tapVerifiedSendOnce(x, y), 90L);
    }

    private void tapVerifiedSendOnce(int x, int y) {
        Path path = new Path();
        path.moveTo(x, y);
        boolean started = dispatchGesture(new GestureDescription.Builder()
            .addStroke(new GestureDescription.StrokeDescription(path, 0, 65)).build(),
            new GestureResultCallback() {
                @Override public void onCancelled(GestureDescription gesture) {
                    main.post(() -> fail("Verzendtik afgebroken. Controleer je concept."));
                }
                @Override public void onCompleted(GestureDescription gesture) {
                    HistoryStore.diagnostic(thisService(), "SCREEN_SEND_TAP_COMPLETED_UNCONFIRMED");
                    main.postDelayed(() -> {
                        if (!sendDispatched) return;
                        AccessibilityNodeInfo editorNow = recentLiveEditor();
                        if (editorNow != null && editorNow.getText() != null &&
                            editorNow.getText().length() == 0) {
                            HistoryStore.diagnostic(thisService(), "SCREEN_SEND_COMPOSER_CLEARED");
                            releaseCompletedAttempt();
                        } else {
                            HistoryStore.diagnostic(thisService(), "SCREEN_SEND_STILL_UNCONFIRMED");
                            Toast.makeText(thisService(),
                                "Verzending niet bevestigd. Controleer de chat voordat je opnieuw tikt.",
                                Toast.LENGTH_LONG).show();
                        }
                    }, 1000L);
                }
            }, main);
        if (!started) fail("Verzendtik niet uitgevoerd. Je bericht staat nog klaar.");
        else HistoryStore.diagnostic(this, "SCREEN_SEND_TAP_DISPATCHED_UNCONFIRMED");
    }

    private void releaseCompletedAttempt() {
        if (!sendDispatched) return;
        sendDispatched = false;
        dispatchedAt = 0L;
        sendGate.reset();
        draft = "";
        bypassUntil = 0L;
        main.removeCallbacks(refreshTask);
        main.post(refreshTask);
    }

    private void fail(String text) {
        if (startedAt > 0L) {
            HistoryStore.add(this, target, verifiedLabel, text,
                SystemClock.uptimeMillis() - startedAt, seenChoices, clickedChoice);
            startedAt = 0L;
        }
        processing = false;
        menuInspection = false;
        sendDispatched = false;
        dispatchedAt = 0L;
        sendGate.reset();
        main.removeCallbacksAndMessages(null);
        main.postDelayed(visibilityPoll, 1200L);
        // Keep the accessible button in place on failure. The 250 ms cooldown
        // prevents rapid retries without making it vanish from the composer.
        bypassUntil = SystemClock.uptimeMillis() + 250L;
        if (chatForeground && sendOverlay != null) applyOverlayAppearance(sendOverlay);
        Toast.makeText(this, text, Toast.LENGTH_LONG).show();
        main.postDelayed(this::refreshOverlay, 300L);
    }

    private AccessibilityNodeInfo editor(List<AccessibilityNodeInfo> nodes) {
        AccessibilityNodeInfo focusedEmpty = null;
        AccessibilityNodeInfo populated = null;
        AccessibilityNodeInfo fallback = null;
        for (AccessibilityNodeInfo n : nodes) {
            if (!n.isEditable() || !n.isVisibleToUser() ||
                    n.getPackageName() == null ||
                    !activePackage.contentEquals(n.getPackageName())) continue;
            CharSequence value = n.getText();
            if (value != null && !value.toString().trim().isEmpty()) {
                if (n.isFocused()) return n;
                if (populated == null) populated = n;
            } else if (n.isFocused()) focusedEmpty = n;
            if (fallback == null) fallback = n;
        }
        return populated != null ? populated :
            focusedEmpty != null ? focusedEmpty : fallback;
    }
    private AccessibilityNodeInfo findSend(List<AccessibilityNodeInfo> nodes) {
        AccessibilityNodeInfo fallback = null;
        RemoteRules.Rules.AppRules provider = RemoteRules.current().forPackage(activePackage);
        // The official ChatGPT UI may expose a resource ID but no translated label.
        // Never guess a Send button from screen position: that could send the wrong text.
        for (AccessibilityNodeInfo n : nodes) {
            if (!n.isEnabled() || !n.isVisibleToUser() || n.isEditable()) continue;
            String s = label(n);
            String id = n.getViewIdResourceName() == null ? "" :
                n.getViewIdResourceName().toLowerCase(Locale.ROOT);
            boolean knownLabel =
                s.equals("send") || s.equals("verzenden") ||
                s.equals("verstuur") || s.equals("sturen") ||
                s.contains("send message") || s.contains("send button") ||
                s.contains("send prompt") || s.equals("submit") ||
                s.contains("submit message") || s.contains("submit prompt") ||
                s.contains("verstuur bericht") || s.contains("bericht verzenden") ||
                s.contains("bericht sturen") || s.contains("vraag versturen");
            boolean knownId = id.endsWith("/send") || id.endsWith("/send_button") ||
                id.endsWith("/send_message") || id.endsWith("/send_message_button") ||
                id.endsWith("/submit") || id.endsWith("/submit_button");
            if ((knownLabel || knownId ||
                RemoteRules.matchesUiLabel(s, RemoteRules.current().sendLabels) ||
                RemoteRules.matchesResourceId(id, RemoteRules.current().sendIds) ||
                RemoteRules.matchesUiLabel(s, provider.sendLabels) ||
                RemoteRules.matchesResourceId(id, provider.sendIds)) && clickable(n)) return n;
            if ((knownLabel || knownId ||
                RemoteRules.matchesUiLabel(s, RemoteRules.current().sendLabels) ||
                RemoteRules.matchesResourceId(id, RemoteRules.current().sendIds) ||
                RemoteRules.matchesUiLabel(s, provider.sendLabels) ||
                RemoteRules.matchesResourceId(id, provider.sendIds)) && fallback == null) fallback = n;
        }
        return fallback;
    }
    private AccessibilityNodeInfo findPicker(List<AccessibilityNodeInfo> nodes) {
        RemoteRules.Rules.AppRules provider = RemoteRules.current().forPackage(activePackage);
        for (AccessibilityNodeInfo n : nodes) {
            if (!n.isVisibleToUser() || n.isEditable()) continue;
            String s = label(n);
            String id = n.getViewIdResourceName() == null ? "" :
                n.getViewIdResourceName().toLowerCase(Locale.ROOT);
            boolean selectorId = id.contains("model") &&
                (id.contains("picker") || id.contains("select") || id.contains("switch"));
            boolean headerLabel = s.contains("instant") || s.contains("thinking") ||
                s.equals("medium") || s.equals("standard") || s.equals("high") ||
                s.equals("hoog") || s.equals("extended") || s.equals("pro") ||
                s.contains("6 astra") || s.contains("6 sol") || s.contains("6 luna") ||
                s.contains("gpt-5") || s.contains("gpt 5") ||
                (s.contains("model") && (s.contains("select") || s.contains("kie") ||
                    s.contains("change")));
            boolean chatgpt = SupportedAiApps.CHATGPT.equals(activePackage);
            boolean matched = chatgpt
                ? (selectorId || (isTopBarNode(n) && headerLabel) ||
                    RemoteRules.matchesResourceId(id, RemoteRules.current().pickerIds) ||
                    RemoteRules.matchesResourceId(id, provider.pickerIds) ||
                    (isTopBarNode(n) &&
                        (RemoteRules.matchesUiLabel(s, RemoteRules.current().pickerLabels) ||
                         RemoteRules.matchesUiLabel(s, provider.pickerLabels))))
                : provider.modelPickerEnabled &&
                    (RemoteRules.matchesResourceId(id, provider.pickerIds) ||
                     RemoteRules.matchesUiLabel(s, provider.pickerLabels));
            if (matched && clickable(n)) return n;
        }
        return null;
    }
    /** The Work conversation header can open Configureren while exposing no model name. */
    private AccessibilityNodeInfo findWorkHeader(List<AccessibilityNodeInfo> nodes) {
        for (AccessibilityNodeInfo n : nodes) {
            if (n.isVisibleToUser() && !n.isEditable() && isTopBarNode(n) &&
                    label(n).equals("work")) return n;
        }
        return null;
    }
    /** A Compose header may have a generic product label instead of a model label. */
    private AccessibilityNodeInfo findModelHeaderFallback(List<AccessibilityNodeInfo> nodes) {
        AccessibilityNodeInfo generic = null;
        for (AccessibilityNodeInfo n : nodes) {
            if (!n.isVisibleToUser() || n.isEditable() || !isTopBarNode(n) || !clickable(n) ||
                n.getPackageName() == null || !activePackage.contentEquals(n.getPackageName()))
                continue;
            String value = label(n);
            String id = n.getViewIdResourceName() == null ? "" :
                n.getViewIdResourceName().toLowerCase(Locale.ROOT);
            if (value.equals("work") || value.equals("model") ||
                value.contains("model kiezen") || value.contains("choose model") ||
                value.contains("change model") || value.contains("switch model") ||
                id.contains("model") || id.contains("header_model")) return n;
            if (generic == null && (value.equals("chatgpt") || value.equals("chatgpt 5") ||
                value.equals("gpt-5") || value.equals("gpt 5"))) generic = n;
        }
        return generic;
    }
    private boolean tapVisibleNode(AccessibilityNodeInfo n) {
        Rect b = new Rect();
        n.getBoundsInScreen(b);
        if (b.isEmpty() || !n.isVisibleToUser() || !isTopBarNode(n)) return false;
        Path p = new Path();
        p.moveTo(b.exactCenterX(), b.exactCenterY());
        return dispatchGesture(new GestureDescription.Builder()
            .addStroke(new GestureDescription.StrokeDescription(p, 0, 65)).build(),
            null, main);
    }
    private boolean isTopBarNode(AccessibilityNodeInfo node) {
        Rect rect = new Rect();
        node.getBoundsInScreen(rect);
        return !rect.isEmpty() &&
            rect.centerY() < getResources().getDisplayMetrics().heightPixels / 3;
    }

    private int visibleModeCount(List<AccessibilityNodeInfo> nodes) {
        boolean instant = false, medium = false, high = false;
        RemoteRules.Rules.AppRules provider = RemoteRules.current().forPackage(activePackage);
        boolean chatgpt = SupportedAiApps.CHATGPT.equals(activePackage);
        for (AccessibilityNodeInfo n : nodes) {
            if (!n.isVisibleToUser() || !clickable(n) || n.isEditable()) continue;
            String text = label(n);
            instant |= chatgpt ? ModelMenuPolicy.matches(ModelRule.Level.INSTANT, text)
                : RemoteRules.matchesUiLabel(text, provider.instantModels);
            medium |= chatgpt ? ModelMenuPolicy.matches(ModelRule.Level.THINKING, text)
                : RemoteRules.matchesUiLabel(text, provider.thinkingModels);
            high |= chatgpt ? ModelMenuPolicy.matches(ModelRule.Level.HIGH, text)
                : RemoteRules.matchesUiLabel(text, provider.highModels);
        }
        return (instant ? 1 : 0) + (medium ? 1 : 0) + (high ? 1 : 0);
    }
    private boolean pickerShowsTarget(AccessibilityNodeInfo picker, ModelRule.Level level) {
        if (SupportedAiApps.CHATGPT.equals(activePackage))
            return isTopBarNode(picker) && ModelMenuPolicy.matches(level, label(picker));
        RemoteRules.Rules.AppRules provider = RemoteRules.current().forPackage(activePackage);
        return provider.modelPickerEnabled &&
            RemoteRules.matchesUiLabel(label(picker), provider.models(level));
    }
    private String findAvailableChoices(List<AccessibilityNodeInfo> nodes) {
        StringBuilder choices = new StringBuilder();
        for (AccessibilityNodeInfo n : nodes) {
            if (!n.isVisibleToUser() || !clickable(n)) continue;
            String s = safeModelLabel(n);
            if (s.isEmpty()) continue;
            if (s.contains("instant") || s.contains("thinking") || s.contains("medium") ||
                s.contains("high") || s.contains("hoog") || s.contains("extended") ||
                s.contains("6 astra") || s.contains("6 sol") || s.contains("6 luna") ||
                s.contains("gpt-")) {
                if (choices.length() > 0) choices.append("; ");
                if (choices.length() + s.length() >= 280) break;
                choices.append(s);
            }
        }
        return choices.toString();
    }
    private AccessibilityNodeInfo findChoice(List<AccessibilityNodeInfo> nodes, ModelRule.Level level) {
        if (!SupportedAiApps.CHATGPT.equals(activePackage)) {
            RemoteRules.Rules.AppRules provider = RemoteRules.current().forPackage(activePackage);
            if (!provider.modelPickerEnabled) return null;
            for (AccessibilityNodeInfo n : nodes) {
                if (n.isVisibleToUser() && clickable(n) && !n.isEditable() &&
                    RemoteRules.matchesUiLabel(label(n), provider.models(level))) return n;
            }
            return null;
        }
        // Learn the UI label of the latest verified selection for this reasoning level.
        String preference = HistoryStore.preferred(this, level);
        if (!preference.isEmpty()) for (AccessibilityNodeInfo n : nodes) {
            if (n.isVisibleToUser() && clickable(n) && preference.equals(safeModelLabel(n))) return n;
        }
        // Only select a mode explicitly exposed in the current menu.
        // "Thinking" by itself does not verify Medium or High.
        for (AccessibilityNodeInfo n : nodes) {
            if (n.isVisibleToUser() && clickable(n) && !n.isEditable() &&
                ModelMenuPolicy.matches(level, label(n))) return n;
        }
        return null;
    }
    /** Only model identifiers / preset names enter the log. Never persist UI or prompt text. */
    private static String safeModelLabel(AccessibilityNodeInfo node) {
        String s = label(node);
        java.util.regex.Matcher m = java.util.regex.Pattern.compile(
            "\\b(?:gpt[- ]?[0-9]+(?:\\.[0-9]+)?(?:[- ][a-z]+)?|6\\s+(?:astra|sol|luna)|instant|thinking|medium|standard|hoog|high|extended|pro)\\b",
            java.util.regex.Pattern.CASE_INSENSITIVE).matcher(s);
        StringBuilder safe = new StringBuilder();
        while (m.find() && safe.length() < 65) {
            if (safe.length() > 0) safe.append(" ");
            safe.append(m.group().toLowerCase(Locale.ROOT));
        }
        return safe.toString();
    }
    private static String label(AccessibilityNodeInfo node) {
        String text = node.getText() == null ? "" : node.getText().toString();
        String desc = node.getContentDescription() == null ? "" : node.getContentDescription().toString();
        return (text + " " + desc).trim().toLowerCase(Locale.ROOT);
    }
    private static boolean clickable(AccessibilityNodeInfo n) {
        for (int i=0; n != null && i<4; i++, n=n.getParent()) if (n.isClickable()) return true;
        return false;
    }
    private static boolean clickUp(AccessibilityNodeInfo n) {
        for (int i=0; n != null && i<4; i++, n=n.getParent())
            if (n.isClickable() && n.isEnabled() &&
                n.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true;
        return false;
    }
    private static List<AccessibilityNodeInfo> flatten(AccessibilityNodeInfo root) {
        List<AccessibilityNodeInfo> out = new ArrayList<>();
        if (root != null) walk(root, out, 0);
        return out;
    }
    private static void walk(AccessibilityNodeInfo n, List<AccessibilityNodeInfo> out, int d) {
        if (n == null || d > 30 || out.size() > 280) return;
        out.add(n);
        for (int i=0; i<n.getChildCount(); i++) walk(n.getChild(i), out, d+1);
    }
    private void removeSendOverlay() {
        if (sendOverlay != null && windows != null) {
            try { windows.removeViewImmediate(sendOverlay); } catch (RuntimeException ignored) { }
            sendOverlay = null;
        }
    }
    private void removeOverlay() {
        removeFloatingButton();
        removeSendOverlay();
    }
    @Override public void onInterrupt() { processing=false; sendDispatched=false; sendGate.reset(); chatForeground=false; activePackage=""; latestChatEventRoot=null; main.removeCallbacksAndMessages(null); removeOverlay(); }
    @Override public void onDestroy() { onInterrupt(); super.onDestroy(); }
}
