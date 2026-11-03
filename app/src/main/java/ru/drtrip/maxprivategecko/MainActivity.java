package ru.drtrip.maxprivategecko;

import android.Manifest;
import android.app.Activity;
import android.app.KeyguardManager;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.database.Cursor;
import android.provider.OpenableColumns;
import android.webkit.MimeTypeMap;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.app.AlertDialog;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.ImageButton;
import android.content.ClipboardManager;
import android.content.res.ColorStateList;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.FrameLayout;
import android.widget.Button;
import java.util.ArrayList;

import org.mozilla.geckoview.ContentBlocking;
import org.mozilla.geckoview.GeckoResult;
import org.mozilla.geckoview.GeckoRuntime;
import org.mozilla.geckoview.GeckoRuntimeSettings;
import org.mozilla.geckoview.GeckoSession;
import org.mozilla.geckoview.GeckoSessionSettings;
import org.mozilla.geckoview.GeckoView;
import org.mozilla.geckoview.StorageController;
import org.mozilla.geckoview.WebNotification;
import org.mozilla.geckoview.WebNotificationDelegate;
import org.mozilla.geckoview.WebResponse;
import org.mozilla.geckoview.WebRequest;
import org.mozilla.geckoview.WebExtension;
import org.json.JSONObject;
import org.mozilla.geckoview.GeckoWebExecutor;
import android.os.SystemClock;

/** Minimal browser shell; notification diagnostics do not read message contents. */
public final class MainActivity extends Activity {
    private static final String HOME = "https://web.max.ru/";
    private static final String PRIVACY_PROBE = "resource://android/assets/privacy-check.html";
    private static volatile boolean filterRegistered;
    private static volatile boolean filterProbeVerified;
    private static volatile int filterBlockedCount;
    private static volatile long lastFilterProof;
    private static volatile boolean notificationSuspended;
    private static volatile boolean[] webProbeResults;
    private boolean webProofStarted;
    private boolean verificationMediaComplete;
    private String verificationMediaResult = "not-run";
    private String verificationMediaDetail = "";
    private boolean verificationWipeAttempted;
    private boolean verificationWipeFinished;
    private boolean probeActive;
    private boolean privacyPassed;
    private boolean[] probeResults;
    private int probeAttempts;
    private final Runnable probeCompletion = this::completePrivacyProbe;
    private static final String CONTEXT = "max-private-gecko";
    private static final String MAX_NOTIF_CHANNEL = "max_private_messages_silent";
    private static final int MAX_NOTIF_ID = 2023;
    private static final int TEST_NOTIF_ID = 2024;
    private static final String DIAGNOSTICS = "MaxNotification";
    private static final int NOTIFICATION_PERMISSION_REQUEST = 23;
    private static final int FILE_PICKER_REQUEST = 2606;
    private static final int DOWNLOAD_SAVE_REQUEST = 2607;
    private static final int UNLOCK_REQUEST = 2608;
    private static long backgroundAt;
    private boolean unlocked;
    private boolean unlockAttempted;
    private boolean credentialInFlight;
    private View lockCover;
    private View appContent;
    private static final long DOWNLOAD_LIMIT_BYTES = 100L * 1024L * 1024L;
    private static final String DOWNLOAD_CACHE_DIR = "max_private_downloads";
    // Upper bounds also protect device storage if a document provider streams indefinitely.
    private static final long MAX_FILE_COPY_BYTES = 100L * 1024 * 1024;
    private static final long MAX_TOTAL_COPY_BYTES = 150L * 1024 * 1024;
    private static final int MAX_SELECTED_FILES = 10;
    private static final String FILE_CACHE_DIR = "max_selected_files";
    private TransferScope transfers = new TransferScope();
    private GeckoResult<WebResponse> activeDownloadRequest;
    private static final Handler MAIN_HANDLER = new Handler(Looper.getMainLooper());
    private static GeckoRuntime runtime;  // Gecko allows one runtime per app process.

    private GeckoView view;
    private GeckoSession session;
    private AlertDialog pendingUploadDialog;
    private TextView status;
    private boolean canGoBack = false;
    private volatile boolean clearing = false;
    // True only after the built-in AppTracer Guard extension is installed.
    private boolean guardReady = false;
    private boolean resumed = false;
    private boolean started = false;
    // Only one user-initiated picker at a time; no file lists or URI logs.
    private GeckoSession.PromptDelegate.FilePrompt pendingFilePrompt;
    private GeckoResult<GeckoSession.PromptDelegate.PromptResponse> pendingFileResult;
    private long approvedDownloadAt;
    private boolean downloadInProgress;
    private boolean downloadFetchPending;
    private File pendingSaveFile;
    // A short-lived, headless GeckoSession for a user-requested MAX file download.
    // This is never used to browse arbitrary sites and is not a background service.
    private GeckoSession downloadPopupSession;
    // This exact URL is only kept in process memory during a single download.
    // Never log it; MAX download URLs can contain access tokens.
    private String approvedDownloadUri;
    private int approvedDownloadTarget;
    /*
     * The runtime outlives an Activity.  Its delegate must therefore use the
     * application context and stay installed for the lifetime of that runtime;
     * otherwise a queued Gecko notification can call into a destroyed Activity.
     */
    private static NotificationManager notificationManager;
    private static WebNotification activeWebNotification;
    private static WebNotificationDelegate notificationDelegate;
    private static android.content.SharedPreferences notificationDiagnostics;

