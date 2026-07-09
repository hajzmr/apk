package com.hajzmr.app;

import android.util.Log;
import android.webkit.CookieManager;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * يرسل توكن جهاز إشعارات Firebase إلى save-fcm-token.php على الخادم،
 * مرفقاً بكوكيز جلسة تسجيل الدخول الحالية (session cookie) حتى يتعرّف
 * السيرفر على حساب العميل المرتبط بهذا الجهاز.
 */
public class TokenUploader {

    private static final String TAG = "HajzMR-Token";

    public static void upload(String baseUrl, String token) {
        new Thread(() -> {
            HttpURLConnection conn = null;
            try {
                String cookie = CookieManager.getInstance().getCookie(baseUrl);
                URL url = new URL(baseUrl + "save-fcm-token.php");
                conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setDoOutput(true);
                conn.setConnectTimeout(10000);
                conn.setReadTimeout(10000);
                conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8");
                if (cookie != null) {
                    conn.setRequestProperty("Cookie", cookie);
                }

                String body = "token=" + java.net.URLEncoder.encode(token, "UTF-8") + "&platform=android";
                try (OutputStream os = conn.getOutputStream()) {
                    os.write(body.getBytes(StandardCharsets.UTF_8));
                }

                int code = conn.getResponseCode();
                Log.d(TAG, "استجابة حفظ توكن FCM: HTTP " + code);
            } catch (Exception e) {
                Log.w(TAG, "فشل إرسال توكن FCM إلى الخادم", e);
            } finally {
                if (conn != null) conn.disconnect();
            }
        }).start();
    }
}
