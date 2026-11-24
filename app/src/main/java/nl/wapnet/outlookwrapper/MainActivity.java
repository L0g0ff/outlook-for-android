package nl.wapnet.outlookwrapper;

import android.app.Activity;
import android.app.DownloadManager;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Message;
import android.webkit.CookieManager;
import android.webkit.MimeTypeMap;
import android.webkit.URLUtil;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

public final class MainActivity extends Activity {
    private static final int FILE_CHOOSER_REQUEST = 1;
    // At most one picker is open; a recreated Activity starts with null, so a late result is dropped.
    private ValueCallback<Uri[]> fileChooserCallback;
    private WebView webView;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        webView = new WebView(this);
        setContentView(webView);

        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setSupportZoom(true);
        settings.setBuiltInZoomControls(true);
        settings.setDisplayZoomControls(false);
        settings.setUseWideViewPort(true);
        // Desktop identity, Android input: passes platform-scoped "use the Outlook app/Edge" policies.
        settings.setUserAgentString(UserAgent.desktop(settings.getUserAgentString()));
        // target=_blank links arrive in onCreateWindow instead of replacing Outlook.
        settings.setSupportMultipleWindows(true);

        CookieManager cookies = CookieManager.getInstance();
        cookies.setAcceptCookie(true);
        cookies.setAcceptThirdPartyCookies(webView, true);

        webView.setDownloadListener((url, userAgent, contentDisposition, mimeType, length) ->
                download(url, userAgent, contentDisposition, mimeType));
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                String url = request.getUrl().toString();
                String scheme = request.getUrl().getScheme();
                if ("https".equalsIgnoreCase(scheme) || "http".equalsIgnoreCase(scheme)) {
                    // ponytail: same-window navigations stay in-app so redirects to a tenant's own
                    // sign-in server keep working; mail links use new windows (onCreateWindow).
                    return false;
                }
                openLink(url);
                return true;
            }
        });
        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onCreateWindow(WebView view, boolean isDialog, boolean isUserGesture, Message result) {
                // A throwaway WebView only catches the URL of the new window; it never renders it.
                WebView catcher = new WebView(view.getContext());
                catcher.setWebViewClient(new WebViewClient() {
                    @Override
                    public boolean shouldOverrideUrlLoading(WebView popup, WebResourceRequest request) {
                        openLink(request.getUrl().toString());
                        popup.post(popup::destroy);
                        return true;
                    }
                });
                ((WebView.WebViewTransport) result.obj).setWebView(catcher);
                result.sendToTarget();
                return true;
            }

            @Override
            public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback, FileChooserParams params) {
                if (fileChooserCallback != null) {
                    fileChooserCallback.onReceiveValue(null);
                }
                fileChooserCallback = callback;
                // System picker (ACTION_GET_CONTENT): grants read access per file, no storage permission.
                Intent pick = params.createIntent();
                if (params.getMode() == FileChooserParams.MODE_OPEN_MULTIPLE) {
                    pick.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
                }
                try {
                    startActivityForResult(pick, FILE_CHOOSER_REQUEST);
                } catch (ActivityNotFoundException e) {
                    fileChooserCallback = null;
                    Toast.makeText(MainActivity.this, R.string.link_no_app, Toast.LENGTH_SHORT).show();
                    return false;
                }
                return true;
            }
        });
        String draft = Links.compose(getIntent().getDataString());
        webView.loadUrl(draft != null ? draft : Links.START_URL);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        // singleTask: a mailto: link from another app reuses this Activity and its signed-in WebView.
        String draft = Links.compose(intent.getDataString());
        if (draft != null) {
            webView.loadUrl(draft);
        }
    }

    /** Outlook/sign-in stays here, mailto: becomes a draft here, the rest goes to Android. */
    private void openLink(String url) {
        String draft = Links.compose(url);
        if (draft != null) {
            webView.loadUrl(draft);
        } else if (Links.opensInApp(url)) {
            webView.loadUrl(url);
        } else if (!Links.opensExternally(url)) {
            Toast.makeText(this, R.string.link_blocked, Toast.LENGTH_SHORT).show();
        } else {
            try {
                startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)).addCategory(Intent.CATEGORY_BROWSABLE));
            } catch (ActivityNotFoundException e) {
                Toast.makeText(this, R.string.link_no_app, Toast.LENGTH_SHORT).show();
            }
        }
    }

    /** Attachments via Android's DownloadManager with the WebView session cookie for that URL. */
    private void download(String url, String userAgent, String contentDisposition, String mimeType) {
        Uri uri = Uri.parse(url);
        if (!"https".equalsIgnoreCase(uri.getScheme())) {
            // ponytail: blob:/data: files are generated inside the page; DownloadManager cannot fetch
            // them. Add a JavaScript bridge if Outlook turns out to serve attachments this way.
            Toast.makeText(this, getString(R.string.download_unsupported, uri.getScheme()), Toast.LENGTH_LONG).show();
            return;
        }
        String name = FileName.fromContentDisposition(contentDisposition);
        if (name == null) {
            name = URLUtil.guessFileName(url, null, mimeType);
        }
        name = FileName.withExtension(name, MimeTypeMap.getSingleton().getExtensionFromMimeType(mimeType));
        DownloadManager.Request request = new DownloadManager.Request(uri)
                .addRequestHeader("Cookie", CookieManager.getInstance().getCookie(url))
                .addRequestHeader("User-Agent", userAgent)
                .setMimeType(mimeType)
                .setTitle(name)
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
        if (Build.VERSION.SDK_INT >= 29) {
            // Public Downloads without a storage permission from Android 10 on.
            request.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name);
        }
        getSystemService(DownloadManager.class).enqueue(request);
        Toast.makeText(this, getString(R.string.download_started, name), Toast.LENGTH_SHORT).show();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode != FILE_CHOOSER_REQUEST) {
            super.onActivityResult(requestCode, resultCode, data);
            return;
        }
        ValueCallback<Uri[]> callback = fileChooserCallback;
        fileChooserCallback = null;
        if (callback == null) {
            return;
        }
        Uri[] files = null;
        if (resultCode == RESULT_OK && data != null) {
            if (data.getClipData() != null) {
                files = new Uri[data.getClipData().getItemCount()];
                for (int i = 0; i < files.length; i++) {
                    files[i] = data.getClipData().getItemAt(i).getUri();
                }
            } else if (data.getData() != null) {
                files = new Uri[] {data.getData()};
            }
        }
        // null tells WebView the picker was cancelled, so Outlook can open it again.
        callback.onReceiveValue(files);
    }

    @Override
    public void onBackPressed() {
        if (webView.canGoBack()) {
            webView.goBack();
        } else {
            super.onBackPressed();
        }
    }

    @Override
    protected void onPause() {
        CookieManager.getInstance().flush();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        if (fileChooserCallback != null) {
            fileChooserCallback.onReceiveValue(null);
            fileChooserCallback = null;
        }
        webView.destroy();
        super.onDestroy();
    }
}
