package nl.wapnet.outlookwrapper;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.DownloadManager;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Message;
import android.view.KeyEvent;
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
    private boolean volumeUpDown;
    private boolean volumeDownDown;
    private AlertDialog openDialog;
    private static final String VIEWPORT_WIDTH = "viewport_width";
    private static final String THEME = "theme";
    // false: desktop Outlook (default). true: Outlook's own mobile web layout, the slimmed-down one.
    private static final String LITE = "lite";
    // Stored as the index of the Theme menu choice.
    private static final int THEME_SYSTEM = 0, THEME_LIGHT = 1, THEME_DARK = 2;
    // CSS pixels the page lays out in; 0 keeps Outlook's own device-width viewport.
    private static final int[] VIEWPORT_WIDTHS = {0, 480, 600, 800, 1024};
    // Rewrites the viewport meta tag and keeps it rewritten when Outlook touches it again.
    private static final String VIEWPORT_SCRIPT = "(w=>{window.__vpWidth=w;const set=()=>{"
            + "let m=document.querySelector('meta[name=viewport]');if(!m){m=document.createElement('meta');"
            + "m.name='viewport';document.head.appendChild(m)}const c='width='+window.__vpWidth;"
            + "if(m.content!==c)m.content=c};set();if(!window.__vpObserver){window.__vpObserver=new MutationObserver(set);"
            + "window.__vpObserver.observe(document.head,{subtree:true,childList:true,attributes:true,"
            + "attributeFilter:['content']})}})(%d)";

    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(base);
        // Same file as getPreferences(), which is not available yet here.
        int theme = base.getSharedPreferences(getClass().getSimpleName(), MODE_PRIVATE).getInt(THEME, THEME_SYSTEM);
        if (theme != THEME_SYSTEM) {
            // Picks values-night (or not) for the app theme; WebView derives prefers-color-scheme from it.
            Configuration night = new Configuration();
            night.uiMode = theme == THEME_DARK ? Configuration.UI_MODE_NIGHT_YES : Configuration.UI_MODE_NIGHT_NO;
            applyOverrideConfiguration(night);
        }
    }

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
        // Zooms out to fit when a wider viewport width is chosen in the menu.
        settings.setLoadWithOverviewMode(true);
        if (Build.VERSION.SDK_INT >= 33) {
            // ponytail: Android 13+ only. Darkens pages without their own dark style (e.g. some mails)
            // when the app theme is dark; Outlook's own dark theme takes precedence where it has one.
            settings.setAlgorithmicDarkeningAllowed(true);
        }
        // Desktop identity, Android input: passes platform-scoped "use the Outlook app/Edge" policies.
        // ponytail: Lite shows Android to Microsoft, so a tenant policy that blocks mobile browsers
        // (the reason for the desktop UA) blocks Lite too; switching back to Desktop recovers.
        settings.setUserAgentString(lite()
                ? UserAgent.mobile(settings.getUserAgentString())
                : UserAgent.desktop(settings.getUserAgentString()));
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

            @Override
            public void onPageFinished(WebView view, String url) {
                applyViewportWidth();
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

    private int viewportWidth() {
        return getPreferences(MODE_PRIVATE).getInt(VIEWPORT_WIDTH, 0);
    }

    private void applyViewportWidth() {
        int width = viewportWidth();
        if (width > 0 && Links.opensInApp(webView.getUrl())) {
            webView.evaluateJavascript(String.format(java.util.Locale.ROOT, VIEWPORT_SCRIPT, width), null);
        }
    }

    // Menu on volume up + down together; a single volume key keeps changing the volume.
    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP) {
            volumeUpDown = true;
        } else if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
            volumeDownDown = true;
        } else {
            return super.onKeyDown(keyCode, event);
        }
        if (volumeUpDown && volumeDownDown) {
            // Holding the keys repeats onKeyDown; only the first press opens the menu.
            if (event.getRepeatCount() == 0) {
                showMenu();
            }
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP) {
            volumeUpDown = false;
        } else if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
            volumeDownDown = false;
        }
        return super.onKeyUp(keyCode, event);
    }

    private void showMenu() {
        if (openDialog != null && openDialog.isShowing()) {
            return;
        }
        String[] items = {getString(R.string.menu_layout), getString(R.string.menu_resolution), getString(R.string.menu_theme)};
        Runnable[] actions = {this::showLayoutMenu, this::showResolutionMenu, this::showThemeMenu};
        openDialog = new AlertDialog.Builder(this)
                .setItems(items, (d, which) -> actions[which].run())
                .show();
    }

    private void showResolutionMenu() {
        String[] labels = new String[VIEWPORT_WIDTHS.length];
        int checked = 0;
        for (int i = 0; i < labels.length; i++) {
            labels[i] = VIEWPORT_WIDTHS[i] == 0 ? getString(R.string.resolution_default)
                    : getString(R.string.resolution_width, VIEWPORT_WIDTHS[i]);
            if (VIEWPORT_WIDTHS[i] == viewportWidth()) {
                checked = i;
            }
        }
        openDialog = new AlertDialog.Builder(this)
                .setTitle(R.string.menu_resolution)
                .setSingleChoiceItems(labels, checked, (d, which) -> {
                    d.dismiss();
                    getPreferences(MODE_PRIVATE).edit().putInt(VIEWPORT_WIDTH, VIEWPORT_WIDTHS[which]).apply();
                    // Reload so the page starts from its own viewport, then onPageFinished applies the choice.
                    webView.reload();
                })
                .show();
    }

    private boolean lite() {
        return getPreferences(MODE_PRIVATE).getBoolean(LITE, false);
    }

    private void showLayoutMenu() {
        String[] labels = {getString(R.string.layout_desktop), getString(R.string.layout_lite)};
        openDialog = new AlertDialog.Builder(this)
                .setTitle(R.string.menu_layout)
                .setSingleChoiceItems(labels, lite() ? 1 : 0, (d, which) -> {
                    d.dismiss();
                    getPreferences(MODE_PRIVATE).edit().putBoolean(LITE, which == 1).apply();
                    // The User-Agent is set in onCreate; the signed-in session survives in cookies.
                    recreate();
                })
                .show();
    }

    private void showThemeMenu() {
        String[] labels = {getString(R.string.theme_system), getString(R.string.theme_light), getString(R.string.theme_dark)};
        openDialog = new AlertDialog.Builder(this)
                .setTitle(R.string.menu_theme)
                .setSingleChoiceItems(labels, getPreferences(MODE_PRIVATE).getInt(THEME, THEME_SYSTEM), (d, which) -> {
                    d.dismiss();
                    getPreferences(MODE_PRIVATE).edit().putInt(THEME, which).apply();
                    // The theme is chosen in attachBaseContext; the signed-in session survives in cookies.
                    recreate();
                })
                .show();
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
        if (openDialog != null) {
            openDialog.dismiss();
        }
        webView.destroy();
        super.onDestroy();
    }
}
