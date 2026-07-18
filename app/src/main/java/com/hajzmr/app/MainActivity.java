package com.hajzmr.app;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.DownloadManager;
import android.content.ContentValues;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.net.http.SslError;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.print.PrintAttributes;
import android.print.PrintManager;
import android.provider.MediaStore;
import android.util.Base64;
import android.util.Log;
import android.view.KeyEvent;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.SslErrorHandler;
import android.webkit.URLUtil;
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

import java.io.OutputStream;

/**
 * يعرض موقع حجزمر داخل WebView كامل الشاشة، ويتكفّل بـ:
 *  - الحفاظ على تصفّح المستخدم داخل التطبيق (بدون فتح متصفح خارجي).
 *  - دعم رفع صور وصل الدفع (input type=file) من نموذج الحجز.
 *  - تسجيل توكن إشعارات Firebase (FCM) وربطه بحساب العميل بعد تسجيل الدخول.
 *  - تحميل الملفات (تذكرة PDF، إلخ) عبر DownloadManager — WebView لا يحمّل
 *    أي شيء تلقائياً بدون DownloadListener صريح.
 *  - طباعة أصلية عبر PrintManager، لأن window.print() في الجافاسكربت لا
 *    ينفّذ شيئاً داخل WebView افتراضياً (يحتاج تطبيق مضيف يوفر هذه الواجهة).
 */
public class MainActivity extends AppCompatActivity {

    private static final String TAG = "HajzMR";
    private WebView webView;
    private SwipeRefreshLayout swipeRefresh;
    private ProgressBar progressBar;
    private ValueCallback<Uri[]> filePathCallback;
    private static final int FILE_CHOOSER_REQUEST_CODE = 51426;

    private String baseUrl;

    // لغة الموقع الحالية كما يبلّغنا بها الجافاسكربت عبر واجهة AndroidLang
    // (انظر lang-switcher.php)، تُستخدم لعرض إشعارات Toast الأصلية باللغة
    // الصحيحة (تحميل الملف، حفظ لقطة الشاشة...). تُحفظ في SharedPreferences
    // كي تبقى صحيحة حتى قبل أن تُحمَّل الصفحة وتُبلّغنا من جديد.
    private static final String PREFS_NAME = "HajzMRPrefs";
    private static final String PREF_LANG  = "site_lang";
    private volatile String currentLang = "ar";

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        baseUrl = getString(R.string.base_url);

        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        currentLang = prefs.getString(PREF_LANG, "ar");

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

