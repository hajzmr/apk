package com.hajzmr.app;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.net.http.SslError;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.view.KeyEvent;
import android.webkit.CookieManager;
import android.webkit.SslErrorHandler;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.ProgressBar;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import com.google.firebase.messaging.FirebaseMessaging;

/**
 * يعرض موقع حجزمر داخل WebView كامل الشاشة، ويتكفّل بـ:
 *  - الحفاظ على تصفّح المستخدم داخل التطبيق (بدون فتح متصفح خارجي).
 *  - دعم رفع صور وصل الدفع (input type=file) من نموذج الحجز.
 *  - تسجيل توكن إشعارات Firebase (FCM) وربطه بحساب العميل بعد تسجيل الدخول.
 */
public class MainActivity extends AppCompatActivity {

    private static final String TAG = "HajzMR";
    private WebView webView;
    private SwipeRefreshLayout swipeRefresh;
    private ProgressBar progressBar;
    private ValueCallback<Uri[]> filePathCallback;
    private static final int FILE_CHOOSER_REQUEST_CODE = 51426;

    private String baseUrl;

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        baseUrl = getString(R.string.base_url);

        webView      = findViewById(R.id.webView);
        swipeRefresh = findViewById(R.id.swipeRefresh);
        progressBar  = findViewById(R.id.progressBar);

        // تفعيل الكوكيز (ضروري كي تعمل جلسة تسجيل الدخول PHP session بشكل طبيعي)
        CookieManager cookieManager = CookieManager.getInstance();
        cookieManager.setAcceptCookie(true);
        cookieManager.setAcceptThirdPartyCookies(webView, true);

        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setLoadWithOverviewMode(true);
        settings.setUseWideViewPort(true);
        settings.setSupportZoom(false);
        settings.setAllowFileAccess(true);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        settings.setTextZoom(100);
        settings.setUserAgentString(settings.getUserAgentString() + " HajzMRApp/1.0");

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                if (url.startsWith(baseUrl) || url.startsWith("https://hajzmr.com")) {
                    return false; // ابقَ داخل WebView
                }
                if (url.startsWith("tel:") || url.startsWith("mailto:") || url.startsWith("whatsapp:")) {
                    try {
                        startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
                    } catch (Exception ignored) {}
                    return true;
                }
                return false;
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                swipeRefresh.setRefreshing(false);
                progressBar.setVisibility(android.view.View.GONE);

                // بعد نجاح تسجيل الدخول يتم التوجيه إما إلى my-account.php (مسافر)
                // أو إلى admin/dashboard.php وما بعدها (ممثل شركة عبر admin/login.php)
                // — في الحالتين هذه لحظة مناسبة لتسجيل/تحديث توكن الإشعارات المرتبط
                // بحساب المستخدم الحالي بعد أن أصبحت كوكيز الجلسة متوفرة فعلياً.
                boolean isCustomerAccountPage = url != null && url.contains("my-account.php");
                boolean isAdminPanelPage      = url != null && url.contains("/admin/") && !url.contains("login.php");
                if (isCustomerAccountPage || isAdminPanelPage) {
                    registerFcmToken();
                }
            }

            @Override
            public void onReceivedSslError(WebView view, SslErrorHandler handler, SslError error) {
                // لا نتجاهل أخطاء SSL في بيئة الإنتاج — إجراء أمان قياسي
                handler.cancel();
            }
        });

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView view, int newProgress) {
                progressBar.setProgress(newProgress);
                if (newProgress >= 100) progressBar.setVisibility(android.view.View.GONE);
            }

            // دعم اختيار/تصوير ملف (صورة وصل الدفع) من نموذج الحجز
            @Override
            public boolean onShowFileChooser(WebView webView, ValueCallback<Uri[]> callback,
                                              FileChooserParams fileChooserParams) {
                filePathCallback = callback;
                Intent intent = fileChooserParams.createIntent();
                try {
                    startActivityForResult(intent, FILE_CHOOSER_REQUEST_CODE);
                } catch (Exception e) {
                    filePathCallback = null;
                    return false;
                }
                return true;
            }
        });

        swipeRefresh.setOnRefreshListener(() -> webView.reload());

        webView.loadUrl(baseUrl);

        requestNotificationPermissionIfNeeded();
        registerFcmToken();
    }

    private void requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this,
                        new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1001);
            }
        }
    }

    /** يجلب توكن FCM الحالي للجهاز ويرسله لحفظه على الخادم مرتبطاً بحساب العميل الحالي */
    private void registerFcmToken() {
        FirebaseMessaging.getInstance().getToken().addOnCompleteListener(task -> {
            if (!task.isSuccessful()) {
                Log.w(TAG, "تعذّر جلب توكن FCM", task.getException());
                return;
            }
            String token = task.getResult();
            TokenUploader.upload(baseUrl, token);
        });
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == FILE_CHOOSER_REQUEST_CODE) {
            if (filePathCallback == null) return;
            Uri[] results = null;
            if (resultCode == RESULT_OK && data != null) {
                String dataString = data.getDataString();
                if (dataString != null) {
                    results = new Uri[]{Uri.parse(dataString)};
                }
            }
            filePathCallback.onReceiveValue(results);
            filePathCallback = null;
        } else {
            super.onActivityResult(requestCode, resultCode, data);
        }
    }

    @Override
    public void onBackPressed() {
        if (webView.canGoBack()) {
            webView.goBack();
        } else {
            super.onBackPressed();
        }
    }
}
