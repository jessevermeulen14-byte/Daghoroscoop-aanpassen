package nl.slimmemodelkiezer.android;

import android.accessibilityservice.AccessibilityService;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.hardware.HardwareBuffer;
import android.os.Build;
import android.os.Handler;
import android.view.Display;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Lightweight local screen fallback.
 *
 * It does not perform OCR and does not persist screenshots. Its only job is to
 * provide the known ChatGPT top-bar model-header area when the Compose
 * accessibility root is temporarily unavailable. Model rows themselves are
 * still selected only from real Accessibility nodes.
 */
final class ScreenModelReader {
    static final class Item {
        final String text;
        final Rect bounds;
        Item(String text, Rect bounds) { this.text = text; this.bounds = bounds; }
    }
    interface Callback {
        void success(Bitmap bitmap, List<Item> items);
        void failure(String code);
    }
    private ScreenModelReader() {}

    static void read(AccessibilityService service, Handler handler, Callback callback) {
        if (Build.VERSION.SDK_INT < 30) {
            callback.failure("SCREEN_CAPTURE_ANDROID_TOO_OLD");
            return;
        }
        try {
            AtomicBoolean completed = new AtomicBoolean(false);
            Runnable timeout = () -> {
                if (completed.compareAndSet(false, true))
                    callback.failure("SCREEN_CAPTURE_TIMEOUT");
            };
            handler.postDelayed(timeout, 1200L);
            service.takeScreenshot(Display.DEFAULT_DISPLAY, handler::post,
                new AccessibilityService.TakeScreenshotCallback() {
                    @Override public void onFailure(int errorCode) {
                        if (!completed.compareAndSet(false, true)) return;
                        handler.removeCallbacks(timeout);
                        callback.failure("SCREEN_CAPTURE_FAILED_" + errorCode);
                    }
                    @Override public void onSuccess(AccessibilityService.ScreenshotResult result) {
                        if (!completed.compareAndSet(false, true)) {
                            try { result.getHardwareBuffer().close(); } catch (RuntimeException ignored) { }
                            return;
                        }
                        handler.removeCallbacks(timeout);
                        HardwareBuffer buffer = result.getHardwareBuffer();
                        Bitmap bitmap = null;
                        try {
                            Bitmap hardware = Bitmap.wrapHardwareBuffer(
                                buffer, result.getColorSpace());
                            if (hardware == null) {
                                callback.failure("SCREEN_BITMAP_UNAVAILABLE");
                                return;
                            }
                            bitmap = hardware.copy(Bitmap.Config.ARGB_8888, false);
                            if (bitmap == null) {
                                callback.failure("SCREEN_BITMAP_UNAVAILABLE");
                                return;
                            }

                            float density = service.getResources()
                                .getDisplayMetrics().density;
                            int centerY = Math.max(
                                Math.round(58f * density),
                                Math.round(bitmap.getHeight() * 0.055f));
                            int halfHeight = Math.max(
                                Math.round(22f * density),
                                Math.round(bitmap.getHeight() * 0.012f));
                            Rect header = new Rect(
                                Math.round(bitmap.getWidth() * 0.18f),
                                Math.max(0, centerY - halfHeight),
                                Math.round(bitmap.getWidth() * 0.82f),
                                Math.min(bitmap.getHeight(), centerY + halfHeight));

                            List<Item> items = new ArrayList<>();
                            items.add(new Item("model", header));
                            callback.success(bitmap, items);
                        } catch (RuntimeException error) {
                            callback.failure("SCREEN_BITMAP_FAILED");
                        } finally {
                            buffer.close();
                            if (bitmap != null && !bitmap.isRecycled()) bitmap.recycle();
                        }
                    }
                });
        } catch (RuntimeException error) {
            callback.failure("SCREEN_CAPTURE_NOT_ALLOWED");
        }
    }
}