        // ===== تحميل الملفات (تذكرة PDF، إلخ) =====
        // بدون هذا، أي رابط تحميل (حتى لو كان تحميل HTTP عادي بترويسة
        // Content-Disposition: attachment) يتجاهله WebView بصمت تماماً.
        webView.setDownloadListener((url, userAgent, contentDisposition, mimetype, contentLength) -> {
            if (url != null && url.startsWith("data:")) {
                // روابط data: (تُستخدم في زر "لقطة شاشة" عبر html-to-image) لا يدعمها
                // DownloadManager إطلاقاً (يقبل http/https فقط) — نحفظها يدوياً كصورة.
                saveDataUriImage(url, mimetype);
                return;
            }
            try {
                DownloadManager.Request request = new DownloadManager.Request(Uri.parse(url));
                String cookies = CookieManager.getInstance().getCookie(url);
                if (cookies != null) {
                    request.addRequestHeader("Cookie", cookies);
                }
                request.addRequestHeader("User-Agent", userAgent);
                request.setMimeType(mimetype);
                String fileName = URLUtil.guessFileName(url, contentDisposition, mimetype);
                request.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName);
                request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
                request.allowScanningByMediaScanner();

                DownloadManager dm = (DownloadManager) getSystemService(DOWNLOAD_SERVICE);
                if (dm != null) {
                    dm.enqueue(request);
                    Toast.makeText(getApplicationContext(),
                            tr("جارٍ تحميل الملف...", "Téléchargement du fichier..."),
                            Toast.LENGTH_SHORT).show();
                }
            } catch (Exception e) {
                Log.e(TAG, "فشل بدء التحميل", e);
                Toast.makeText(getApplicationContext(),
                        tr("تعذّر تحميل الملف", "Impossible de télécharger le fichier"),
                        Toast.LENGTH_SHORT).show();
            }
        });

        // ===== طباعة أصلية =====
        // window.print() في صفحات الموقع لا يفعل شيئاً بمفرده داخل WebView،
        // لذا نوفر واجهة JS (AndroidPrint.printPage) تستدعي PrintManager
        // الحقيقي على مستوى النظام. صفحات الموقع تتحقق من وجود هذه الواجهة
        // وتستخدمها بدل window.print() عند توفرها.
        webView.addJavascriptInterface(new Object() {
            @JavascriptInterface
            public void printPage() {
                runOnUiThread(() -> {
                    try {
                        PrintManager printManager = (PrintManager) getSystemService(PRINT_SERVICE);
                        String jobName = getString(R.string.app_name) + " - تذكرة";
                        if (printManager != null) {
                            printManager.print(
                                jobName,
                                webView.createPrintDocumentAdapter(jobName),
                                new PrintAttributes.Builder().build()
                            );
                        }
                    } catch (Exception e) {
                        Log.e(TAG, "فشل بدء الطباعة", e);
                        Toast.makeText(getApplicationContext(),
                                tr("تعذّر بدء الطباعة", "Impossible de démarrer l'impression"),
                                Toast.LENGTH_SHORT).show();
                    }
                });
            }
        }, "AndroidPrint");

        // ===== مزامنة لغة الموقع مع التطبيق =====
        // يستدعيها lang-switcher.php في كل مرة تُطبَّق فيها اللغة (عند تحميل
        // الصفحة وعند تبديل المستخدم للغة)، لنعرف بأي لغة نعرض إشعارات
        // Toast الأصلية أدناه.
        webView.addJavascriptInterface(new Object() {
            @JavascriptInterface
            public void setLanguage(String lang) {
                if (lang == null) return;
                currentLang = "fr".equals(lang) ? "fr" : "ar";
                getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                        .edit()
                        .putString(PREF_LANG, currentLang)
                        .apply();
            }
        }, "AndroidLang");

        swipeRefresh.setOnRefreshListener(() -> webView.reload());

        webView.loadUrl(baseUrl);

        requestNotificationPermissionIfNeeded();
        registerFcmToken();
    }

    /** يُرجع النص الفرنسي إذا كانت لغة الموقع الحالية fr، وإلا يُرجع النص العربي. */
    private String tr(String ar, String fr) {
        return "fr".equals(currentLang) ? fr : ar;
    }

    /**
     * يحفظ صورة قادمة كرابط data: (مثل ناتج زر "لقطة شاشة" عبر html-to-image)
     * مباشرة في مجلد Pictures عبر MediaStore، لأن DownloadManager لا يدعم
     * روابط data: إطلاقاً (http/https فقط).
     */
    private void saveDataUriImage(String dataUri, String mimetypeHint) {
        try {
            int commaIndex = dataUri.indexOf(',');
            if (commaIndex < 0) throw new IllegalArgumentException("data URI غير صالح");
            String meta = dataUri.substring(5, commaIndex); // بعد "data:"
            String base64Data = dataUri.substring(commaIndex + 1);
            String mimeType = meta.contains(";") ? meta.substring(0, meta.indexOf(';')) : meta;
            if (mimeType.isEmpty()) mimeType = (mimetypeHint != null ? mimetypeHint : "image/png");
            String extension = mimeType.contains("png") ? "png" : "jpg";

            byte[] bytes = Base64.decode(base64Data, Base64.DEFAULT);
            String fileName = "ticket_" + System.currentTimeMillis() + "." + extension;

            ContentValues values = new ContentValues();
            values.put(MediaStore.Images.Media.DISPLAY_NAME, fileName);
            values.put(MediaStore.Images.Media.MIME_TYPE, mimeType);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                values.put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES);
            }

            Uri collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI;
            Uri itemUri = getContentResolver().insert(collection, values);
            if (itemUri == null) throw new IllegalStateException("تعذّر إنشاء ملف الصورة");

            try (OutputStream out = getContentResolver().openOutputStream(itemUri)) {
                if (out == null) throw new IllegalStateException("تعذّر فتح ملف الصورة للكتابة");
                out.write(bytes);
            }

            Toast.makeText(getApplicationContext(),
                    tr("تم حفظ لقطة الشاشة في الصور", "Capture d'écran enregistrée dans les Photos"),
                    Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Log.e(TAG, "فشل حفظ لقطة الشاشة", e);
            Toast.makeText(getApplicationContext(),
                    tr("تعذّر حفظ لقطة الشاشة", "Impossible d'enregistrer la capture d'écran"),
                    Toast.LENGTH_SHORT).show();
        }
    }

    private void requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this,
                        new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1001);
            }
        }
        // على أندرويد 9 وأقدم فقط، يحتاج DownloadManager صراحة إذن الكتابة
        // للتخزين الخارجي (WRITE_EXTERNAL_STORAGE) لحفظ الملفات في Downloads.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
                    != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this,
                        new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, 1002);
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
