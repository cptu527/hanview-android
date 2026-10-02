package com.hanview.translate;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.DownloadManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.Settings;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.File;\nimport java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class AppUpdateManager {
    private static final String[] LATEST_URLS = {
            "https://raw.githubusercontent.com/cptu527/hanview-android/main/latest.json",
            "https://github.com/cptu527/hanview-android/releases/download/latest/latest.json"
    };

    private static final String PREFS = "viewnyang_update";
    private static final String KEY_DOWNLOAD_ID = "download_id";
    private static final String KEY_APK_URL = "apk_url";
    private static final String KEY_SHA256 = "sha256";
    private static final String KEY_VERSION = "version";

    private final Activity activity;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final DownloadManager downloadManager;
    private final SharedPreferences prefs;
    private boolean receiverRegistered = false;

    private final BroadcastReceiver downloadReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (!DownloadManager.ACTION_DOWNLOAD_COMPLETE.equals(intent.getAction())) return;

            long id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L);
            long expected = prefs.getLong(KEY_DOWNLOAD_ID, -1L);

            if (id > 0 && id == expected) verifyAndInstall(id);
        }
    };

    public AppUpdateManager(Activity activity) {
        this.activity = activity;
        this.downloadManager =
                (DownloadManager) activity.getSystemService(Context.DOWNLOAD_SERVICE);
        this.prefs = activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public void checkForUpdate(boolean manual) {
        if (manual) {
            Toast.makeText(activity, "업데이트를 확인하고 있어요.", Toast.LENGTH_SHORT).show();
        }

        executor.execute(() -> {
            Exception lastError = null;

            for (String baseUrl : LATEST_URLS) {
                HttpURLConnection connection = null;

                try {
                    URL url = new URL(baseUrl + "?t=" + System.currentTimeMillis());
                    connection = (HttpURLConnection) url.openConnection();
                    connection.setConnectTimeout(10000);
                    connection.setReadTimeout(10000);
                    connection.setUseCaches(false);
                    connection.setInstanceFollowRedirects(true);
                    connection.setRequestProperty("Accept", "application/json");
                    connection.setRequestProperty("User-Agent", "ViewNyang-Android");

                    int code = connection.getResponseCode();
                    if (code < 200 || code >= 300) {
                        throw new IllegalStateException("HTTP " + code);
                    }

                    byte[] body = connection.getInputStream().readAllBytes();
                    JSONObject json = new JSONObject(
                            new String(body, StandardCharsets.UTF_8)
                    );

                    long latestCode = json.getLong("versionCode");
                    String versionName = json.getString("versionName");
                    String apkUrl = json.getString("apkUrl");
                    String sha256 = json.optString("sha256", "");
                    String notes = json.optString("notes", "");
                    long currentCode = currentVersionCode();

                    activity.runOnUiThread(() -> {
                        if (latestCode > currentCode) {
                            showUpdateDialog(versionName, apkUrl, sha256, notes);
                        } else if (manual) {
                            Toast.makeText(
                                    activity,
                                    "이미 최신 버전이에요.",
                                    Toast.LENGTH_SHORT
                            ).show();
                        }
                    });
                    return;
                } catch (Exception e) {
                    lastError = e;
                } finally {
                    if (connection != null) {
                        connection.disconnect();
                    }
                }
            }

            if (manual) {
                String reason = lastError == null
                        ? "알 수 없는 오류"
                        : lastError.getClass().getSimpleName();

                activity.runOnUiThread(() ->
                        new AlertDialog.Builder(activity)
                                .setTitle("업데이트 확인 실패")
                                .setMessage("업데이트 서버에 연결하지 못했어요.\n\n오류: " + reason)
                                .setPositiveButton("확인", null)
                                .show()
                );
            }
        });
    }

    private long currentVersionCode() throws Exception {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            return activity.getPackageManager()
                    .getPackageInfo(activity.getPackageName(), 0)
                    .getLongVersionCode();
        }

        @SuppressWarnings("deprecation")
        int versionCode = activity.getPackageManager()
                .getPackageInfo(activity.getPackageName(), 0)
                .versionCode;
        return versionCode;
    }

    private void showUpdateDialog(
            String versionName,
            String apkUrl,
            String sha256,
            String notes
    ) {
        String message = "새 버전 " + versionName + "이 있어요.";
        if (notes != null && !notes.trim().isEmpty()) message += "\n\n" + notes.trim();

        new AlertDialog.Builder(activity)
                .setTitle("뷰냥 업데이트")
                .setMessage(message)
                .setNegativeButton("나중에", null)
                .setPositiveButton(
                        "업데이트",
                        (dialog, which) -> beginUpdate(apkUrl, sha256, versionName)
                )
                .show();
    }

    private void beginUpdate(String apkUrl, String sha256, String versionName) {
        prefs.edit()
                .putString(KEY_APK_URL, apkUrl)
                .putString(KEY_SHA256, sha256 == null ? "" : sha256)
                .putString(KEY_VERSION, versionName == null ? "" : versionName)
                .apply();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                && !activity.getPackageManager().canRequestPackageInstalls()) {

            Intent settings = new Intent(
                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:" + activity.getPackageName())
            );
            activity.startActivity(settings);

            Toast.makeText(
                    activity,
                    "‘이 출처 허용’을 켜고 돌아오면 업데이트를 계속할게요.",
                    Toast.LENGTH_LONG
            ).show();
            return;
        }

        downloadPendingUpdate();
    }

    public void resumePendingUpdateFlow() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                && !activity.getPackageManager().canRequestPackageInstalls()) return;

        long downloadId = prefs.getLong(KEY_DOWNLOAD_ID, -1L);
        if (downloadId > 0) {
            verifyCompletedDownload(downloadId);
            return;
        }

        String apkUrl = prefs.getString(KEY_APK_URL, "");
        if (apkUrl != null && !apkUrl.trim().isEmpty()) downloadPendingUpdate();
    }

    private void downloadPendingUpdate() {
        String apkUrl = prefs.getString(KEY_APK_URL, "");
        String versionName = prefs.getString(KEY_VERSION, "latest");

        if (apkUrl == null || apkUrl.trim().isEmpty()) return;

        try {
            registerReceiverIfNeeded();

            String safeVersion = versionName.replaceAll("[^0-9A-Za-z._-]", "_");

            File dir = activity.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
            if (dir != null) {
                File oldFile = new File(
                        dir,
                        "viewnyang-update-" + safeVersion + ".apk"
                );
                if (oldFile.exists()) {
                    oldFile.delete();
                }
            }

            DownloadManager.Request request =
                    new DownloadManager.Request(Uri.parse(apkUrl));

            request.setTitle("뷰냥 " + versionName + " 업데이트");
            request.setDescription("새 버전을 내려받는 중");
            request.setNotificationVisibility(
                    DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED
            );
            request.setMimeType("application/vnd.android.package-archive");
            request.setAllowedOverMetered(true);
            request.setAllowedOverRoaming(true);
            request.setDestinationInExternalFilesDir(
                    activity,
                    Environment.DIRECTORY_DOWNLOADS,
                    "viewnyang-update-" + safeVersion + ".apk"
            );

            long id = downloadManager.enqueue(request);
            prefs.edit().putLong(KEY_DOWNLOAD_ID, id).apply();

            Toast.makeText(activity, "업데이트를 내려받고 있어요.", Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            Toast.makeText(
                    activity,
                    "업데이트 다운로드를 시작하지 못했어요.",
                    Toast.LENGTH_LONG
            ).show();
        }
    }

    private void verifyCompletedDownload(long id) {
        DownloadManager.Query query = new DownloadManager.Query().setFilterById(id);

        try (Cursor cursor = downloadManager.query(query)) {
            if (cursor == null || !cursor.moveToFirst()) return;

            int index = cursor.getColumnIndex(DownloadManager.COLUMN_STATUS);
            if (index < 0) return;

            int status = cursor.getInt(index);
            if (status == DownloadManager.STATUS_SUCCESSFUL) {
                verifyAndInstall(id);
            } else if (status == DownloadManager.STATUS_FAILED) {
                int reasonIndex =
                        cursor.getColumnIndex(DownloadManager.COLUMN_REASON);
                int reason = reasonIndex >= 0 ? cursor.getInt(reasonIndex) : -1;
                clearPending();
                activity.runOnUiThread(() ->
                        new AlertDialog.Builder(activity)
                                .setTitle("업데이트 다운로드 실패")
                                .setMessage("다운로드 오류 코드: " + reason)
                                .setPositiveButton("확인", null)
                                .show()
                );
            }
        }
    }

    private void verifyAndInstall(long id) {
        Uri uri = downloadManager.getUriForDownloadedFile(id);

        if (uri == null) {
            clearPending();
            return;
        }

        String expected = prefs.getString(KEY_SHA256, "");

        executor.execute(() -> {
            try {
                if (expected != null && !expected.trim().isEmpty()) {
                    String actual = sha256(uri);

                    if (!expected.trim().equalsIgnoreCase(actual)) {
                        clearPending();
                        activity.runOnUiThread(() ->
                                Toast.makeText(
                                        activity,
                                        "업데이트 파일 검증에 실패했어요.",
                                        Toast.LENGTH_LONG
                                ).show()
                        );
                        return;
                    }
                }

                activity.runOnUiThread(() -> openInstaller(uri));
            } catch (Exception e) {
                activity.runOnUiThread(() ->
                        Toast.makeText(
                                activity,
                                "업데이트 파일을 확인하지 못했어요.",
                                Toast.LENGTH_LONG
                        ).show()
                );
            }
        });
    }

    private String sha256(Uri uri) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");

        try (InputStream input =
                     activity.getContentResolver().openInputStream(uri)) {

            if (input == null) throw new IllegalStateException("missing file");

            byte[] buffer = new byte[32768];
            int read;

            while ((read = input.read(buffer)) >= 0) {
                if (read > 0) digest.update(buffer, 0, read);
            }
        }

        StringBuilder hex = new StringBuilder();
        for (byte b : digest.digest()) {
            hex.append(String.format(Locale.US, "%02x", b));
        }
        return hex.toString();
    }

    private void openInstaller(Uri uri) {
        Intent install = new Intent(Intent.ACTION_VIEW);
        install.setDataAndType(uri, "application/vnd.android.package-archive");
        install.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        install.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        activity.startActivity(install);
        clearPending();
    }

    private void registerReceiverIfNeeded() {
        if (receiverRegistered) return;

        IntentFilter filter = new IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE);

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

    private void clearPending() {
        prefs.edit()
                .remove(KEY_DOWNLOAD_ID)
                .remove(KEY_APK_URL)
                .remove(KEY_SHA256)
                .remove(KEY_VERSION)
                .apply();
    }

    public void destroy() {
        executor.shutdownNow();

        if (receiverRegistered) {
            try {
                activity.unregisterReceiver(downloadReceiver);
            } catch (Exception ignored) {
            }
            receiverRegistered = false;
        }
    }
}
