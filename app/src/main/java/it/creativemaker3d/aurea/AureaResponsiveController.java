package it.creativemaker3d.aurea;

import android.app.Activity;
import android.app.Application;
import android.graphics.Color;
import android.os.Bundle;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.webkit.WebView;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Adatta le schermate native AUREA al tablet di riferimento 13,4 pollici,
 * 1920x1200, Android 16, mantenendo compatibilità con altre risoluzioni.
 *
 * La classe modifica esclusivamente la disposizione visiva: non legge né
 * altera profili, token, memoria, voce, volto o dati Home Assistant.
 */
final class AureaResponsiveController
        implements Application.ActivityLifecycleCallbacks {

    private static final AtomicBoolean INSTALLED = new AtomicBoolean(false);
    private static final String TAG_SCREEN_SCROLL = "aurea_responsive_screen_scroll";
    private static final String TAG_FACE_SCROLL = "aurea_responsive_face_scroll";

    static void install(Application application) {
        if (application == null || !INSTALLED.compareAndSet(false, true)) {
            return;
        }
        application.registerActivityLifecycleCallbacks(
            new AureaResponsiveController()
        );
    }

    @Override
    public void onActivityCreated(Activity activity, Bundle state) {
        configureKeyboard(activity);
        scheduleApply(activity);
    }

    @Override
    public void onActivityResumed(Activity activity) {
        configureKeyboard(activity);
        scheduleApply(activity);
    }

    private void scheduleApply(Activity activity) {
        if (activity == null) {
            return;
        }
        View decor = activity.getWindow().getDecorView();
        decor.post(() -> apply(activity));
        decor.postDelayed(() -> apply(activity), 220L);
    }

    private void configureKeyboard(Activity activity) {
        Window window = activity.getWindow();
        window.setSoftInputMode(
            WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
                | WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN
        );
    }

    private void apply(Activity activity) {
        if (activity == null || activity.isFinishing() || activity.isDestroyed()) {
            return;
        }

        if (activity instanceof FaceGateActivity
                || activity instanceof VoiceGateActivity) {
            configureIdentityViewport(activity);
        }

        ViewGroup content = activity.findViewById(android.R.id.content);
        if (content == null || content.getChildCount() == 0) {
            return;
        }

        if (activity instanceof VoiceGateActivity) {
            wrapSingleLinearScreen(activity, content);
        } else if (activity instanceof MainActivity) {
            wrapSetupScreenIfPresent(activity, content);
        }

        if (activity instanceof FaceGateActivity) {
            installFaceControlsScroll(activity, content);
        }

        applyControlSizing(activity, content);
    }

    /**
     * I pannelli identità occupano la metà sinistra in larghezza e tutta
     * l'altezza utile del display 16:10. In precedenza erano forzati quadrati,
     * lasciando inutilizzati circa 240 pixel verticali sul tablet 1920x1200.
     */
    private void configureIdentityViewport(Activity activity) {
        DisplayMetrics metrics = activity.getResources().getDisplayMetrics();
        int screenWidth = Math.max(metrics.widthPixels, metrics.heightPixels);
        int screenHeight = Math.min(metrics.widthPixels, metrics.heightPixels);
        int margin = dp(activity, 8);
        int panelWidth = Math.max(1, screenWidth / 2);
        int panelHeight = Math.max(1, screenHeight - margin);

        Window window = activity.getWindow();
        WindowManager.LayoutParams params = window.getAttributes();
        params.gravity = Gravity.START | Gravity.TOP;
        params.width = panelWidth;
        params.height = panelHeight;
        params.x = 0;
        params.y = 0;
        params.dimAmount = 0f;
        window.setAttributes(params);
        window.setLayout(panelWidth, panelHeight);
    }

    private void wrapSetupScreenIfPresent(
            Activity activity,
            ViewGroup content) {
        if (content.getChildCount() != 1) {
            return;
        }
        View child = content.getChildAt(0);
        if (child instanceof WebView || child instanceof ScrollView) {
            return;
        }
        if (child instanceof LinearLayout) {
            wrapInScrollView(activity, content, child);
        }
    }

    private void wrapSingleLinearScreen(
            Activity activity,
            ViewGroup content) {
        if (content.getChildCount() != 1) {
            return;
        }
        View child = content.getChildAt(0);
        if (child instanceof ScrollView || !(child instanceof LinearLayout)) {
            return;
        }
        wrapInScrollView(activity, content, child);
    }

    private void wrapInScrollView(
            Activity activity,
            ViewGroup content,
            View child) {
        if (content.findViewWithTag(TAG_SCREEN_SCROLL) != null) {
            return;
        }

        content.removeView(child);
        ScrollView scroll = new ScrollView(activity);
        scroll.setTag(TAG_SCREEN_SCROLL);
        scroll.setFillViewport(true);
        scroll.setClipToPadding(false);
        scroll.setVerticalScrollBarEnabled(true);
        scroll.setOverScrollMode(View.OVER_SCROLL_IF_CONTENT_SCROLLS);
        scroll.setBackgroundColor(Color.TRANSPARENT);

        scroll.addView(
            child,
            new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT,
                ScrollView.LayoutParams.WRAP_CONTENT
            )
        );
        content.addView(
            scroll,
            new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        );
    }

    /**
     * La fotocamera resta a pieno riquadro. Soltanto il pannello inferiore dei
     * comandi diventa scorrevole e cambia altezza quando compare la tastiera.
     */
    private void installFaceControlsScroll(
            Activity activity,
            ViewGroup content) {
        FrameLayout root = findFaceRoot(content);
        if (root == null || root.findViewWithTag(TAG_FACE_SCROLL) != null) {
            return;
        }

        LinearLayout controls = findFaceControls(root);
        if (controls == null) {
            return;
        }

        ViewGroup.LayoutParams original = controls.getLayoutParams();
        FrameLayout.LayoutParams originalFrame = original instanceof FrameLayout.LayoutParams
            ? (FrameLayout.LayoutParams) original
            : null;

        root.removeView(controls);

        ScrollView scroll = new ScrollView(activity);
        scroll.setTag(TAG_FACE_SCROLL);
        scroll.setFillViewport(false);
        scroll.setClipToPadding(false);
        scroll.setVerticalScrollBarEnabled(true);
        scroll.setOverScrollMode(View.OVER_SCROLL_IF_CONTENT_SCROLLS);

        scroll.addView(
            controls,
            new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT,
                ScrollView.LayoutParams.WRAP_CONTENT
            )
        );

        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            responsiveFacePanelHeight(activity, root.getHeight()),
            Gravity.BOTTOM
        );
        if (originalFrame != null) {
            params.leftMargin = originalFrame.leftMargin;
            params.topMargin = originalFrame.topMargin;
            params.rightMargin = originalFrame.rightMargin;
            params.bottomMargin = originalFrame.bottomMargin;
        }
        root.addView(scroll, params);

        root.addOnLayoutChangeListener((view, left, top, right, bottom,
                oldLeft, oldTop, oldRight, oldBottom) -> {
            ViewGroup.LayoutParams current = scroll.getLayoutParams();
            if (!(current instanceof FrameLayout.LayoutParams)) {
                return;
            }
            int desired = responsiveFacePanelHeight(activity, bottom - top);
            FrameLayout.LayoutParams frame = (FrameLayout.LayoutParams) current;
            if (frame.height != desired) {
                frame.height = desired;
                frame.gravity = Gravity.BOTTOM;
                scroll.setLayoutParams(frame);
            }
        });
    }

    private FrameLayout findFaceRoot(ViewGroup content) {
        if (content instanceof FrameLayout
                && hasPreviewAndControls((FrameLayout) content)) {
            return (FrameLayout) content;
        }
        for (int index = 0; index < content.getChildCount(); index++) {
            View child = content.getChildAt(index);
            if (child instanceof FrameLayout
                    && hasPreviewAndControls((FrameLayout) child)) {
                return (FrameLayout) child;
            }
        }
        return null;
    }

    private boolean hasPreviewAndControls(FrameLayout root) {
        return findFaceControls(root) != null;
    }

    private LinearLayout findFaceControls(FrameLayout root) {
        for (int index = 0; index < root.getChildCount(); index++) {
            View child = root.getChildAt(index);
            if (!(child instanceof LinearLayout)) {
                continue;
            }
            if (containsType(child, EditText.class)
                    && countType(child, Button.class) >= 3) {
                return (LinearLayout) child;
            }
        }
        return null;
    }

    private boolean containsType(View view, Class<?> type) {
        return countType(view, type) > 0;
    }

    private int countType(View view, Class<?> type) {
        int count = type.isInstance(view) ? 1 : 0;
        if (!(view instanceof ViewGroup)) {
            return count;
        }
        ViewGroup group = (ViewGroup) view;
        for (int index = 0; index < group.getChildCount(); index++) {
            count += countType(group.getChildAt(index), type);
        }
        return count;
    }

    private int responsiveFacePanelHeight(Activity activity, int rootHeight) {
        int available = rootHeight;
        if (available <= 0) {
            available = activity.getWindow().getDecorView().getHeight();
        }
        if (available <= 0) {
            DisplayMetrics metrics = activity.getResources().getDisplayMetrics();
            available = Math.min(metrics.widthPixels, metrics.heightPixels);
        }

        int minimum = dp(activity, 220);
        int maximum = dp(activity, 520);
        int desired = Math.round(available * 0.54f);
        return Math.min(maximum, Math.max(minimum, desired));
    }

    private void applyControlSizing(Activity activity, View view) {
        if (view instanceof Button) {
            Button button = (Button) view;
            int minimumHeight = dp(activity, 48);
            button.setMinHeight(minimumHeight);
            button.setMinimumHeight(minimumHeight);
            button.setMaxLines(2);
            button.setGravity(Gravity.CENTER);
        } else if (view instanceof EditText) {
            EditText input = (EditText) view;
            int minimumHeight = dp(activity, 52);
            input.setMinHeight(minimumHeight);
            input.setMinimumHeight(minimumHeight);
        }

        if (!(view instanceof ViewGroup)) {
            return;
        }
        ViewGroup group = (ViewGroup) view;
        for (int index = 0; index < group.getChildCount(); index++) {
            applyControlSizing(activity, group.getChildAt(index));
        }
    }

    private int dp(Activity activity, int value) {
        return Math.round(
            value * activity.getResources().getDisplayMetrics().density
        );
    }

    @Override
    public void onActivityStarted(Activity activity) {
    }

    @Override
    public void onActivityPaused(Activity activity) {
    }

    @Override
    public void onActivityStopped(Activity activity) {
    }

    @Override
    public void onActivitySaveInstanceState(Activity activity, Bundle state) {
    }

    @Override
    public void onActivityDestroyed(Activity activity) {
    }
}
