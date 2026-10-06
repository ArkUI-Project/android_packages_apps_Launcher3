/* Modified by the ArkUI Project in 2026 to handle cancelled settings authentication.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.launcher3.lineage;

import android.app.KeyguardManager;
import android.app.Activity;
import android.content.Intent;
import android.content.ActivityNotFoundException;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.Context;
import android.hardware.biometrics.BiometricManager.Authenticators;
import android.hardware.biometrics.BiometricPrompt;
import android.os.CancellationSignal;
import android.os.Handler;
import android.os.Looper;
import android.os.Parcel;
import android.os.Binder;
import android.os.Bundle;
import android.os.ResultReceiver;
import android.os.SystemClock;
import android.widget.Toast;

import com.android.launcher3.R;

import java.util.concurrent.atomic.AtomicBoolean;

public class LineageUtils {

    /** Hidden-app management uses the independent privacy credential in trusted Settings UI. */
    public static void showPrivacyPassword(Activity activity, Runnable success, Runnable cancel) {
        Handler handler = new Handler(Looper.getMainLooper());
        AtomicBoolean delivered = new AtomicBoolean();
        long expires = SystemClock.elapsedRealtime() + 5 * 60_000L;
        final int settingsUid;
        try {
            ApplicationInfo settings = activity.getPackageManager()
                    .getApplicationInfo("com.android.settings", 0);
            if ((settings.flags & ApplicationInfo.FLAG_SYSTEM) == 0
                    || activity.getPackageManager().checkSignatures("android", "com.android.settings")
                            != PackageManager.SIGNATURE_MATCH) {
                cancel.run();
                return;
            }
            settingsUid = settings.uid;
        } catch (PackageManager.NameNotFoundException error) {
            cancel.run();
            return;
        }
        // With no Handler, onReceiveResult runs on the Binder thread while the sender identity
        // is still available. Ordinary apps cannot forge Settings' authentication callback.
        ResultReceiver receiver = new ResultReceiver(null) {
            @Override protected void onReceiveResult(int resultCode, Bundle resultData) {
                if (Binder.getCallingUid() != settingsUid
                        || !delivered.compareAndSet(false, true)) return;
                boolean verified = resultCode == Activity.RESULT_OK
                        && SystemClock.elapsedRealtime() < expires;
                handler.post(() -> {
                    if (activity.isFinishing() || activity.isDestroyed()) return;
                    if (verified) success.run();
                    else cancel.run();
                });
            }
        };
        // Only the framework Parcelable class can cross into Settings' class loader. The
        // local subclass remains behind its Binder, where the callback sender is validated.
        final ResultReceiver callback;
        Parcel parcel = Parcel.obtain();
        try {
            receiver.writeToParcel(parcel, 0);
            parcel.setDataPosition(0);
            callback = ResultReceiver.CREATOR.createFromParcel(parcel);
        } finally {
            parcel.recycle();
        }
        try {
            activity.startActivity(new Intent("org.arkui.settings.CONFIRM_PRIVACY_PASSWORD")
                    .setClassName("com.android.settings",
                            "com.android.settings.arkui.privacy.PrivacyPasswordActivity")
                    .putExtra("org.arkui.privacy.CALLBACK", callback));
        } catch (ActivityNotFoundException error) {
            Toast.makeText(activity, R.string.trust_apps_privacy_unavailable, Toast.LENGTH_LONG).show();
            cancel.run();
        }
    }

    /**
     * Shows authentication screen to confirm credentials (pin, pattern or password) for the current
     * user of the device.
     *
     * @param context The {@code Context} used to get {@code KeyguardManager} service
     * @param title the {@code String} which will be shown as the pompt title
     * @param successRunnable The {@code Runnable} which will be executed if the user does not setup
     *                        device security or if lock screen is unlocked
     */
    public static void showLockScreen(Context context, String title, Runnable successRunnable) {
        showLockScreen(context, title, successRunnable, () -> { });
    }

    public static void showLockScreen(Context context, String title, Runnable successRunnable,
            Runnable cancelRunnable) {
        if (hasSecureKeyguard(context)) {
            final BiometricPrompt.AuthenticationCallback authenticationCallback =
                    new BiometricPrompt.AuthenticationCallback() {
                        @Override
                        public void onAuthenticationSucceeded(
                                    BiometricPrompt.AuthenticationResult result) {
                            successRunnable.run();
                        }

                        @Override
                        public void onAuthenticationError(int errorCode, CharSequence errString) {
                            cancelRunnable.run();
                        }
            };

            final BiometricPrompt bp = new BiometricPrompt.Builder(context)
                    .setTitle(title)
                    .setAllowedAuthenticators(Authenticators.BIOMETRIC_STRONG |
                                              Authenticators.DEVICE_CREDENTIAL)
                    .build();

            final Handler handler = new Handler(Looper.getMainLooper());
            bp.authenticate(new CancellationSignal(),
                    runnable -> handler.post(runnable),
                    authenticationCallback);
        } else {
            // Notify the user a secure keyguard is required for protected apps,
            // but allow to set hidden apps
            Toast.makeText(context, R.string.trust_apps_no_lock_error, Toast.LENGTH_LONG)
                .show();
            successRunnable.run();
        }
    }

    public static boolean hasSecureKeyguard(Context context) {
        final KeyguardManager keyguardManager = context.getSystemService(KeyguardManager.class);
        return keyguardManager != null && keyguardManager.isKeyguardSecure();
    }

}