    // Counts only; never persist message contents, origins, tags or account data.
    private static void countNotificationEvent(String name) {
        if (BuildConfig.DEBUG && notificationDiagnostics != null) {
            notificationDiagnostics.edit().putInt(name,
                    notificationDiagnostics.getInt(name, 0) + 1).apply();
        }
    }

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        unlocked = BuildConfig.INTERNAL_AUDIT || !screenLockEnabled();
        // Previous activity instance is gone. Remove private copies left by earlier uploads.
        try {
            if (runtime == null) PrivateFiles.purge(getCacheDir());
            else PrivateFiles.purgeExpired(getCacheDir(), System.currentTimeMillis());
        } catch (IOException ignored) { /* A failed purge is reported by the explicit wipe action. */ }
        // Prevent screenshots and Android recent-app preview capture.
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_SECURE,
                WindowManager.LayoutParams.FLAG_SECURE);
        if (BuildConfig.INTERNAL_AUDIT) getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        createUi();
        if (Build.VERSION.SDK_INT >= 33) getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT, this::handleBack);
        if (BuildConfig.INTERNAL_AUDIT) {
            try { Class.forName("ru.drtrip.maxprivategecko.HardeningVerification")
                    .getMethod("run",android.content.Context.class).invoke(null,getApplicationContext()); }
            catch (ReflectiveOperationException ignored) { }
        }
        if (runtime == null) {
            final String privacyConfigPath;
            try {
                privacyConfigPath = installPrivacyConfig();
            } catch (java.io.IOException error) {
                status.setText(R.string.protection_unavailable);
                return;
            }
            ContentBlocking.Settings blocking = new ContentBlocking.Settings.Builder()
                    .antiTracking(ContentBlocking.AntiTracking.STRICT)
                    .cookieBehavior(ContentBlocking.CookieBehavior.ACCEPT_FIRST_PARTY)
                    .enhancedTrackingProtectionLevel(ContentBlocking.EtpLevel.STRICT)
                    .strictSocialTrackingProtection(true)
                    .safeBrowsing(ContentBlocking.SafeBrowsing.DEFAULT)
                    .build();
            GeckoRuntimeSettings settings = new GeckoRuntimeSettings.Builder()
                    .configFilePath(privacyConfigPath)
                    .contentBlocking(blocking)
                    .globalPrivacyControlEnabled(true)
                    .loginAutofillEnabled(false)
                    .isolatedProcessEnabled(true)
                    .allowInsecureConnections(GeckoRuntimeSettings.HTTPS_ONLY)
                    .setLnaEnabled(true)
                    .setLnaBlocking(true)
                    .setLnaBlockTrackers(true)
                    .aboutConfigEnabled(false)
                    .extensionsWebAPIEnabled(false)
                    .debugLogging(false)
                    .remoteDebuggingEnabled(false)
                    .consoleOutput(false)
                    .build();
            settings.setFingerprintingProtection(true);
            settings.setBaselineFingerprintingProtection(true);
            runtime = GeckoRuntime.create(getApplicationContext(), settings);
        }
        configureNotificationBridge();
        // Load MAX only after the built-in request blocker has been registered.
        status.setText(R.string.filter_connecting);
        runtime.getWebExtensionController()
                .ensureBuiltIn("resource://android/assets/max_guard/",
                        "max-private-gecko-guard@drtrip.local")
                .accept(extension -> runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    extension.setMessageDelegate(new WebExtension.MessageDelegate() {
                        @Override public GeckoResult<Object> onMessage(String app, Object message,
                                WebExtension.MessageSender sender) {
                            if (!"max_privacy_health".equals(app) || sender == null
                                    || sender.session != null
                                    || sender.environmentType != WebExtension.MessageSender.ENV_TYPE_EXTENSION
                                    || !sender.webExtension.isBuiltIn
                                    || !"max-private-gecko-guard@drtrip.local".equals(sender.webExtension.id)
                                    || !(message instanceof JSONObject)) return null;
                            JSONObject health = (JSONObject) message;
                            if (!"0.3.1".equals(health.optString("version")) || health.length() > 5) return null;
                            filterRegistered = health.optBoolean("ready", false);
                            org.json.JSONArray caps = health.optJSONArray("capabilities");
                            if (caps != null && caps.length() == 6) {
                                boolean[] values = new boolean[6];
                                boolean typed = true;
                                for (int i=0;i<6;i++) { typed &= caps.opt(i) instanceof Boolean; values[i]=caps.optBoolean(i,false); }
                                if (typed) webProbeResults = values;
                            }
                            filterProbeVerified = health.optBoolean("probeBlocked", false);
                            filterBlockedCount = Math.max(0, Math.min(1000000000, health.optInt("blocked", 0)));
                            if (filterProbeVerified) lastFilterProof = SystemClock.elapsedRealtime();
                            try { return GeckoResult.fromValue(new JSONObject("{\"ok\":true}")); }
                            catch (org.json.JSONException error) { return null; }
                        }
                    }, "max_privacy_health");
                    guardReady = false;
                    if (session == null) repairNotificationPermissionAndCreateSession();
                }), error -> runOnUiThread(() -> {
                    guardReady = false;
                    status.setText(R.string.filter_unavailable);
                    Toast.makeText(this, "Не удалось подключить блокировку AppTracer",
                            Toast.LENGTH_LONG).show();
                }));
    }

    private String installPrivacyConfig() throws java.io.IOException {
        try (java.io.InputStream source = getAssets().open("privacy-gecko.yaml");
                java.io.OutputStream target = openFileOutput("privacy-gecko.yaml", MODE_PRIVATE)) {
            byte[] buffer = new byte[4096];
            int length;
            while ((length = source.read(buffer)) != -1) target.write(buffer, 0, length);
        }
        return new java.io.File(getFilesDir(), "privacy-gecko.yaml").getAbsolutePath();
    }

    private static boolean isMaxNotificationOrigin(String origin) {
        if (origin == null) return false;
        // WebNotification.origin is a Gecko principal, not always a plain URL.
        // Accept only this application's exact container suffix; keep the full
        // original principal when acknowledging the notification to Gecko.
        int suffix = origin.indexOf('^');
        if (suffix >= 0) {
            String expected = "^geckoViewUserContextId=gvctx"
                    + new java.math.BigInteger(1,
                            CONTEXT.getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString(16);
            if (!expected.equals(origin.substring(suffix))) return false;
            origin = origin.substring(0, suffix);
        }
        try {
            Uri uri = Uri.parse(origin);
            return "https".equalsIgnoreCase(uri.getScheme())
                    && "web.max.ru".equalsIgnoreCase(uri.getHost())
                    && uri.getUserInfo() == null
                    && (uri.getPort() == -1 || uri.getPort() == 443);
        } catch (RuntimeException e) {
            return false;
        }
    }

    private void repairNotificationPermissionAndCreateSession() {
        // One-time migration of the old denial, without clearing any site data.
        if (!canShowNotifications(this)
                || notificationDiagnostics.getBoolean("permission_repair_158", false)) {
            createSession();
            return;
        }
        StorageController storage = runtime.getStorageController();
        storage.getPermissions(HOME, CONTEXT, false).accept(permissions -> {
            if (isFinishing() || isDestroyed() || clearing) return;
            for (GeckoSession.PermissionDelegate.ContentPermission permission : permissions) {
                if (permission.permission == GeckoSession.PermissionDelegate.PERMISSION_DESKTOP_NOTIFICATION
                        && !permission.privateMode
                        && CONTEXT.equals(permission.contextId)
                        && isMaxNotificationOrigin(permission.uri)
                        && permission.value == GeckoSession.PermissionDelegate.ContentPermission.VALUE_DENY) {
                    storage.setPermission(permission,
                            GeckoSession.PermissionDelegate.ContentPermission.VALUE_ALLOW);
                    countNotificationEvent("permission_repaired");
                }
            }
            // Query after the queued update before loading MAX, so it reads the
            // updated Notification.permission on its first page load.
            storage.getPermissions(HOME, CONTEXT, false).accept(updated -> {
                if (isFinishing() || isDestroyed() || clearing) return;
                notificationDiagnostics.edit().putBoolean("permission_repair_158", true).apply();
                if (session == null) createSession();
            }, error -> {
                countNotificationEvent("permission_repair_error");
                if (!isFinishing() && !isDestroyed() && session == null && !clearing) createSession();
            });
        }, error -> {
            countNotificationEvent("permission_repair_error");
            if (!isFinishing() && !isDestroyed() && session == null && !clearing) createSession();
        });
    }

    private static boolean canShowNotifications(android.content.Context context) {
        return (Build.VERSION.SDK_INT < 33
                || context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                == PackageManager.PERMISSION_GRANTED)
                && notificationManager != null
                && notificationManager.areNotificationsEnabled()
                && notificationManager.getNotificationChannel(MAX_NOTIF_CHANNEL) != null
                && notificationManager.getNotificationChannel(MAX_NOTIF_CHANNEL).getImportance()
                != NotificationManager.IMPORTANCE_NONE;
    }

    private void configureNotificationBridge() {
        final android.content.Context appContext = getApplicationContext();
        notificationDiagnostics = appContext.getSharedPreferences(
                "notification_diagnostics", MODE_PRIVATE);
        if (!BuildConfig.DEBUG) {
            boolean repaired = notificationDiagnostics.getBoolean("permission_repair_158", false);
            notificationDiagnostics.edit().clear().putBoolean("permission_repair_158", repaired).apply();
            appContext.deleteSharedPreferences("privacy_self_test");
        }
        countNotificationEvent("bridge_starts");
        notificationManager = (NotificationManager) appContext.getSystemService(NOTIFICATION_SERVICE);
        NotificationChannel channel = new NotificationChannel(
                MAX_NOTIF_CHANNEL, "Сообщения MAX · без звука", NotificationManager.IMPORTANCE_DEFAULT);
        channel.setSound(null, null);
        channel.enableVibration(false);
        // Hide even the generic notification on the lock screen.
        channel.setLockscreenVisibility(Notification.VISIBILITY_SECRET);
        notificationManager.createNotificationChannel(channel);
        if (notificationDelegate == null) {
            notificationDelegate = new NotificationBridge(appContext);
        }
        runtime.setWebNotificationDelegate(notificationDelegate);
        if (BuildConfig.DEBUG) Log.i(DIAGNOSTICS, "bridge ready; androidAllowed=" + canShowNotifications(appContext));
        if (!BuildConfig.INTERNAL_AUDIT && Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},
                    NOTIFICATION_PERMISSION_REQUEST);
        }
    }

    private static final class NotificationBridge implements WebNotificationDelegate {
        private final android.content.Context appContext;

        NotificationBridge(android.content.Context context) {
            appContext = context.getApplicationContext();
        }

                @Override public void onShowNotification(WebNotification webNotification) {
                    if (BuildConfig.DEBUG) Log.i(DIAGNOSTICS, "web show callback; maxOrigin="
                            + isMaxNotificationOrigin(webNotification.origin));
                    MAIN_HANDLER.post(() -> {
                        countNotificationEvent("web_show");
                        showGenericNotification(appContext, webNotification);
                    });
                }

                @Override public void onCloseNotification(WebNotification webNotification) {
                    MAIN_HANDLER.post(() -> {
                        countNotificationEvent("web_close");
                        if (BuildConfig.DEBUG) Log.i(DIAGNOSTICS, "web close callback; active="
                                + isActiveNotification(webNotification));
                        if (isActiveNotification(webNotification)) {
                            notificationManager.cancel(MAX_NOTIF_ID);
                            activeWebNotification = null;
                        }
                        // Required acknowledgement in GeckoView 158's API contract.
                        webNotification.dismiss();
                    });
                }
    }

    private static boolean isActiveNotification(WebNotification webNotification) {
        return activeWebNotification != null
                && java.util.Objects.equals(activeWebNotification.origin, webNotification.origin)
                && java.util.Objects.equals(activeWebNotification.tag, webNotification.tag);
    }

    private static void showGenericNotification(android.content.Context appContext,
            WebNotification webNotification) {
        if (notificationSuspended || !isMaxNotificationOrigin(webNotification.origin)
                || !canShowNotifications(appContext)) {
            countNotificationEvent("web_rejected");
            if (BuildConfig.DEBUG) Log.i(DIAGNOSTICS, "web notification rejected; maxOrigin="
                    + isMaxNotificationOrigin(webNotification.origin)
                    + "; androidAllowed=" + canShowNotifications(appContext));
            webNotification.dismiss();
            return;
        }
        Intent open = new Intent(appContext, MainActivity.class);
        open.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pending = PendingIntent.getActivity(appContext, 0, open,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification androidNotification = new Notification.Builder(appContext, MAX_NOTIF_CHANNEL)
                .setSmallIcon(android.R.drawable.ic_dialog_email)
                .setContentTitle("MAX Private Gecko")
                .setContentText("Новое сообщение в MAX")
                .setVisibility(Notification.VISIBILITY_SECRET)
                .setCategory(Notification.CATEGORY_MESSAGE)
                .setContentIntent(pending)
                .setAutoCancel(true)
                .build();
        try {
            notificationManager.notify(MAX_NOTIF_ID, androidNotification);
            // Gecko is notified only after Android accepted the notification.
            activeWebNotification = webNotification;
            webNotification.show();
            countNotificationEvent("web_posted");
            if (BuildConfig.DEBUG) Log.i(DIAGNOSTICS, "web notification posted");
        } catch (SecurityException ex) {
            countNotificationEvent("web_android_error");
            if (BuildConfig.DEBUG) Log.w(DIAGNOSTICS, "Android rejected web notification");
            webNotification.dismiss();
        }
    }

    private void testAndroidNotification() {
        if (!canShowNotifications(this)) {
            Toast.makeText(this, "Уведомления приложения или канала выключены",
                    Toast.LENGTH_LONG).show();
            if (BuildConfig.DEBUG) Log.i(DIAGNOSTICS, "test blocked by Android settings");
            return;
        }
        try {
            notificationManager.notify(TEST_NOTIF_ID,
                    new Notification.Builder(this, MAX_NOTIF_CHANNEL)
                            .setSmallIcon(android.R.drawable.ic_dialog_email)
                            .setContentTitle("MAX Private Gecko · проверка")
                            .setContentText("Проверочное уведомление Android")
                            .setVisibility(Notification.VISIBILITY_SECRET)
                            .setAutoCancel(true)
                            .setTimeoutAfter(60000)
                            .build());
            countNotificationEvent("android_test_posted");
            if (BuildConfig.DEBUG) Log.i(DIAGNOSTICS, "Android test posted");
            Toast.makeText(this, "Проверьте шторку уведомлений", Toast.LENGTH_LONG).show();
        } catch (SecurityException ex) {
            if (BuildConfig.DEBUG) Log.w(DIAGNOSTICS, "Android rejected test notification");
            Toast.makeText(this, "Android запретил уведомление", Toast.LENGTH_LONG).show();
        }
    }

    private void createUi() {
        LinearLayout base = new LinearLayout(this);
        base.setOrientation(LinearLayout.VERTICAL);
        base.setBackgroundColor(Color.rgb(18, 22, 28));
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(4), dp(4), dp(4), dp(4));
        // Compact native icons: every action retains accessible text and a tooltip.
        ImageButton back = makeToolbarButton(android.R.drawable.ic_menu_revert, "Назад", false);
        ImageButton reload = makeToolbarButton(android.R.drawable.ic_popup_sync,
                "Обновить (удержать: проверить уведомления)", false);
        ImageButton privacy = makeToolbarButton(android.R.drawable.ic_menu_preferences,
                getString(R.string.settings_title), false);
        ImageButton wipe = makeToolbarButton(android.R.drawable.ic_menu_delete,
                "Стереть данные приложения", true);
        addToolbarButton(bar, back);
        addToolbarButton(bar, reload);
        addToolbarButton(bar, privacy);
        addToolbarButton(bar, wipe);
        base.addView(bar);
        // Version is a separate widget: status updates must never hide it.
        LinearLayout statusRow = new LinearLayout(this);
        statusRow.setOrientation(LinearLayout.HORIZONTAL);
        statusRow.setGravity(Gravity.CENTER_VERTICAL);
        statusRow.setPadding(dp(6), 0, dp(10), dp(4));
        status = new TextView(this);
        status.setTextColor(Color.rgb(195, 207, 218));
        status.setTextSize(11);
        status.setPadding(dp(4), 0, dp(4), 0);
        status.setText(R.string.initial_status);
        statusRow.addView(status, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        TextView versionLabel = new TextView(this);
        versionLabel.setText(getString(R.string.version_label, BuildConfig.VERSION_NAME));
        versionLabel.setTextColor(Color.rgb(123, 217, 189));
        versionLabel.setTextSize(11);
        versionLabel.setSingleLine(true);
        statusRow.addView(versionLabel);
        base.addView(statusRow);
        view = new GeckoView(this);
        view.setAutofillEnabled(false);
        view.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS);
        base.addView(view, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1));
        appContent = base;
        FrameLayout shell = new FrameLayout(this);
        shell.addView(base, new FrameLayout.LayoutParams(-1,-1));
        LinearLayout cover = new LinearLayout(this);
        cover.setOrientation(LinearLayout.VERTICAL);
        cover.setGravity(Gravity.CENTER);
        cover.setPadding(dp(24),dp(24),dp(24),dp(24));
        cover.setBackgroundColor(Color.rgb(18,22,28));
        TextView notice = new TextView(this);
        notice.setText("MAX заблокирован\nРазблокировка выполняется системой Android. Код не передаётся MAX.");
        notice.setTextColor(Color.WHITE);
        cover.addView(notice);
        Button unlock = new Button(this);
        unlock.setText("Разблокировать");
        unlock.setOnClickListener(v -> requestUnlock());
        cover.addView(unlock);
        lockCover = cover;
        shell.addView(cover, new FrameLayout.LayoutParams(-1,-1));
        setContentView(shell);
        renderLock();
        base.setOnApplyWindowInsetsListener((v, insets) -> {
    v.setPadding(
            0,
            insets.getSystemWindowInsetTop(),
            0,
            insets.getSystemWindowInsetBottom()
    );
    return insets;
});
base.requestApplyInsets();
        back.setOnClickListener(v -> { if (session != null && canGoBack) session.goBack(); });
        reload.setOnClickListener(v -> { if (session != null && !clearing) session.reload(); });
        reload.setOnLongClickListener(v -> {
            testAndroidNotification();
            return true;
        });
        wipe.setOnClickListener(v -> confirmWipe());
        privacy.setOnClickListener(v -> showSettings());
    }

    private void showSettings() {
        if (!unlocked || clearing || isFinishing() || isDestroyed()) return;
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(18), dp(8), dp(18), dp(8));
        android.content.SharedPreferences options = getSharedPreferences("privacy_options", MODE_PRIVATE);
        android.widget.CheckBox lock = new android.widget.CheckBox(this);
        lock.setText(R.string.lock_setting);
        lock.setChecked(options.getBoolean("app_lock", true));
        KeyguardManager keyguard = (KeyguardManager)getSystemService(KEYGUARD_SERVICE);
        lock.setEnabled(keyguard != null && keyguard.isDeviceSecure());
        lock.setOnCheckedChangeListener((button, checked) -> options.edit().putBoolean("app_lock", checked).apply());
        content.addView(lock);
        TextView lockHint = new TextView(this);
        lockHint.setText(lock.isEnabled() ? R.string.lock_hint : R.string.lock_unavailable);
        content.addView(lockHint);
        android.widget.CheckBox clean = new android.widget.CheckBox(this);
        clean.setText(R.string.clean_setting);
        clean.setChecked(options.getBoolean("clean_images", true));
        clean.setOnCheckedChangeListener((button, checked) -> options.edit().putBoolean("clean_images", checked).apply());
        content.addView(clean);
        TextView cleanHint = new TextView(this);
        cleanHint.setText(R.string.clean_hint);
        content.addView(cleanHint);
        settingsButton(content, R.string.notifications_settings, () -> {
            Intent intent = new Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, getPackageName());
            try { startActivity(intent); } catch (ActivityNotFoundException ignored) {
                Toast.makeText(this, R.string.settings_unavailable, Toast.LENGTH_SHORT).show();
            }
        });
        settingsButton(content, R.string.privacy_report, this::showPrivacyDashboard);
        settingsButton(content, R.string.privacy_policy, () -> showDocument(getString(R.string.privacy_policy), "PRIVACY.md", false));
        settingsButton(content, R.string.licenses, () -> showDocument(getString(R.string.licenses), "THIRD_PARTY_NOTICES.md", false));
        settingsButton(content, R.string.gecko_licenses, () -> showDocument(getString(R.string.gecko_licenses), "GECKO-LICENSE.html", true));
        settingsButton(content, R.string.updates, () -> showDocument(getString(R.string.updates), "UPDATES.md", false));
        ScrollView scroll = new ScrollView(this);
        scroll.addView(content);
        new AlertDialog.Builder(this).setTitle(getString(R.string.settings_version, BuildConfig.VERSION_NAME))
                .setView(scroll).setPositiveButton(R.string.close, null).show();
    }

    private void settingsButton(LinearLayout content, int label, Runnable action) {
        Button button = new Button(this);
        button.setText(label);
        button.setOnClickListener(v -> { if (unlocked && !clearing) action.run(); });
        content.addView(button);
    }

    private void showDocument(String title, String asset, boolean html) {
        if (!unlocked || isFinishing() || isDestroyed()) return;
        try (InputStream input = getAssets().open("documents/" + asset)) {
            java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) != -1) {
                if (bytes.size() + count > 2000000) throw new IOException("Document too large");
                bytes.write(buffer, 0, count);
            }
            String text = new String(bytes.toByteArray(), java.nio.charset.StandardCharsets.UTF_8);
            TextView document = new TextView(this);
            document.setText(html ? android.text.Html.fromHtml(text, android.text.Html.FROM_HTML_MODE_LEGACY) : text);
            document.setTextIsSelectable(true);
            document.setPadding(dp(18), dp(12), dp(18), dp(12));
            ScrollView scroll = new ScrollView(this);
            scroll.addView(document);
            new AlertDialog.Builder(this).setTitle(title).setView(scroll).setPositiveButton(R.string.close, null).show();
        } catch (IOException error) {
            Toast.makeText(this, R.string.document_unavailable, Toast.LENGTH_LONG).show();
        }
    }

    // Display and report only app metadata. Never inspect accounts, messages,
    // filenames, full URLs, browser storage or notification content.
    private void showPrivacyDashboard() {
        if (isFinishing() || isDestroyed()) return;
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(false);
        scroll.setBackgroundColor(Color.rgb(25, 30, 38));
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(18), dp(6), dp(18), dp(18));
        scroll.addView(content);

        privacySection(content, "Защита устройства");
        privacyRow(content, "AppTracer Guard",
                guardReady ? "При запуске подтверждена отмена контрольного запроса .invalid" : "Блокировка не проверена");
        privacyRow(content, "Защита от отслеживания",
                runtime != null ? "Настроен строгий режим GeckoView" : "Движок не готов");
        privacyRow(content, "Настройки Gecko",
                privacyPassed ? "GPC, WebRTC, захват медиа, геолокация и WebAuthn проверены локально" : "Эффективные настройки не подтверждены");
        privacyRow(content, "Изоляция и видео", "Изоляция content включена; декодирование NDK/RDD. RDD имеет UID приложения.");
        privacyRow(content, "Граница фильтра", "Блокируется apptracer.ru и его поддомены. Это не отключает всю аналитику MAX.");
        privacyRow(content, "Защита экрана",
                screenCaptureBlocked() ? "FLAG_SECURE включён" : "Не подтверждена");
        privacyRow(content, "Блокировка входа", screenLockEnabled() ? "Включена" : "Выключена или не настроен код Android");

        privacySection(content, "Разрешения и файлы");
        privacyRow(content, "Контакты, SMS, камера, микрофон, геолокация, медиа",
                sensitivePermissionsSummary());
        privacyRow(content, "Выбор вложений",
                "Только выбранные файлы. Во время работы действует таймер очистки 1 час; после остановки процесса остатки очищаются при новом запуске.");
        privacyRow(content, "Очистка обычных фото", getSharedPreferences("privacy_options", MODE_PRIVATE).getBoolean("clean_images", true) ? "Включена, до 16 Мп" : "Выключена: отправка с предупреждением");
        privacyRow(content, "Документы, видео и анимация", "Передаются как есть только после предупреждения о метаданных. Оригиналы не меняются.");
        privacyRow(content, "Блокировки AppTracer",
                "В текущем процессе: " + filterBlockedCount + ". URL и содержимое запросов не сохраняются.");

        privacySection(content, "Уведомления");
        privacyRow(content, "Системный канал",
                canShowNotifications(this) ? "Разрешён" : "Отключён или недоступен");
        if (BuildConfig.DEBUG && notificationDiagnostics != null) {
            privacyRow(content, "Локальная диагностика",
                    "События: " + notificationDiagnostics.getInt("web_show", 0)
                    + " · публикации Android: "
                    + notificationDiagnostics.getInt("web_posted", 0));
        } else {
            privacyRow(content, "Локальная диагностика", "Недоступна в release");
        }

        privacySection(content, "О проекте");
        privacyRow(content, "Версия",
                BuildConfig.VERSION_NAME + " · сборка " + BuildConfig.VERSION_CODE);
        privacyRow(content, "Разработчик", "mac-sudo · независимый проект");
        privacyRow(content, "Ограничения",
                "Серверы MAX обрабатывают переписку и метаданные. "
                + "Оболочка не добавляет сквозное шифрование и не гарантирует анонимность.");
        TextView clipboardNotice = new TextView(this);
        clipboardNotice.setText(R.string.clipboard_notice);
        clipboardNotice.setTextColor(Color.rgb(160, 172, 182));
        clipboardNotice.setTextSize(11);
        clipboardNotice.setPadding(0, dp(14), 0, dp(2));
        content.addView(clipboardNotice);

        new AlertDialog.Builder(this)
                .setTitle("Центр приватности · v" + BuildConfig.VERSION_NAME)
                .setView(scroll)
                .setNeutralButton("Скопировать отчёт", (dialog, which) -> copyPrivacyReport())
                .setPositiveButton("Закрыть", null)
                .show();
    }

    private void privacySection(LinearLayout root, String heading) {
        TextView title = new TextView(this);
        title.setText(heading);
        title.setTextColor(Color.rgb(241, 246, 248));
        title.setTextSize(15);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        title.setPadding(0, dp(16), 0, dp(7));
        root.addView(title);
        View line = new View(this);
        line.setBackgroundColor(Color.rgb(59, 79, 85));
        root.addView(line, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(1)));
    }

    private void privacyRow(LinearLayout root, String title, String detail) {
        TextView name = new TextView(this);
        name.setText(title);
        name.setTextColor(Color.rgb(125, 231, 203));
        name.setTextSize(12);
        name.setPadding(0, dp(10), 0, dp(2));
        root.addView(name);
        TextView value = new TextView(this);
        value.setText(detail);
        value.setTextColor(Color.rgb(222, 229, 235));
        value.setTextSize(13);
        value.setPadding(0, 0, 0, dp(4));
        root.addView(value);
    }

    private boolean screenCaptureBlocked() {
        return (getWindow().getAttributes().flags
                & WindowManager.LayoutParams.FLAG_SECURE) != 0;
    }

    // Human-readable local diagnostics, built only after the user presses Copy.
    // Do not include chat activity, notification contents, accounts, tokens, URLs,
    // document names, cookie values, device identifiers, or network payloads.
    private String buildPrivacyReport() {
        StringBuilder report = new StringBuilder("MAX Private Gecko — технический отчёт\n");
        report.append("Версия: ").append(BuildConfig.VERSION_NAME)
                .append(" (сборка ").append(BuildConfig.VERSION_CODE).append(")\n");
        report.append("AppTracer Guard: ")
                .append(guardReady ? "подключён" : "не подтверждено").append('\n');
        report.append("GeckoView: ")
                .append("159.0.20261022150525; ")
                .append(privacyPassed ? "локальная проверка защит пройдена" : "эффективные защиты не подтверждены")
                .append('\n');
        report.append("Фильтр: apptracer.ru и поддомены; контрольная отмена .invalid: ")
                .append(guardReady ? "подтверждена при запуске" : "не подтверждена").append('\n');
        report.append("Изоляция content включена; RDD имеет UID приложения.\n");
        report.append("Скриншоты: ")
                .append(screenCaptureBlocked() ? "заблокированы FLAG_SECURE" : "защита не подтверждена")
                .append('\n');
        report.append("Чувствительные разрешения: ")
                .append(sensitivePermissionsSummary()).append('\n');
        report.append("Уведомления Android: ")
                .append(canShowNotifications(this) ? "разрешены" : "недоступны")
                .append('\n');
        if (BuildConfig.DEBUG && notificationDiagnostics != null) {
            // Technical callback counts only; these are not verified message counts.
            report.append("События уведомлений: ")
                    .append(notificationDiagnostics.getInt("web_show", 0)).append('\n');
            report.append("Публикации Android: ")
                    .append(notificationDiagnostics.getInt("web_posted", 0)).append('\n');
        }
        report.append("Блокировки в текущем процессе (без URL): ").append(filterBlockedCount).append('\n');
        report.append("IP и серверная аналитика MAX не скрываются.\n");
        report.append("Этот отчёт формируется локально и не отправляется автоматически.\n");
        report.append("Отчёт не содержит содержимое переписки и вложений.\n");
        return report.toString();
    }

    private void copyPrivacyReport() {
        try {
            ClipboardManager clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            if (clipboard == null) throw new IllegalStateException("Clipboard unavailable");
            clipboard.setPrimaryClip(ClipData.newPlainText(
                    "MAX Private Gecko · защита", buildPrivacyReport()));
            Toast.makeText(this, "Технический отчёт скопирован", Toast.LENGTH_SHORT).show();
        } catch (RuntimeException error) {
            Toast.makeText(this, "Не удалось скопировать отчёт", Toast.LENGTH_SHORT).show();
        }
    }

    private String sensitivePermissionsSummary() {
        final String[] sensitive = {
                Manifest.permission.READ_CONTACTS,
                Manifest.permission.WRITE_CONTACTS,
                Manifest.permission.READ_SMS,
                Manifest.permission.SEND_SMS,
                Manifest.permission.RECEIVE_SMS,
                Manifest.permission.CAMERA,
                Manifest.permission.RECORD_AUDIO,
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION,
                Manifest.permission.READ_EXTERNAL_STORAGE,
                Manifest.permission.WRITE_EXTERNAL_STORAGE,
                "android.permission.MANAGE_EXTERNAL_STORAGE",
                "android.permission.READ_MEDIA_VISUAL_USER_SELECTED",
                "android.permission.READ_MEDIA_IMAGES",
                "android.permission.READ_MEDIA_VIDEO",
                "android.permission.READ_MEDIA_AUDIO"
        };
        try {
            android.content.pm.PackageInfo info = getPackageManager().getPackageInfo(
                    getPackageName(), PackageManager.GET_PERMISSIONS);
            if (info.requestedPermissions == null) return "Не запрашиваются приложением";
            int requested = 0;
            for (String permission : info.requestedPermissions) {
                for (String item : sensitive) {
                    if (item.equals(permission)) {
                        requested++;
                        break;
                    }
                }
            }
            return requested == 0 ? "Не запрашиваются приложением"
                    : "В APK обнаружены чувствительные разрешения (" + requested + ") — проверь настройки";
        } catch (PackageManager.NameNotFoundException error) {
            return "Не удалось проверить разрешения";
        }
    }

    private ImageButton makeToolbarButton(int iconRes, String description, boolean destructive) {
        ImageButton button = new ImageButton(this);
        button.setImageResource(iconRes);
        button.setColorFilter(destructive ? Color.rgb(248, 153, 153)
                : Color.rgb(155, 232, 211));
        button.setContentDescription(description);
        button.setTooltipText(description);
        button.setScaleType(android.widget.ImageView.ScaleType.CENTER_INSIDE);
        button.setPadding(dp(12), dp(11), dp(12), dp(11));
        GradientDrawable surface = new GradientDrawable();
        surface.setColor(Color.rgb(35, 44, 53));
        surface.setCornerRadius(dp(10));
        surface.setStroke(dp(1), destructive ? Color.rgb(114, 62, 65)
                : Color.rgb(57, 79, 86));
        button.setBackground(new RippleDrawable(
                ColorStateList.valueOf(Color.argb(60, 81, 210, 174)), surface, null));
        return button;
    }

    private void addToolbarButton(LinearLayout bar, ImageButton button) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, dp(44), 1);
        params.setMargins(dp(3), 0, dp(3), 0);
        bar.addView(button, params);
    }
    private int dp(int value) { return (int)(value * getResources().getDisplayMetrics().density); }

    private void createSession() {
        if (clearing) return;
        GeckoSessionSettings settings = new GeckoSessionSettings.Builder()
                .allowJavascript(true) // Essential for MAX Web functionality.
                .contextId(CONTEXT) // Partitioned site data, not shared with other browsers.
                .usePrivateMode(false) // Keep QR-login between app launches.
                .useTrackingProtection(true)
                .userAgentMode(GeckoSessionSettings.USER_AGENT_MODE_MOBILE)
                .viewportMode(GeckoSessionSettings.VIEWPORT_MODE_MOBILE)
                .build();
        session = new GeckoSession(settings);
        session.setContentDelegate(new GeckoSession.ContentDelegate() {
            @Override public void onTitleChange(GeckoSession s, String title) {
                if (s != session || !probeActive || title == null) return;
                if (title.matches("MAX_PRIVACY_PROBE:[01]:[01]:[01]:[01]:[01]:[01]")) {
                    String[] values = title.substring("MAX_PRIVACY_PROBE:".length()).split(":");
                    probeResults = new boolean[values.length];
                    for (int i = 0; i < values.length; i++) probeResults[i] = "1".equals(values[i]);
                    completePrivacyProbe();
                } else if (BuildConfig.INTERNAL_AUDIT && title.matches("MAX_MEDIA_PROBE:[01]:[01]:(OK|NotAllowedError|NotSupportedError|TypeError|Error|Other):(OK|NotAllowedError|NotSupportedError|TypeError|Error|Other)")) {
                    Log.i("MaxPrivacyCheck", title);
                    String[] result = title.substring("MAX_MEDIA_PROBE:".length()).split(":");
                    verificationMediaResult = result[0]+":"+result[1];
                    verificationMediaDetail = result[2]+"/"+result[3];
                    verificationMediaComplete = true;
                    completePrivacyProbe();
                }
            }
            @Override public void onExternalResponse(GeckoSession s, WebResponse response) {
                handleExternalDownloadResponse(s, response);
            }
        });
        session.setPromptDelegate(new GeckoSession.PromptDelegate() {
            @Override public GeckoResult<PromptResponse> onFilePrompt(GeckoSession s, FilePrompt prompt) {
                // A user-controlled Android Documents picker; no storage permissions.
                if (s != session || clearing || isFinishing() || isDestroyed()
                        || pendingFilePrompt != null
                        || (prompt.type != FilePrompt.Type.SINGLE
                            && prompt.type != FilePrompt.Type.MULTIPLE)) {
                    // Folder uploads remain deliberately unsupported.
                    return GeckoResult.fromValue(prompt.dismiss());
                }
                Intent picker = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                picker.addCategory(Intent.CATEGORY_OPENABLE);
                picker.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                if (prompt.mimeTypes != null && prompt.mimeTypes.length == 1
                        && prompt.mimeTypes[0] != null
                        && prompt.mimeTypes[0].contains("/")) {
                    picker.setType(prompt.mimeTypes[0]);
                } else {
                    picker.setType("*/*");
                    if (prompt.mimeTypes != null && prompt.mimeTypes.length > 1) {
                        picker.putExtra(Intent.EXTRA_MIME_TYPES, prompt.mimeTypes);
                    }
                }
                picker.putExtra(Intent.EXTRA_ALLOW_MULTIPLE,
                        prompt.type == FilePrompt.Type.MULTIPLE);
                GeckoResult<PromptResponse> result = new GeckoResult<>();
                pendingFilePrompt = prompt;
                pendingFileResult = result;
                try {
                    startActivityForResult(picker, FILE_PICKER_REQUEST);
                } catch (ActivityNotFoundException | SecurityException e) {
                    pendingFilePrompt = null;
                    pendingFileResult = null;
                    result.complete(prompt.dismiss());
                    Toast.makeText(MainActivity.this,
                            "Не удалось открыть системный выбор файлов", Toast.LENGTH_SHORT).show();
                }
                return result;
            }
            @Override public GeckoResult<PromptResponse> onSharePrompt(GeckoSession s, SharePrompt prompt) {
                return GeckoResult.fromValue(prompt.dismiss());
            }
        });
        session.setPermissionDelegate(new GeckoSession.PermissionDelegate() {
            @Override public void onAndroidPermissionsRequest(GeckoSession s, String[] perms,
                    GeckoSession.PermissionDelegate.Callback cb) {
                cb.reject(); // No camera, location, recording, etc.
            }
            @Override public GeckoResult<Integer> onContentPermissionRequest(GeckoSession s,
                    GeckoSession.PermissionDelegate.ContentPermission perm) {
                // Only synthetic local playback in the separate verification package.
                // Daily MAX still requires user-initiated playback, as before.
                if (BuildConfig.INTERNAL_AUDIT && probeActive && !webProofStarted && s == session
                        && (perm.permission == GeckoSession.PermissionDelegate.PERMISSION_AUTOPLAY_INAUDIBLE
                            || perm.permission == GeckoSession.PermissionDelegate.PERMISSION_AUTOPLAY_AUDIBLE)) {
                    return GeckoResult.fromValue(GeckoSession.PermissionDelegate.ContentPermission.VALUE_ALLOW);
                }
                // Other site permissions (location, microphone, storage exceptions) stay denied.
                boolean allow = perm.permission ==
                        GeckoSession.PermissionDelegate.PERMISSION_DESKTOP_NOTIFICATION
                        && isMaxNotificationOrigin(perm.uri)
                        && canShowNotifications(MainActivity.this);
                if (perm.permission == GeckoSession.PermissionDelegate.PERMISSION_DESKTOP_NOTIFICATION) {
                    countNotificationEvent(allow ? "permission_allowed" : "permission_denied");
                    if (BuildConfig.DEBUG) Log.i(DIAGNOSTICS, "web permission request; maxOrigin="
                            + isMaxNotificationOrigin(perm.uri) + "; allow=" + allow);
                }
                return GeckoResult.fromValue(allow
                        ? GeckoSession.PermissionDelegate.ContentPermission.VALUE_ALLOW
                        : GeckoSession.PermissionDelegate.ContentPermission.VALUE_DENY);
            }
            @Override public void onMediaPermissionRequest(GeckoSession s, String uri,
                    GeckoSession.PermissionDelegate.MediaSource[] video,
                    GeckoSession.PermissionDelegate.MediaSource[] audio,
                    GeckoSession.PermissionDelegate.MediaCallback cb) {
                cb.reject();
            }
        });
        session.setNavigationDelegate(new GeckoSession.NavigationDelegate() {
            @Override public GeckoResult<org.mozilla.geckoview.AllowOrDeny> onLoadRequest(
                    GeckoSession s, GeckoSession.NavigationDelegate.LoadRequest req) {
                if (probeActive && (PRIVACY_PROBE.equals(req.uri)
                        || (BuildConfig.INTERNAL_AUDIT && (PRIVACY_PROBE+"?media=1").equals(req.uri)))) return GeckoResult.allow();
                // Restricts top-level navigation, NOT all subresources / WebSockets.
                if (allowedTopLevel(req.uri)) return GeckoResult.allow();
                // blob: URLs are browser-local, not network hosts. Only a direct
                // user action in MAX may open its selected blob in this session.
                // If Gecko classifies it as a download, onExternalResponse provides
                // the stream; otherwise Gecko may display it inline.
                if (s == session && isMaxBlobUri(req.uri)
                        && exactHttpsHost(req.triggerUri, "web.max.ru")
                        && req.hasUserGesture && !req.isRedirect
                        && req.target == GeckoSession.NavigationDelegate.TARGET_WINDOW_CURRENT
                        && !clearing && !downloadInProgress && !downloadFetchPending
                        && !isFinishing() && !isDestroyed()) {
                    approvedDownloadAt = SystemClock.elapsedRealtime();
                    approvedDownloadUri = req.uri; // Private; never logged or persisted.
                    approvedDownloadTarget = req.target;
                    if (BuildConfig.DEBUG) Log.i("MaxDownloadDiag", "user-initiated MAX blob navigation allowed");
                    return GeckoResult.allow();
                }
                if (s == session && downloadFetchPending && approvedDownloadUri != null
                        && approvedDownloadUri.equals(req.uri)) {
                    // The web client may retry window.open while its original
                    // action awaits completion. Do not issue duplicate fetches.
                    if (BuildConfig.DEBUG) Log.i("MaxDownloadDiag", "duplicate download navigation ignored");
                    return GeckoResult.deny();
                }
                if (s == session && isUserInitiatedMaxDownload(req)
                        && !downloadFetchPending && !downloadInProgress && !clearing) {
                    // Intercept this one user action without opening a browser
                    // window, broadening top-level navigation, or exposing cookies.
                    approvedDownloadAt = SystemClock.elapsedRealtime();
                    approvedDownloadUri = req.uri; // never logged or persisted
                    approvedDownloadTarget = req.target;
                    downloadFetchPending = true;
                    if (BuildConfig.DEBUG) Log.i("MaxDownloadDiag", "anonymous direct fetch started");
                    fetchApprovedDownload(req.uri);
                    return GeckoResult.deny();
                }
                // Record enum/boolean diagnostics only, never URLs or tokens.
                if (BuildConfig.DEBUG) Log.i("MaxDownloadDiag",
                        "download guard: targetWindow=" + req.target
                        + " originOK=" + exactHttpsHost(req.triggerUri, "web.max.ru")
                        + " fileHostOK=" + exactHttpsHost(req.uri, "fd.oneme.ru"));
                // Only scheme/hostname metadata. Never log URL paths, query strings,
                // attachment names, account identifiers, or download tokens.
                if (BuildConfig.DEBUG) {
                    Log.i("MaxDownloadDiag", "blocked target=" + safeDownloadUriMetadata(req.uri)
                            + " source=" + safeDownloadUriMetadata(req.triggerUri)
                            + " gesture=" + req.hasUserGesture
                            + " redirect=" + req.isRedirect);
                }
                Toast.makeText(MainActivity.this, "Внешний адрес заблокирован", Toast.LENGTH_SHORT).show();
                return GeckoResult.deny();
            }
            @Override public GeckoResult<GeckoSession> onNewSession(GeckoSession s, String uri) {
                if (downloadFetchPending) {
                    if (BuildConfig.DEBUG) Log.i("MaxDownloadDiag", "popup denied while direct fetch active");
                    return null;
                }
                // No generic popup support. Only the exact, recently user-requested
                // download on fd.oneme.ru may create a temporary session.
                if (s != session || clearing || isFinishing() || isDestroyed()
                        || downloadInProgress || downloadPopupSession != null
                        || approvedDownloadAt == 0 || approvedDownloadUri == null
                        || approvedDownloadTarget != GeckoSession.NavigationDelegate.TARGET_WINDOW_NEW
                        || !approvedDownloadUri.equals(uri)
                        || !exactHttpsHost(uri, "fd.oneme.ru")
                        || SystemClock.elapsedRealtime() - approvedDownloadAt > 10000) {
                    if (BuildConfig.DEBUG) Log.i("MaxDownloadDiag", "other popup denied");
                    return null;
                }
                final GeckoSession popup = new GeckoSession(
                        new GeckoSessionSettings.Builder()
                                .allowJavascript(false)
                                .contextId(CONTEXT)
                                .usePrivateMode(false)
                                .useTrackingProtection(true)
                                .userAgentMode(GeckoSessionSettings.USER_AGENT_MODE_MOBILE)
                                .viewportMode(GeckoSessionSettings.VIEWPORT_MODE_MOBILE)
                                .build());
                popup.setContentDelegate(new GeckoSession.ContentDelegate() {
                    @Override public void onExternalResponse(GeckoSession source, WebResponse response) {
                        handleExternalDownloadResponse(source, response);
                    }
                });
                popup.setNavigationDelegate(new GeckoSession.NavigationDelegate() {
                    @Override public GeckoResult<org.mozilla.geckoview.AllowOrDeny> onLoadRequest(
                            GeckoSession source, GeckoSession.NavigationDelegate.LoadRequest request) {
                        boolean permitted = source == downloadPopupSession
                                && approvedDownloadAt != 0
                                && SystemClock.elapsedRealtime() - approvedDownloadAt <= 120000
                                && approvedDownloadUri != null
                                && exactHttpsHost(request.uri, "fd.oneme.ru")
                                && (approvedDownloadUri.equals(request.uri)
                                    || (request.isRedirect
                                        && exactHttpsHost(request.triggerUri, "fd.oneme.ru")));
                        if (!permitted && BuildConfig.DEBUG)
                            Log.i("MaxDownloadDiag", "popup navigation denied");
                        return permitted ? GeckoResult.allow() : GeckoResult.deny();
                    }
                    @Override public GeckoResult<GeckoSession> onNewSession(GeckoSession source,
                            String requestedUri) { return null; }
                });
                popup.setPermissionDelegate(new GeckoSession.PermissionDelegate() {
                    @Override public void onAndroidPermissionsRequest(GeckoSession source,
                            String[] permissions, GeckoSession.PermissionDelegate.Callback callback) {
                        callback.reject();
                    }
                    @Override public GeckoResult<Integer> onContentPermissionRequest(
                            GeckoSession source, GeckoSession.PermissionDelegate.ContentPermission permission) {
                        return GeckoResult.fromValue(
                                GeckoSession.PermissionDelegate.ContentPermission.VALUE_DENY);
                    }
                    @Override public void onMediaPermissionRequest(GeckoSession source,
                            String requestedUri,
                            GeckoSession.PermissionDelegate.MediaSource[] video,
                            GeckoSession.PermissionDelegate.MediaSource[] audio,
                            GeckoSession.PermissionDelegate.MediaCallback callback) {
                        callback.reject();
                    }
                });
                downloadPopupSession = popup; // Strong reference; Gecko opens it automatically.
                if (BuildConfig.DEBUG) Log.i("MaxDownloadDiag", "popup download session created");
                MAIN_HANDLER.postDelayed(() -> {
                    if (downloadPopupSession == popup && !downloadInProgress) {
                        if (BuildConfig.DEBUG) Log.i("MaxDownloadDiag", "popup download timed out");
                        closeDownloadPopupSession();
                    }
                }, 120000);
                return GeckoResult.fromValue(popup);
            }
            @Override public void onCanGoBack(GeckoSession s, boolean enabled) {
                canGoBack = enabled;
            }
            @Override public void onLocationChange(GeckoSession s, String url,
                    java.util.List<GeckoSession.PermissionDelegate.ContentPermission> p,
                    Boolean userGesture) {
                if (!probeActive && url != null) status.setText(getString(R.string.origin_status,
                        displayOrigin(url), getString(privacyPassed ? R.string.protection_passed : R.string.protection_unverified)));
            }
        });
        session.open(runtime);
        view.setSession(session);
        org.mozilla.geckoview.BasicSelectionActionDelegate selection =
                new org.mozilla.geckoview.BasicSelectionActionDelegate(this) {
                    @Override public GeckoResult<org.mozilla.geckoview.AllowOrDeny>
                            onShowClipboardPermissionRequest(GeckoSession s,
                                    GeckoSession.SelectionActionDelegate.ClipboardPermission permission) {
                        return GeckoResult.deny();
                    }
                };
        // Keep explicit Copy/Paste actions, but deny script clipboard reads and
        // prevent other apps from offering automatic text processing actions.
        selection.enableExternalActions(false);
        session.setSelectionActionDelegate(selection);
        session.setActive(started);
        session.setFocused(resumed && hasWindowFocus());
        if (BuildConfig.DEBUG && getIntent().getBooleanExtra("privacy_self_test", false)) {
            try {
                Class.forName("ru.drtrip.maxprivategecko.PrivacySelfTest")
                        .getMethod("install", Activity.class, GeckoSession.class)
                        .invoke(null, this, session);
            } catch (ReflectiveOperationException error) {
                throw new IllegalStateException("Privacy self-test is unavailable", error);
            }
        }
        probeActive = true;
        privacyPassed = false;
        probeResults = null;
        webProofStarted = false;
        verificationMediaComplete = false;
        webProbeResults = null;
        filterProbeVerified = false;
        probeAttempts = 0;
        status.setText("Локальная проверка защит и сетевого фильтра…");
        session.loadUri(PRIVACY_PROBE + (BuildConfig.INTERNAL_AUDIT ? "?media=1" : ""));
        MAIN_HANDLER.postDelayed(probeCompletion, 100);
    }

    private void completePrivacyProbe() {
        MAIN_HANDLER.removeCallbacks(probeCompletion);
        if (!probeActive || isFinishing() || isDestroyed() || session == null || clearing) return;
        if (!webProofStarted && probeResults != null && filterRegistered
                && (unlocked || BuildConfig.INTERNAL_AUDIT)
                && (!BuildConfig.INTERNAL_AUDIT || verificationMediaComplete)) {
            webProofStarted = true;
            view.setVisibility(View.INVISIBLE);
            session.loadUri(HOME);
        }
        if (webProofStarted && webProbeResults != null && filterRegistered && filterProbeVerified) {
            boolean required = true;
            for (int i = 0; i < 5; i++) required &= webProbeResults[i];
            guardReady = true;
            privacyPassed = required;
            if (BuildConfig.INTERNAL_AUDIT) {
                boolean photoCheck = false;
                try { photoCheck = Boolean.TRUE.equals(Class.forName("ru.drtrip.maxprivategecko.HardeningVerification").getMethod("passed").invoke(null)); }
                catch (ReflectiveOperationException ignored) { }
                Log.i("MaxPrivacyCheck", "privacy=" + required + "; guard=" + guardReady
                        + "; h264=" + webProbeResults[5] + "; isolated=" + runtime.getSettings().getIsolatedProcessEnabled()
                        + "; media=" + verificationMediaResult + "; afterWipe=" + verificationWipeFinished);
                status.setText(getString(R.string.verification_status, Boolean.toString(required),
                        Boolean.toString(webProbeResults[5]), verificationMediaResult, verificationMediaDetail,
                        Boolean.toString(photoCheck), Boolean.toString(verificationWipeFinished)));
                if (required && photoCheck && "1:1".equals(verificationMediaResult) && !verificationWipeAttempted) {
                    verificationWipeAttempted = true;
                    seedVerificationWipe();
                }
                return; // Never load an account in the verification package.
            }
            probeActive = false;
            if (required) {
                notificationSuspended = false;
                view.setVisibility(View.VISIBLE);
            }
            else { session.loadUri(PRIVACY_PROBE); view.setVisibility(View.VISIBLE); status.setText(R.string.protection_failed); }
            return;
        }
        if ((!unlocked && !BuildConfig.INTERNAL_AUDIT)
                || (BuildConfig.INTERNAL_AUDIT && !verificationMediaComplete)) {
            MAIN_HANDLER.postDelayed(probeCompletion,100); return;
        }
        if (++probeAttempts >= 300) {
            session.loadUri(PRIVACY_PROBE);
            view.setVisibility(View.VISIBLE);
            status.setText(R.string.protection_timeout);
            if (BuildConfig.INTERNAL_AUDIT) Log.i("MaxPrivacyCheck", "privacy=timeout; filter=" + filterRegistered + "; probe=" + filterProbeVerified + "; web=" + webProofStarted + "; capabilities=" + (webProbeResults != null));
            return;
        }
        MAIN_HANDLER.postDelayed(probeCompletion, 100);
    }

    private static String displayOrigin(String url) {
        if (url == null) return "адрес не определён";
        try {
            Uri uri = Uri.parse(url);
            if ("https".equalsIgnoreCase(uri.getScheme())) return uri.getHost();
            if ("blob".equalsIgnoreCase(uri.getScheme())) return "локальное вложение";
        } catch (RuntimeException ignored) { }
        return "локальная страница";
    }

    // A tightly scoped exception, not a general allowlist for oneme.ru.
    // Only a real user gesture in the MAX web page can initiate a file download.
    private static boolean exactHttpsHost(String value, String hostname) {
        if (value == null) return false;
        try {
            Uri uri = Uri.parse(value);
            return "https".equalsIgnoreCase(uri.getScheme())
                    && hostname.equalsIgnoreCase(uri.getHost())
                    && uri.getUserInfo() == null
                    && (uri.getPort() == -1 || uri.getPort() == 443);
        } catch (RuntimeException e) { return false; }
    }

    private static boolean isUserInitiatedMaxDownload(
            GeckoSession.NavigationDelegate.LoadRequest request) {
        return request.hasUserGesture && !request.isRedirect
                && exactHttpsHost(request.triggerUri, "web.max.ru")
                && exactHttpsHost(request.uri, "fd.oneme.ru")
                && (request.target == GeckoSession.NavigationDelegate.TARGET_WINDOW_NEW
                    || request.target == GeckoSession.NavigationDelegate.TARGET_WINDOW_CURRENT
                    || request.target == GeckoSession.NavigationDelegate.TARGET_WINDOW_NONE);
    }

    // Log only scheme + hostname, never a full URL. Unknown schemes remain blocked.
    private static String safeDownloadUriMetadata(String text) {
        if (text == null) return "none";
        try {
            Uri uri = Uri.parse(text);
            String scheme = uri.getScheme();
            String host = uri.getHost();
            // Hard cap metadata lengths; no app or user-provided pathname is logged.
            if (scheme == null) scheme = "none";
            if (host == null) host = "none";
            scheme = scheme.length() > 32 ? scheme.substring(0, 32) : scheme;
            host = host.length() > 128 ? host.substring(0, 128) : host;
            return scheme + "://" + host;
        } catch (RuntimeException e) {
            return "invalid";
        }
    }

    // No scheme-relative, Unicode tricks, HTTP, userinfo, or suffix spoofing.
    private static boolean allowedTopLevel(String text) {
        if (text == null) return false;
        try {
            Uri uri = Uri.parse(text);
            if (!"https".equalsIgnoreCase(uri.getScheme())) return false;
            if (uri.getPort() != -1 && uri.getPort() != 443) return false;
            String host = uri.getHost();
            if (host == null || uri.getUserInfo() != null) return false;
            host = host.toLowerCase(java.util.Locale.ROOT);
            return host.equals("max.ru") || host.equals("web.max.ru");
        } catch (RuntimeException e) { return false; }
    }

    private void confirmWipe() {
        if (clearing) return;
        new AlertDialog.Builder(this).setTitle("Стереть данные MAX?")
                .setMessage("Будут остановлены загрузки и удалены cookie, данные сайта, временные копии вложений и уведомления. Потребуется новый вход. Уже сохранённые вами документы и данные на сервере MAX не удаляются.")
                .setNegativeButton("Отмена", null)
                .setPositiveButton("Удалить", (dialog, which) -> wipeStorage())
                .show();
    }

    private void detachAndClose() {
        dismissPendingFilePrompt();
        if (view != null) view.releaseSession();
        if (session != null) {
            session.close();
            session = null;
        }
        canGoBack = false;
    }
    private void cancelNetworkTransfers() {
        if (activeDownloadRequest != null) {
            activeDownloadRequest.cancel();
            activeDownloadRequest = null;
        }
        closeDownloadPopupSession();
        approvedDownloadAt = 0;
        approvedDownloadUri = null;
        downloadFetchPending = false;
        pendingSaveFile = null;
        downloadInProgress = false;
    }

    private void wipeStorage() {
        if (runtime == null || clearing) return;
        clearing = true;
        notificationSuspended = true;
        status.setText("Остановка загрузок и удаление локальных копий…");
        detachAndClose();
        cancelNetworkTransfers();
        TransferScope old = transfers;
        old.markCancelled();
        if (notificationManager != null) notificationManager.cancelAll();
        if (activeWebNotification != null) activeWebNotification.dismiss();
        activeWebNotification = null;
        new Thread(() -> {
            try {
                old.cancel();
                if (!old.awaitStopped(10)) throw new IOException("Transfer still running");
                PrivateFiles.purge(getCacheDir());
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    runtime.getStorageController().clearData(StorageController.ClearFlags.ALL)
                            .accept(ignored -> runOnUiThread(() -> {
                                if (isFinishing() || isDestroyed()) return;
                                if (BuildConfig.INTERNAL_AUDIT && verificationWipeAttempted) {
                                    verificationWipeFinished = !new File(getCacheDir(),FILE_CACHE_DIR).exists()
                                            && !new File(getCacheDir(),DOWNLOAD_CACHE_DIR).exists()
                                            && pendingSaveFile == null && approvedDownloadUri == null
                                            && activeDownloadRequest == null && downloadPopupSession == null;
                                    Log.i("MaxPrivacyCheck","wipe="+verificationWipeFinished);
                                }
                                transfers = new TransferScope();
                                clearing = false;
                                status.setText("Данные сайта и временные копии удалены");
                                createSession();
                            }), error -> runOnUiThread(this::wipeFailed));
                });
            } catch (IOException | InterruptedException error) {
                runOnUiThread(this::wipeFailed);
            }
        }, "PrivateDataWipe").start();
    }

    private void wipeFailed() {
        if (BuildConfig.INTERNAL_AUDIT) Log.i("MaxPrivacyCheck","wipe=false");
        if (isFinishing() || isDestroyed()) return;
        clearing = false;
        status.setText("Очистка не завершена — данные могли сохраниться. Повторите очистку.");
        Toast.makeText(this, "Не удалось подтвердить полную очистку. MAX не загружен.", Toast.LENGTH_LONG).show();
    }

    private void seedVerificationWipe() {
        if (!BuildConfig.INTERNAL_AUDIT) return;
        try {
            for (String name : new String[]{FILE_CACHE_DIR,DOWNLOAD_CACHE_DIR}) {
                File folder = new File(getCacheDir(),name);
                if (!folder.isDirectory() && !folder.mkdirs()) throw new IOException("fixture cache");
                File file = new File(folder,"synthetic-wipe-fixture.tmp");
                transfers.own(file);
                try (OutputStream out = new FileOutputStream(file)) { out.write(new byte[]{1,2,3}); }
                pendingSaveFile = file;
            }
            approvedDownloadUri = "https://fd.oneme.ru/synthetic-never-requested";
            downloadFetchPending = true;
            wipeStorage();
        } catch (IOException error) { Log.i("MaxPrivacyCheck","wipe=false; fixture-error"); }
    }

    @Override @SuppressWarnings("deprecation") protected void onActivityResult(
            int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == UNLOCK_REQUEST) {
            credentialInFlight = false;
            unlocked = resultCode == RESULT_OK;
            if (unlocked) backgroundAt = 0;
            renderLock();
            return;
        }
        if (requestCode == DOWNLOAD_SAVE_REQUEST) {
            saveSelectedDownload(resultCode, data);
            return;
        }
        if (requestCode != FILE_PICKER_REQUEST) return;
        final GeckoSession.PromptDelegate.FilePrompt prompt = pendingFilePrompt;
        final GeckoResult<GeckoSession.PromptDelegate.PromptResponse> result = pendingFileResult;
        if (prompt == null || result == null) return;
        if (resultCode != RESULT_OK || data == null || session == null || clearing) {
            pendingFilePrompt = null;
            pendingFileResult = null;
            result.complete(prompt.dismiss());
            return;
        }
        final ArrayList<Uri> chosen = new ArrayList<>();
        ClipData clip = data.getClipData();
        if (clip != null) {
            for (int i = 0; i < clip.getItemCount(); i++) {
                Uri uri = clip.getItemAt(i).getUri();
                if (uri != null && "content".equals(uri.getScheme())) chosen.add(uri);
                if (prompt.type == GeckoSession.PromptDelegate.FilePrompt.Type.SINGLE) break;
            }
        }
        if (chosen.isEmpty() && data.getData() != null
                && "content".equals(data.getData().getScheme())) {
            chosen.add(data.getData());
        }
        if (chosen.isEmpty() || chosen.size() > MAX_SELECTED_FILES) {
            pendingFilePrompt = null;
            pendingFileResult = null;
            result.complete(prompt.dismiss());
            Toast.makeText(this, "Не удалось выбрать файлы", Toast.LENGTH_SHORT).show();
            return;
        }
        if (BuildConfig.DEBUG) Log.i("MaxFilePicker", "Picker returned " + chosen.size() + " item(s)");
        // Do not block the UI thread while Android document providers stream bytes.
        final File requestCache = new File(new File(getCacheDir(), FILE_CACHE_DIR),
                UUID.randomUUID().toString());
        final TransferScope scope = transfers;
        final boolean cleanPhotos = getSharedPreferences("privacy_options", MODE_PRIVATE).getBoolean("clean_images", true);
        scope.own(requestCache);
        final File privateCacheRoot = getCacheDir();
        MAIN_HANDLER.postDelayed(() -> {
            try { PrivateFiles.deleteWithin(privateCacheRoot, requestCache); }
            catch (IOException ignored) { }
        }, PrivateFiles.MAX_AGE_MS);
        scope.execute(() -> {
            final ArrayList<Uri> localFiles = new ArrayList<>();
            IOException failure = null;
            boolean rawMetadata = false;
            long totalBytes = 0;
            try {
                scope.check();
                if (!requestCache.mkdirs()) throw new IOException("Cannot create private cache");
                for (int i = 0; i < chosen.size(); i++) {
                    File directory = new File(requestCache, "item" + i);
                    if (!directory.mkdir()) throw new IOException("Cannot create private item cache");
                    File local = new File(directory, safeSelectedName(chosen.get(i)));
                    long size = copySelectedDocument(scope, chosen.get(i), local,
                            MAX_TOTAL_COPY_BYTES - totalBytes);
                    totalBytes += size;
                    File upload = cleanPhotos ? ImagePrivacy.clean(local, i, scope) : local;
                    totalBytes += upload.length() - size;
                    if (totalBytes > MAX_TOTAL_COPY_BYTES) throw new IOException("Prepared files exceed total limit");
                    rawMetadata |= upload.equals(local);
                    localFiles.add(Uri.fromFile(upload));
                }
            } catch (IOException | RuntimeException | OutOfMemoryError e) {
                failure = new IOException("Document could not be copied", e);
                deletePrivateFileCopies(requestCache);
            }
            final IOException error = failure;
            final boolean needsMetadataWarning = rawMetadata;
            runOnUiThread(() -> {
                if (scope != transfers || scope.isCancelled()
                        || pendingFilePrompt != prompt || pendingFileResult != result
                        || session == null || clearing || isFinishing() || isDestroyed()) {
                    deletePrivateFileCopies(requestCache);
                    return;
                }
                if (error != null || localFiles.isEmpty()) {
                    pendingFilePrompt = null;
                    pendingFileResult = null;
                    result.complete(prompt.dismiss());
                    Toast.makeText(this, "Не удалось подготовить выбранный файл (до 100 МБ)",
                            Toast.LENGTH_LONG).show();
                    if (BuildConfig.DEBUG) Log.w("MaxFilePicker", "Copy failed");
                    return;
                }
                if (needsMetadataWarning) {
                    pendingUploadDialog = new AlertDialog.Builder(this)
                            .setTitle("Отправить файлы как есть?")
                            .setMessage("Документы, видео, анимация и фото без очистки могут содержать имя автора, геолокацию и другие метаданные. Они будут доступны MAX. Оригиналы не меняются.")
                            .setPositiveButton("Отправить", (dialog, which) -> confirmPreparedFiles(prompt,result,scope,requestCache,localFiles))
                            .setNegativeButton("Отмена", (dialog, which) -> { dismissPendingFilePrompt(); deletePrivateFileCopies(requestCache); })
                            .setOnCancelListener(dialog -> { dismissPendingFilePrompt(); deletePrivateFileCopies(requestCache); })
                            .show();
                } else confirmPreparedFiles(prompt,result,scope,requestCache,localFiles);
            });
        });
    }

    private void confirmPreparedFiles(GeckoSession.PromptDelegate.FilePrompt prompt,
            GeckoResult<GeckoSession.PromptDelegate.PromptResponse> result, TransferScope scope,
            File requestCache, ArrayList<Uri> localFiles) {
        pendingUploadDialog = null;
        if (scope != transfers || scope.isCancelled() || pendingFilePrompt != prompt
                || pendingFileResult != result || session == null || clearing || isFinishing() || isDestroyed()) {
            deletePrivateFileCopies(requestCache); return;
        }
        pendingFilePrompt = null;
        pendingFileResult = null;
                try {
                    // GeckoView 158 expects a readable filesystem path; content://
                    // URIs returned by SAF can silently resolve to null there.
                    GeckoSession.PromptDelegate.PromptResponse response =
                            prompt.type == GeckoSession.PromptDelegate.FilePrompt.Type.MULTIPLE
                            ? prompt.confirm(getApplicationContext(), localFiles.toArray(new Uri[0]))
                            : prompt.confirm(getApplicationContext(), localFiles.get(0));
                    result.complete(response);
                    if (BuildConfig.DEBUG) Log.i("MaxFilePicker", "Gecko file prompt confirmed");
                } catch (RuntimeException e) {
                    deletePrivateFileCopies(requestCache);
                    if (!prompt.isComplete()) result.complete(prompt.dismiss());
                    if (BuildConfig.DEBUG) Log.w("MaxFilePicker", "Gecko rejected file prompt");
                    Toast.makeText(this, "GeckoView не принял выбранный файл",
                            Toast.LENGTH_LONG).show();
                }
    }

    private String safeSelectedName(Uri source) {
        String name = null;
        try (Cursor cursor = getContentResolver().query(source,
                new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) name = cursor.getString(0);
        } catch (RuntimeException ignored) { }
        if (name != null) {
            StringBuilder cleaned = new StringBuilder(name.length());
            for (int i = 0; i < name.length(); i++) {
                char c = name.charAt(i);
                cleaned.append(c == '/' || c == '\\' || c < 0x20 || c == 0x7f ? '_' : c);
            }
            name = cleaned.toString().replace("..", "_").trim();
            if (name.length() > 90) name = name.substring(name.length() - 90);
        }
        if (name == null || name.isEmpty() || ".".equals(name)) {
            String mime = getContentResolver().getType(source);
            String extension = mime == null ? null : MimeTypeMap.getSingleton()
                    .getExtensionFromMimeType(mime);
            if (extension == null || !extension.matches("[a-zA-Z0-9]{1,8}")) extension = "bin";
            name = "attachment." + extension;
        }
        return name;
    }

    private long copySelectedDocument(TransferScope scope, Uri uri, File local, long remaining) throws IOException {
        if (remaining <= 0) throw new IOException("Total size limit");
        long bytes = 0;
        scope.check();
        try (InputStream input = scope.input(getContentResolver().openInputStream(uri));
             OutputStream output = scope.output(new FileOutputStream(local))) {
            if (input == null) throw new IOException("Cannot open selected document");
            byte[] buffer = new byte[32768];
            int n;
            while ((n = input.read(buffer)) != -1) {
                scope.check();
                bytes += n;
                if (bytes > MAX_FILE_COPY_BYTES || bytes > remaining)
                    throw new IOException("Document exceeds size limit");
                output.write(buffer, 0, n);
            }
        }
        return bytes;
    }

    /** Fetches a single user-selected MAX file through Gecko networking, not Android's
     * DownloadManager. Cookies are excluded, redirects are disabled and only the
     * preapproved exact host/URL is accepted. Never logs URL or response contents. */
    @android.annotation.SuppressLint("WrongConstant") // Combine the documented independent Gecko fetch bit flags.
    private void fetchApprovedDownload(String uri) {
        if (runtime == null || !exactHttpsHost(uri, "fd.oneme.ru")) {
            downloadFetchPending = false;
            approvedDownloadAt = 0;
            approvedDownloadUri = null;
            return;
        }
        try {
            final TransferScope scope = transfers;
            GeckoWebExecutor executor = new GeckoWebExecutor(runtime);
            WebRequest request = new WebRequest.Builder(uri).build();
            activeDownloadRequest = executor.fetch(request,
                    GeckoWebExecutor.FETCH_FLAGS_ANONYMOUS
                            | GeckoWebExecutor.FETCH_FLAGS_NO_REDIRECTS);
            activeDownloadRequest.accept(response -> runOnUiThread(() -> {
                        if (scope != transfers || scope.isCancelled()) {
                            if (response != null && response.body != null) try { response.body.close(); } catch (IOException ignored) { }
                            return;
                        }
                        activeDownloadRequest = null;
                        downloadFetchPending = false;
                        if (response == null) {
                            approvedDownloadAt = 0;
                            approvedDownloadUri = null;
                            return;
                        }
                        if (session == null || clearing || isFinishing() || isDestroyed()
                                || approvedDownloadAt == 0
                                || !uri.equals(approvedDownloadUri)
                                || !exactHttpsHost(response.uri, "fd.oneme.ru")) {
                            if (response.body != null) {
                                try { response.body.close(); } catch (IOException ignored) { }
                            }
                            approvedDownloadAt = 0;
                            approvedDownloadUri = null;
                            return;
                        }
                        if (BuildConfig.DEBUG) Log.i("MaxDownloadDiag",
                                "direct fetch returned; statusGroup=" + (response.statusCode / 100));
                        // Existing method enforces HTTPS, 2xx and 100 MB limit,
                        // then shows the user the Android Save As picker.
                        handleExternalDownloadResponse(session, response);
                        if (!downloadInProgress) {
                            approvedDownloadAt = 0;
                            approvedDownloadUri = null;
                            Toast.makeText(this, "Не удалось получить файл MAX",
                                    Toast.LENGTH_SHORT).show();
                        }
                    }), error -> runOnUiThread(() -> {
                        if (scope != transfers || scope.isCancelled()) return;
                        activeDownloadRequest = null;
                        downloadFetchPending = false;
                        approvedDownloadAt = 0;
                        approvedDownloadUri = null;
                        if (BuildConfig.DEBUG) Log.i("MaxDownloadDiag", "direct fetch failed");
                        if (!isFinishing() && !isDestroyed()) {
                            Toast.makeText(this, "Скачивание не удалось",
                                    Toast.LENGTH_SHORT).show();
                        }
                    }));
        } catch (RuntimeException ex) {
            downloadFetchPending = false;
            approvedDownloadAt = 0;
            approvedDownloadUri = null;
            if (BuildConfig.DEBUG) Log.i("MaxDownloadDiag", "direct fetch could not start");
            Toast.makeText(this, "Не удалось начать скачивание", Toast.LENGTH_SHORT).show();
        }
    }

    // A blob URL is resolved inside GeckoView's originating page. Never
    // treat it as an HTTP host or send it to the anonymous network fetcher.
    private static boolean isMaxBlobUri(String uri) {
        if (uri == null || uri.length() > 4096) return false;
        try { return "blob".equalsIgnoreCase(Uri.parse(uri).getScheme()); }
        catch (RuntimeException ignored) { return false; }
    }

    private void handleExternalDownloadResponse(GeckoSession s, WebResponse response) {
        final TransferScope scope = transfers;
        final InputStream body;
        try { body = response.body == null ? null : scope.input(response.body); }
        catch (IOException error) { return; }
        // The native stream must always be closed, including rejected responses.
        if (body == null) {
            if (BuildConfig.DEBUG) Log.i("MaxDownloadDiag", "external response without body");
            if (s == downloadPopupSession) closeDownloadPopupSession();
            return;
        }
        long elapsed = SystemClock.elapsedRealtime() - approvedDownloadAt;
        // For a blob stream there is no HTTP status or network TLS connection.
        // Require exact match with the single user-approved blob URI and
        // the original session; do not allow arbitrary blob/file responses.
        final boolean approvedBlob = s == session
                && isMaxBlobUri(response.uri)
                && approvedDownloadUri != null
                && approvedDownloadUri.equals(response.uri)
                && approvedDownloadTarget == GeckoSession.NavigationDelegate.TARGET_WINDOW_CURRENT;
        final boolean approvedNetworkFile = exactHttpsHost(response.uri, "fd.oneme.ru")
                && response.isSecure && response.statusCode >= 200 && response.statusCode < 300;
        if ((s != session && s != downloadPopupSession)
                || clearing || isFinishing() || isDestroyed()
                || downloadInProgress || approvedDownloadAt == 0
                || elapsed < 0 || elapsed > 120000
                || !(approvedBlob || approvedNetworkFile)) {
            try { body.close(); } catch (IOException ignored) { }
            if (BuildConfig.DEBUG) Log.i("MaxDownloadDiag", "external response rejected by policy");
            if (s == downloadPopupSession) closeDownloadPopupSession();
            return;
        }
        if (approvedBlob && BuildConfig.DEBUG)
            Log.i("MaxDownloadDiag", "approved blob response; stream received");
        approvedDownloadAt = 0;
        approvedDownloadUri = null;
        downloadInProgress = true;
        // Read only response metadata: filename never goes to logs or analytics.
        final String declaredMime = downloadMime(response);
        final String originalName = downloadHeaderFileName(response);
        final File target = new File(new File(getCacheDir(), DOWNLOAD_CACHE_DIR),
                UUID.randomUUID().toString() + ".tmp");
        scope.own(target);
        // No URL, auth header, filename, or file contents are sent to logcat.
        if (BuildConfig.DEBUG) Log.i("MaxDownloadDiag", "approved download response; copying in private cache");
        try {
            scope.execute(() -> {
                boolean success = false;
                try (InputStream input = body) {
                    scope.check();
                    if (!target.getParentFile().isDirectory()
                            && !target.getParentFile().mkdirs())
                        throw new IOException("Cannot create download cache");
                    try (OutputStream output = scope.output(new FileOutputStream(target))) {
                        byte[] buffer = new byte[32768];
                        long count = 0;
                        int n;
                        while ((n = input.read(buffer)) != -1) {
                            scope.check();
                            count += n;
                            if (count > DOWNLOAD_LIMIT_BYTES) throw new IOException("File exceeds download limit");
                            output.write(buffer, 0, n);
                        }
                    }
                    success = true;
                } catch (IOException | RuntimeException ignored) { }
                final boolean ok = success;
                // Determine real format from the private cached copy, not its URL.
                // No file contents or filenames are logged or uploaded.
                final String mime = ok ? downloadMimeFromFile(target, declaredMime) : declaredMime;
                final String filename = safeDownloadFileName(originalName, mime);
                runOnUiThread(() -> {
                    if (scope != transfers || scope.isCancelled()) { target.delete(); return; }
                    if (s == downloadPopupSession) closeDownloadPopupSession();
                    if (!ok || isFinishing() || isDestroyed() || clearing) {
                        target.delete();
                        downloadInProgress = false;
                        Toast.makeText(this, "Не удалось получить файл (лимит 100 МБ)",
                                Toast.LENGTH_LONG).show();
                        if (BuildConfig.DEBUG) Log.i("MaxDownloadDiag", "download cache copy failed");
                        return;
                    }
                    pendingSaveFile = target;
                    Intent save = new Intent(Intent.ACTION_CREATE_DOCUMENT);
                    save.addCategory(Intent.CATEGORY_OPENABLE);
                    save.setType(mime);
                    save.putExtra(Intent.EXTRA_TITLE, filename);
                    try {
                        startActivityForResult(save, DOWNLOAD_SAVE_REQUEST);
                        if (BuildConfig.DEBUG) Log.i("MaxDownloadDiag", "Android save picker opened");
                    } catch (ActivityNotFoundException | SecurityException e) {
                        target.delete();
                        pendingSaveFile = null;
                        downloadInProgress = false;
                        Toast.makeText(this, "Нет системного окна сохранения",
                                Toast.LENGTH_LONG).show();
                    }
                });
            });
        } catch (java.util.concurrent.RejectedExecutionException e) {
            try { body.close(); } catch (IOException ignored) { }
            target.delete();
            downloadInProgress = false;
        }
    }

    private static String downloadMime(WebResponse response) {
        String value = null;
        if (response.headers == null) return "application/octet-stream";
        for (java.util.Map.Entry<String, String> header : response.headers.entrySet()) {
            if ("content-type".equalsIgnoreCase(header.getKey())) {
                value = header.getValue();
                break;
            }
        }
        if (value == null) return "application/octet-stream";
        int semi = value.indexOf(';');
        if (semi >= 0) value = value.substring(0, semi);
        value = value.trim().toLowerCase(java.util.Locale.ROOT);
        // Do not pass arbitrary server-controlled strings to the Android picker.
        return value.matches("[a-z0-9.+-]{1,50}/[a-z0-9.+-]{1,80}")
                ? value : "application/octet-stream";
    }

    /** Extract a suggested name from Content-Disposition without reading chats or URLs. */
    private static String downloadHeaderFileName(WebResponse response) {
        if (response.headers == null) return null;
        String disposition = null;
        for (java.util.Map.Entry<String, String> header : response.headers.entrySet()) {
            if ("content-disposition".equalsIgnoreCase(header.getKey())) {
                disposition = header.getValue();
                break;
            }
        }
        if (disposition == null) return null;
        String ordinary = null;
        for (String item : disposition.split(";")) {
            int equal = item.indexOf('=');
            if (equal < 0) continue;
            String key = item.substring(0, equal).trim().toLowerCase(java.util.Locale.ROOT);
            String value = item.substring(equal + 1).trim();
            if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
                value = value.substring(1, value.length() - 1);
            }
            if ("filename*".equals(key)) {
                int separator = value.indexOf("''");
                if (separator > 0 && "utf-8".equalsIgnoreCase(value.substring(0, separator))) {
                    try {
                        // In RFC 5987, '+' is literal, unlike form data.
                        return java.net.URLDecoder.decode(
                                value.substring(separator + 2).replace("+", "%2B"), "UTF-8");
                    } catch (IllegalArgumentException | java.io.UnsupportedEncodingException ignored) { }
                }
            } else if ("filename".equals(key)) {
                ordinary = value;
            }
        }
        return ordinary;
    }

    /** Do not mistake application/octet-stream PDFs and pictures for .bin files. */
    private static String downloadMimeFromFile(File file, String declared) {
        if (!"application/octet-stream".equals(declared)) return declared;
        byte[] magic = new byte[16];
        int n;
        try (InputStream input = new java.io.FileInputStream(file)) {
            n = input.read(magic);
        } catch (IOException ignored) { return declared; }
        if (n >= 5 && magic[0] == '%' && magic[1] == 'P' && magic[2] == 'D'
                && magic[3] == 'F' && magic[4] == '-') return "application/pdf";
        if (n >= 4 && (magic[0] & 255) == 0x89 && magic[1] == 'P'
                && magic[2] == 'N' && magic[3] == 'G') return "image/png";
        if (n >= 3 && (magic[0] & 255) == 0xFF && (magic[1] & 255) == 0xD8
                && (magic[2] & 255) == 0xFF) return "image/jpeg";
        if (n >= 12 && magic[0] == 'R' && magic[1] == 'I' && magic[2] == 'F'
                && magic[3] == 'F' && magic[8] == 'W' && magic[9] == 'E'
                && magic[10] == 'B' && magic[11] == 'P') return "image/webp";
        return declared;
    }

    /** Prevent path injection, confusing Unicode control chars and excessively long names. */
    private static String safeDownloadFileName(String proposed, String mime) {
        if (proposed == null || proposed.trim().isEmpty()) return defaultDownloadName(mime);
        int slash = Math.max(proposed.lastIndexOf('/'), proposed.lastIndexOf('\\'));
        String name = proposed.substring(slash + 1)
                .replaceAll("[\\p{Cc}\\p{Cf}/\\\\:*?\"<>|]", "_").trim();
        while (name.endsWith(".") || name.endsWith(" ")) name = name.substring(0, name.length() - 1);
        if (name.isEmpty() || name.equals(".") || name.equals("..")) return defaultDownloadName(mime);
        if (name.startsWith(".")) name = "MAX-" + name.substring(1);
        String expectedSuffix = null;
        switch (mime) {
            case "application/pdf": expectedSuffix = ".pdf"; break;
            case "image/jpeg": expectedSuffix = ".jpg"; break;
            case "image/png": expectedSuffix = ".png"; break;
            case "image/webp": expectedSuffix = ".webp"; break;
            case "text/plain": expectedSuffix = ".txt"; break;
            default: break;
        }
        String lower = name.toLowerCase(java.util.Locale.ROOT);
        if (expectedSuffix != null && !lower.endsWith(expectedSuffix)
                && !("image/jpeg".equals(mime) && lower.endsWith(".jpeg"))) {
            int dot = name.lastIndexOf('.');
            if (dot > 0 && name.length() - dot <= 12) name = name.substring(0, dot);
            name += expectedSuffix;
        }
        if (name.length() > 120) {
            int dot = name.lastIndexOf('.');
            String ext = dot > 0 && name.length() - dot <= 12 ? name.substring(dot) : "";
            name = name.substring(0, 120 - ext.length()) + ext;
        }
        return name.isEmpty() ? defaultDownloadName(mime) : name;
    }

    private static String defaultDownloadName(String mime) {
        switch (mime) {
            case "application/pdf": return "MAX-document.pdf";
            case "image/jpeg": return "MAX-image.jpg";
            case "image/png": return "MAX-image.png";
            case "image/webp": return "MAX-image.webp";
            case "text/plain": return "MAX-document.txt";
            default: return "MAX-download.bin";
        }
    }

    private void saveSelectedDownload(int resultCode, Intent data) {
        final TransferScope scope = transfers;
        File source = pendingSaveFile;
        pendingSaveFile = null;
        if (source == null) {
            downloadInProgress = false;
            return;
        }
        Uri uri = data == null ? null : data.getData();
        if (resultCode != RESULT_OK || uri == null || !"content".equals(uri.getScheme())) {
            source.delete();
            downloadInProgress = false;
            if (BuildConfig.DEBUG) Log.i("MaxDownloadDiag", "save cancelled");
            return;
        }
        scope.execute(() -> {
            boolean ok = false;
            try (InputStream input = scope.input(new java.io.FileInputStream(source));
                    OutputStream output = scope.output(getContentResolver().openOutputStream(uri, "w"))) {
                if (output == null) throw new IOException("No output stream");
                byte[] buffer = new byte[32768];
                int n;
                while ((n = input.read(buffer)) != -1) { scope.check(); output.write(buffer, 0, n); }
                ok = true;
            } catch (IOException | RuntimeException ignored) { }
            finally { source.delete(); }
            final boolean result = ok;
            runOnUiThread(() -> {
                if (scope != transfers || scope.isCancelled()) return;
                downloadInProgress = false;
                if (BuildConfig.DEBUG) Log.i("MaxDownloadDiag", result ? "file saved" : "file save failed");
                Toast.makeText(this, result ? "Файл сохранён" : "Не удалось сохранить файл",
                        Toast.LENGTH_SHORT).show();
            });
        });
    }

    private void closeDownloadPopupSession() {
        GeckoSession popup = downloadPopupSession;
        downloadPopupSession = null;
        approvedDownloadUri = null;
        approvedDownloadAt = 0;
        if (popup != null && popup.isOpen()) popup.close();
    }

    private void clearOldDownloadCache() {
        File folder = new File(getCacheDir(), DOWNLOAD_CACHE_DIR);
        File[] files = folder.listFiles();
        if (files != null) for (File f : files) if (f.isFile()) f.delete();
    }

    private void clearPreviousFileCopies() {
        File parent = new File(getCacheDir(), FILE_CACHE_DIR);
        File[] entries = parent.listFiles();
        if (entries != null) for (File entry : entries) deletePrivateFileCopies(entry);
    }

    private void deletePrivateFileCopies(File path) {
        try { PrivateFiles.deleteWithin(getCacheDir(), path); }
        catch (IOException ignored) { }
    }

    private void dismissPendingFilePrompt() {
        if (pendingUploadDialog != null) { pendingUploadDialog.dismiss(); pendingUploadDialog = null; }
        GeckoSession.PromptDelegate.FilePrompt prompt = pendingFilePrompt;
        GeckoResult<GeckoSession.PromptDelegate.PromptResponse> result = pendingFileResult;
        pendingFilePrompt = null;
        pendingFileResult = null;
        if (prompt != null && result != null) result.complete(prompt.dismiss());
    }

    private void handleBack() {
        if (!unlocked) { finish(); return; }
        if (canGoBack && session != null) session.goBack();
        else finish();
    }
    @Override public boolean dispatchKeyEvent(android.view.KeyEvent event) {
        if (Build.VERSION.SDK_INT < 33 && event.getKeyCode() == android.view.KeyEvent.KEYCODE_BACK) {
            if (event.getAction() == android.view.KeyEvent.ACTION_UP) handleBack();
            return true;
        }
        return super.dispatchKeyEvent(event);
    }
    @Override protected void onPause() {
        resumed = false;
        if (session != null) {
            session.setFocused(false);
        }
        super.onPause();
    }
    @Override protected void onStart() {
        super.onStart();
        started = true;
        if (session != null) session.setActive(unlocked || probeActive);
    }
    @Override protected void onStop() {
        if (!credentialInFlight) backgroundAt = SystemClock.elapsedRealtime();
        started = false;
        if (session != null) session.setActive(false);
        super.onStop();
    }
    @Override protected void onResume() {
        super.onResume();
        resumed = true;
        if (!BuildConfig.INTERNAL_AUDIT && screenLockEnabled() && unlocked
                && backgroundAt > 0 && SystemClock.elapsedRealtime() - backgroundAt >= 60000) {
            unlocked = false; unlockAttempted = false;
        }
        renderLock();
        if (!unlocked && !unlockAttempted) requestUnlock();
        if (session != null) {
            session.setFocused(unlocked && hasWindowFocus());
        }
    }
    @Override public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (session != null) session.setFocused(unlocked && resumed && hasFocus);
    }

    private boolean screenLockEnabled() {
        KeyguardManager keyguard = (KeyguardManager)getSystemService(KEYGUARD_SERVICE);
        return keyguard != null && keyguard.isDeviceSecure()
                && getSharedPreferences("privacy_options",MODE_PRIVATE).getBoolean("app_lock",true);
    }

    private void renderLock() {
        if (lockCover == null || appContent == null) return;
        lockCover.setVisibility(unlocked ? View.GONE : View.VISIBLE);
        appContent.setVisibility(unlocked ? View.VISIBLE : View.INVISIBLE);
        appContent.setImportantForAccessibility(unlocked ? View.IMPORTANT_FOR_ACCESSIBILITY_AUTO
                : View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        if (!unlocked) {
            android.view.inputmethod.InputMethodManager input = (android.view.inputmethod.InputMethodManager)getSystemService(INPUT_METHOD_SERVICE);
            if (input != null) input.hideSoftInputFromWindow(appContent.getWindowToken(),0);
        }
        if (session != null) {
            session.setActive(started && (unlocked || probeActive));
            session.setFocused(unlocked && resumed && hasWindowFocus());
        }
    }

    @SuppressWarnings("deprecation") private void requestUnlock() {
        if (credentialInFlight || unlocked || isFinishing() || isDestroyed()) return;
        unlockAttempted = true;
        KeyguardManager keyguard = (KeyguardManager)getSystemService(KEYGUARD_SERVICE);
        Intent confirm = keyguard == null ? null : keyguard.createConfirmDeviceCredentialIntent("MAX Private Gecko", "Разблокировка приложения средствами Android");
        if (confirm == null) { Toast.makeText(this,"Системная разблокировка недоступна",Toast.LENGTH_LONG).show(); return; }
        credentialInFlight = true;
        try { startActivityForResult(confirm,UNLOCK_REQUEST); }
        catch (ActivityNotFoundException error) { credentialInFlight = false; }
    }
    @Override protected void onDestroy() {
        MAIN_HANDLER.removeCallbacks(probeCompletion);
        cancelNetworkTransfers();
        if (pendingSaveFile != null) {
            pendingSaveFile.delete();
            pendingSaveFile = null;
        }
        TransferScope old = transfers;
        old.markCancelled();
        File cacheRoot = getCacheDir();
        new Thread(() -> {
            old.cancel();
            try { if (old.awaitStopped(10)) old.purgeOwned(cacheRoot); }
            catch (IOException | InterruptedException ignored) { }
        }, "PrivateTransferCleanup").start();
        // The runtime delegate uses only the application context, so do not
        // replace it while Gecko may still have a notification callback queued.
        detachAndClose();
        super.onDestroy();
    }
}
