package com.hanview.translate;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.DownloadManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.Settings;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class AppUpdateManager {
    private static final String LATEST_JSON =
            "https://github.com/cptu527/hanview-android/releases/download/latest/latest.json";

    private final Activity activity;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final DownloadManager downloadManager;

    private long pendingDownloadId = -1L;
    private boolean receiverRegistered = false;

    private final BroadcastReceiver downloadReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (!DownloadManager.ACTION_DOWNLOAD_COMPLETE.equals(intent.getAction())) {
                return;
            }

            long id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L);
            if (id != pendingDownloadId) {
                return;
            }

            openInstaller(id);
        }
    };

    public AppUpdateManager(Activity activity) {
        this.activity = activity;
        this.downloadManager =
                (DownloadManager) activity.getSystemService(Context.DOWNLOAD_SERVICE);
    }

    public void checkForUpdate(boolean manual) {
        executor.execute(() -> {
            try {
                URL url = new URL(LATEST_JSON + "?t=" + System.currentTimeMillis());
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setConnectTimeout(8000);
                conn.setReadTimeout(8000);
                conn.setRequestProperty("Accept", "application/json");
                conn.setUseCaches(false);

                int code = conn.getResponseCode();
                if (code < 200 || code >= 300) {
                    throw new IllegalStateException("HTTP " + code);
                }

                StringBuilder sb = new StringBuilder();
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(
                                conn.getInputStream(),
                                StandardCharsets.UTF_8
                        ))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        sb.append(line);
                    }
                }

                JSONObject json = new JSONObject(sb.toString());
                int versionCode = json.getInt("versionCode");
                String versionName = json.optString("versionName", "");
                String apkUrl = json.getString("url");
                String notes = json.optString("notes", "");

                activity.runOnUiThread(() -> {
                    if (versionCode > BuildConfig.VERSION_CODE) {
                        showUpdateDialog(versionName, apkUrl, notes);
                    } else if (manual) {
                        Toast.makeText(
                                activity,
                                "이미 최신 버전이에요.",
                                Toast.LENGTH_SHORT
                        ).show();
                    }
                });
            } catch (Exception e) {
                if (manual) {
                    activity.runOnUiThread(() -> Toast.makeText(
                            activity,
                            "업데이트 정보를 확인하지 못했어요.",
                            Toast.LENGTH_LONG
                    ).show());
                }
            }
        });
    }

    private void showUpdateDialog(
            String versionName,
            String apkUrl,
            String notes
    ) {
        String message = "새 버전 " + versionName + "이 있어요.";
        if (notes != null && !notes.trim().isEmpty()) {
            message += "\n\n" + notes.trim();
        }

        new AlertDialog.Builder(activity)
                .setTitle("뷰냥 업데이트")
                .setMessage(message)
                .setNegativeButton("나중에", null)
                .setPositiveButton("업데이트", (dialog, which) -> {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                            && !activity.getPackageManager()
                            .canRequestPackageInstalls()) {
                        Intent settings = new Intent(
                                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                                Uri.parse("package:" + activity.getPackageName())
                        );
                        activity.startActivity(settings);

                        Toast.makeText(
                                activity,
                                "‘이 출처 허용’을 켠 뒤 업데이트 확인을 다시 눌러주세요.",
                                Toast.LENGTH_LONG
                        ).show();
                        return;
                    }

                    download(apkUrl);
                })
                .show();
    }

    private void download(String apkUrl) {
        try {
            registerReceiverIfNeeded();

            DownloadManager.Request request =
                    new DownloadManager.Request(Uri.parse(apkUrl));

            request.setTitle("뷰냥 업데이트");
            request.setDescription("새 버전을 내려받는 중");
            request.setNotificationVisibility(
                    DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED
            );
            request.setMimeType("application/vnd.android.package-archive");
            request.setDestinationInExternalFilesDir(
                    activity,
                    Environment.DIRECTORY_DOWNLOADS,
                    "viewnyang-update.apk"
            );

            pendingDownloadId = downloadManager.enqueue(request);

            activity.getSharedPreferences(
                    "viewnyang_update",
                    Context.MODE_PRIVATE
            ).edit().putLong("pending_download_id", pendingDownloadId).apply();

            Toast.makeText(
                    activity,
                    "업데이트를 내려받고 있어요.",
                    Toast.LENGTH_SHORT
            ).show();
        } catch (Exception e) {
            Toast.makeText(
                    activity,
                    "업데이트 다운로드를 시작하지 못했어요.",
                    Toast.LENGTH_LONG
            ).show();
        }
    }

    public void tryInstallPendingDownload() {
        long saved = activity.getSharedPreferences(
                "viewnyang_update",
                Context.MODE_PRIVATE
        ).getLong("pending_download_id", -1L);

        if (saved <= 0) return;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                && !activity.getPackageManager().canRequestPackageInstalls()) {
            return;
        }

        Uri uri = downloadManager.getUriForDownloadedFile(saved);
        if (uri != null) {
            pendingDownloadId = saved;
            openInstaller(saved);
        }
    }

    private void openInstaller(long downloadId) {
        Uri uri = downloadManager.getUriForDownloadedFile(downloadId);
        if (uri == null) return;

        Intent install = new Intent(Intent.ACTION_VIEW);
        install.setDataAndType(
                uri,
                "application/vnd.android.package-archive"
        );
        install.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        install.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);

        activity.startActivity(install);

        activity.getSharedPreferences(
                "viewnyang_update",
                Context.MODE_PRIVATE
        ).edit().remove("pending_download_id").apply();
    }

    private void registerReceiverIfNeeded() {
        if (receiverRegistered) return;

        IntentFilter filter =
                new IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE);

        if (Build.VERSION.SDK_INT >= 33) {
            activity.registerReceiver(
                    downloadReceiver,
                    filter,
                    Context.RECEIVER_NOT_EXPORTED
            );
        } else {
            activity.registerReceiver(downloadReceiver, filter);
        }

        receiverRegistered = true;
    }
}
