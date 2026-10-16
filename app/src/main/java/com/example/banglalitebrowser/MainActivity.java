package com.example.banglalitebrowser;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.DownloadManager;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.Menu;
import android.view.View;
import android.view.WindowInsets;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.webkit.CookieManager;
import android.webkit.URLUtil;
import android.webkit.WebBackForwardList;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebStorage;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

public class MainActivity extends Activity {
    private static final String HOME_URL = "https://www.google.com";
    private static final int MAX_TABS = 8;                       // fewer live tabs = less RAM, snappier browser
    private static final int REQ_STORAGE = 11, REQ_NOTIF = 12;
    private static final String PERM_STORAGE = "android.permission.WRITE_EXTERNAL_STORAGE";
    private static final String PERM_NOTIF = "android.permission.POST_NOTIFICATIONS";

    private static final int GREEN = Color.rgb(23, 107, 91), INK = Color.rgb(28, 42, 39), MUTED = Color.rgb(105, 121, 116),
            BG = Color.rgb(247, 250, 249), LINE = Color.rgb(225, 234, 230), SOFT = Color.rgb(232, 243, 239);
    private static final Pattern DOMAIN_LIKE = Pattern.compile("^[a-z0-9.-]+\\.[a-z]{2,}([/:?#].*)?$", Pattern.CASE_INSENSITIVE);

    /** One browser tab. The WebView is created lazily and can be released under memory pressure. */
    private static class Tab {
        WebView web;
        String url = "";
        String title = "";
        Bundle state;                                        // saved back/forward list while the WebView is released
        boolean home = true;                                 // showing the home screen instead of a page
    }

    private LinearLayout root;
    private FrameLayout content;
    private ScrollView homePanel;
    private EditText addressBar;
    private ProgressBar progressBar;
    private ImageView siteIcon;
    private ImageButton reloadBtn, backBtn, forwardBtn;
    private TextView tabCountView;

    private final List<Tab> tabs = new ArrayList<>();
    private int activeTab = -1;
    private boolean loading = false;

    private SharedPreferences prefs;
    private Store store;
    private boolean blockerEnabled, dataSaver;
    private final AtomicInteger blockedCount = new AtomicInteger();
    private String[] pendingDownload;

    // ===================================================== lifecycle

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences("rishat_browser", MODE_PRIVATE);
        store = new Store(prefs);
        blockerEnabled = prefs.getBoolean("blocker_enabled", true);
        dataSaver = prefs.getBoolean("data_saver", false);
        buildUi();
        restoreTabs();
        if (savedInstanceState == null) handleIntent(getIntent());
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIntent(intent);
    }

    @Override protected void onResume() {
        super.onResume();
        Tab t = cur();
        if (t != null && t.web != null) {
            t.web.resumeTimers();
            if (!t.home) t.web.onResume();
        }
    }

    @Override protected void onPause() {
        Tab t = cur();
        if (t != null && t.web != null) {
            t.web.onPause();
            t.web.pauseTimers();                             // stops background JS/layout work: saves battery and CPU
        }
        CookieManager.getInstance().flush();
        saveTabs();
        super.onPause();
    }

    @Override public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        if (level == TRIM_MEMORY_RUNNING_LOW || level == TRIM_MEMORY_RUNNING_CRITICAL || level >= TRIM_MEMORY_BACKGROUND) {
            releaseBackgroundTabs();
        }
    }

    @Override protected void onDestroy() {
        for (Tab t : new ArrayList<>(tabs)) destroyWeb(t);
        tabs.clear();
        super.onDestroy();
    }

    @Override public void onBackPressed() {
        Tab t = cur();
        if (t != null && !t.home) {
            if (t.web != null && t.web.canGoBack()) t.web.goBack(); else showHome();
        } else if (tabs.size() > 1) {
            closeTab(activeTab);
        } else {
            super.onBackPressed();
        }
    }

    // ===================================================== UI

    private void buildUi() {
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);
        // Android 15+ draws edge-to-edge: keep our bars clear of system bars and the keyboard.
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            int l, t, r, b;
            if (Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets i = insets.getInsets(WindowInsets.Type.systemBars()
                        | WindowInsets.Type.displayCutout() | WindowInsets.Type.ime());
                l = i.left; t = i.top; r = i.right; b = i.bottom;
            } else {
                l = insets.getSystemWindowInsetLeft(); t = insets.getSystemWindowInsetTop();
                r = insets.getSystemWindowInsetRight(); b = insets.getSystemWindowInsetBottom();
            }
            v.setPadding(l, t, r, b);
            return insets;
        });

        // ---- address bar
        LinearLayout top = new LinearLayout(this);
        top.setPadding(dp(10), dp(8), dp(10), dp(8));
        LinearLayout addressRow = new LinearLayout(this);
        addressRow.setGravity(Gravity.CENTER_VERTICAL);
        addressRow.setPadding(dp(14), 0, dp(4), 0);
        addressRow.setBackground(roundRect(Color.WHITE, dp(22), LINE));
        siteIcon = new ImageView(this);
        siteIcon.setImageResource(R.drawable.ic_search);
        addressRow.addView(siteIcon, new LinearLayout.LayoutParams(dp(18), dp(18)));
        addressBar = new EditText(this);
        addressBar.setSingleLine(true);
        addressBar.setTextSize(14);
        addressBar.setTextColor(INK);
        addressBar.setHintTextColor(MUTED);
        addressBar.setHint("ওয়েব ঠিকানা বা সার্চ লিখুন");
        addressBar.setBackground(null);
        addressBar.setPadding(dp(10), 0, dp(6), 0);
        addressBar.setSelectAllOnFocus(true);
        addressBar.setImeOptions(EditorInfo.IME_ACTION_GO);
        addressBar.setInputType(InputType.TYPE_CLASS_TEXT);
        addressRow.addView(addressBar, new LinearLayout.LayoutParams(0, -1, 1));
        reloadBtn = iconButton(R.drawable.ic_refresh, "রিলোড");
        addressRow.addView(reloadBtn, new LinearLayout.LayoutParams(dp(40), dp(40)));
        top.addView(addressRow, new LinearLayout.LayoutParams(-1, dp(44)));
        root.addView(top, new LinearLayout.LayoutParams(-1, -2));

        progressBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progressBar.setMax(100);
        progressBar.setProgressTintList(ColorStateList.valueOf(GREEN));
        progressBar.setVisibility(View.INVISIBLE);           // INVISIBLE (not GONE) so the page never jumps
        root.addView(progressBar, new LinearLayout.LayoutParams(-1, dp(2)));

        // ---- page area
        content = new FrameLayout(this);
        content.setFocusableInTouchMode(true);
        homePanel = buildHome();
        content.addView(homePanel, new FrameLayout.LayoutParams(-1, -1));
        root.addView(content, new LinearLayout.LayoutParams(-1, 0, 1));

        // ---- bottom bar
        View divider = new View(this);
        divider.setBackgroundColor(LINE);
        root.addView(divider, new LinearLayout.LayoutParams(-1, dp(1)));
        LinearLayout bottom = new LinearLayout(this);
        bottom.setGravity(Gravity.CENTER);
        bottom.setBackgroundColor(Color.WHITE);
        backBtn = iconButton(R.drawable.ic_back, "পিছনে");
        forwardBtn = iconButton(R.drawable.ic_forward, "সামনে");
        ImageButton homeBtn = iconButton(R.drawable.ic_home, "হোম");
        ImageButton menuBtn = iconButton(R.drawable.ic_more, "মেনু");
        FrameLayout tabsBtn = new FrameLayout(this);
        tabsBtn.setBackgroundResource(rippleRes());
        tabsBtn.setContentDescription("ট্যাব");
        tabCountView = new TextView(this);
        tabCountView.setText("1");
        tabCountView.setTextSize(12);
        tabCountView.setTypeface(Typeface.DEFAULT_BOLD);
        tabCountView.setTextColor(INK);
        tabCountView.setGravity(Gravity.CENTER);
        GradientDrawable box = new GradientDrawable();
        box.setColor(Color.TRANSPARENT);
        box.setCornerRadius(dp(5));
        box.setStroke(dp(2), INK);
        tabCountView.setBackground(box);
        FrameLayout.LayoutParams tp = new FrameLayout.LayoutParams(dp(22), dp(22));
        tp.gravity = Gravity.CENTER;
        tabsBtn.addView(tabCountView, tp);
        bottom.addView(backBtn, new LinearLayout.LayoutParams(0, dp(48), 1));
        bottom.addView(forwardBtn, new LinearLayout.LayoutParams(0, dp(48), 1));
        bottom.addView(homeBtn, new LinearLayout.LayoutParams(0, dp(48), 1));
        bottom.addView(tabsBtn, new LinearLayout.LayoutParams(0, dp(48), 1));
        bottom.addView(menuBtn, new LinearLayout.LayoutParams(0, dp(48), 1));
        root.addView(bottom, new LinearLayout.LayoutParams(-1, -2));
        setContentView(root);

        // ---- actions
        addressBar.setOnEditorActionListener((v, action, event) -> {
            if (action == EditorInfo.IME_ACTION_GO
                    || (event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER && event.getAction() == KeyEvent.ACTION_DOWN)) {
                loadAddress();
                return true;
            }
            return false;
        });
        reloadBtn.setOnClickListener(v -> {
            WebView w = currentWeb();
            if (loading) { if (w != null) w.stopLoading(); }
            else if (w != null && !cur().home) w.reload();
            else showHome();
        });
        backBtn.setOnClickListener(v -> goBackOrHome());
        forwardBtn.setOnClickListener(v -> goForward());
        homeBtn.setOnClickListener(v -> showHome());
        tabsBtn.setOnClickListener(v -> showTabsDialog());
        menuBtn.setOnClickListener(v -> showMenu(v));
        root.requestApplyInsets();
        content.requestFocus();
        root.post(() -> content.requestFocus());
    }

    private void goBackOrHome() {
        Tab t = cur();
        if (t != null && !t.home && t.web != null && t.web.canGoBack()) t.web.goBack(); else showHome();
    }

    private void goForward() {
        Tab t = cur();
        if (t == null) return;
        if (t.home && t.web != null && t.web.getUrl() != null) { t.home = false; render(t); return; }
        if (t.web != null && t.web.canGoForward()) t.web.goForward();
    }

    private ScrollView buildHome() {
        ScrollView sv = new ScrollView(this);
        sv.setFillViewport(true);
        sv.setBackgroundColor(BG);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER_HORIZONTAL);
        col.setPadding(dp(20), dp(40), dp(20), dp(24));

        ImageView logo = new ImageView(this);
        logo.setImageResource(R.drawable.ic_browser_logo);
        col.addView(logo, new LinearLayout.LayoutParams(dp(64), dp(64)));
        TextView name = text("Rishat Browser", 22, INK);
        name.setTypeface(Typeface.DEFAULT_BOLD);
        LinearLayout.LayoutParams np = new LinearLayout.LayoutParams(-2, -2);
        np.topMargin = dp(10);
        col.addView(name, np);
        col.addView(text("সহজ, দ্রুত, আপনার ব্রাউজার", 13, MUTED));

        TextView search = text("ওয়েব ঠিকানা বা সার্চ লিখুন", 14, MUTED);
        search.setGravity(Gravity.CENTER_VERTICAL);
        search.setPadding(dp(18), 0, dp(18), 0);
        search.setBackground(roundRect(Color.WHITE, dp(24), LINE));
        search.setOnClickListener(v -> focusAddressBar());
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(-1, dp(48));
        sp.topMargin = dp(24);
        sp.bottomMargin = dp(20);
        col.addView(search, sp);

        col.addView(shortcutRow("Google", "google.com", HOME_URL, "YouTube", "youtube.com", "https://m.youtube.com"));
        col.addView(shortcutRow("Wikipedia", "wikipedia.org", "https://www.wikipedia.org", "Facebook", "facebook.com", "https://m.facebook.com"));
        sv.addView(col, new FrameLayout.LayoutParams(-1, -2));
        return sv;
    }

    private LinearLayout shortcutRow(String t1, String s1, String u1, String t2, String s2, String u2) {
        LinearLayout row = new LinearLayout(this);
        LinearLayout.LayoutParams p1 = new LinearLayout.LayoutParams(0, dp(64), 1);
        p1.rightMargin = dp(5);
        p1.bottomMargin = dp(10);
        LinearLayout.LayoutParams p2 = new LinearLayout.LayoutParams(0, dp(64), 1);
        p2.leftMargin = dp(5);
        p2.bottomMargin = dp(10);
        row.addView(shortcut(t1, s1, u1), p1);
        row.addView(shortcut(t2, s2, u2), p2);
        return row;
    }

    private LinearLayout shortcut(String title, String sub, final String url) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setPadding(dp(14), 0, dp(14), 0);
        card.setBackground(roundRect(Color.WHITE, dp(14), LINE));
        TextView n = text(title, 15, GREEN);
        n.setTypeface(Typeface.DEFAULT_BOLD);
        card.addView(n);
        card.addView(text(sub, 11, MUTED));
        card.setOnClickListener(v -> loadInCurrentTab(url));
        return card;
    }

    private void showMenu(View anchor) {
        PopupMenu pm = new PopupMenu(this, anchor, Gravity.END);
        Menu m = pm.getMenu();
        m.add(0, 1, 0, "নতুন ট্যাব");
        m.add(0, 2, 0, "বুকমার্কে যোগ করুন");
        m.add(0, 3, 0, "বুকমার্ক");
        m.add(0, 4, 0, "হিস্টোরি");
        m.add(0, 5, 0, "ডাউনলোড");
        m.add(0, 6, 0, "লিংক শেয়ার");
        m.add(0, 7, 0, "অ্যাড ও ট্র্যাকার ব্লকার (" + blockedCount.get() + ")").setCheckable(true).setChecked(blockerEnabled);
        m.add(0, 8, 0, "ডেটা সেভার (ছবি বন্ধ)").setCheckable(true).setChecked(dataSaver);
        m.add(0, 9, 0, "ক্যাশ ও কুকি মুছুন");
        pm.setOnMenuItemClickListener(item -> {
            switch (item.getItemId()) {
                case 1: newTab(null, true); break;
                case 2: addBookmark(); break;
                case 3: showSavedList(true); break;
                case 4: showSavedList(false); break;
                case 5: showDownloads(); break;
                case 6: shareCurrentPage(); break;
                case 7: toggleBlocker(); break;
                case 8: toggleDataSaver(); break;
                case 9: confirmClearData(); break;
                default: return false;
            }
            return true;
        });
        pm.show();
    }

    // ===================================================== tabs

    private Tab cur() { return activeTab >= 0 && activeTab < tabs.size() ? tabs.get(activeTab) : null; }
    private WebView currentWeb() { Tab t = cur(); return t == null ? null : t.web; }
    private boolean isActive(Tab t) { return cur() == t; }

    private void newTab(String url, boolean switchTo) {
        if (tabs.size() >= MAX_TABS) { toast("সর্বোচ্চ " + MAX_TABS + "টি ট্যাব খোলা যাবে"); return; }
        Tab t = new Tab();
        if (url != null && !url.isEmpty()) { t.url = url; t.home = false; }
        tabs.add(t);
        if (switchTo) switchToTab(tabs.size() - 1); else updateTabCount();
    }

    private void switchToTab(int index) {
        if (index < 0 || index >= tabs.size()) return;
        Tab old = cur();
        if (old != null && old != tabs.get(index) && old.web != null) old.web.onPause();   // background tabs do no work
        activeTab = index;
        render(tabs.get(index));
        updateTabCount();
    }

    /** Puts the tab's content (home screen or its WebView) on screen and syncs the bars. */
    private void render(Tab t) {
        for (int i = content.getChildCount() - 1; i >= 0; i--) {
            if (content.getChildAt(i) != homePanel) content.removeViewAt(i);
        }
        if (t.home) {
            homePanel.setVisibility(View.VISIBLE);
            setAddress("", true);
            setLoading(false);
            progressBar.setVisibility(View.INVISIBLE);
        } else {
            ensureWeb(t);
            homePanel.setVisibility(View.GONE);
            content.addView(t.web, new FrameLayout.LayoutParams(-1, -1));
            t.web.onResume();
            t.web.resumeTimers();
            String shown = t.web.getUrl() != null ? t.web.getUrl() : t.url;
            setAddress(shown, true);
            int p = t.web.getProgress();
            setLoading(p < 100);
            progressBar.setProgress(p);
            progressBar.setVisibility(p < 100 ? View.VISIBLE : View.INVISIBLE);
        }
        updateNav();
    }

    private void ensureWeb(Tab t) {
        if (t.web != null) return;
        t.web = createWebView(t);
        if (t.state != null) {
            WebBackForwardList restored = t.web.restoreState(t.state);
            t.state = null;
            if (restored == null && !t.url.isEmpty()) loadUrl(t.web, t.url);
        } else if (!t.url.isEmpty()) {
            loadUrl(t.web, t.url);
        }
    }

    private void loadUrl(WebView w, String url) {
        if (dataSaver) {
            Map<String, String> h = new HashMap<>();
            h.put("Save-Data", "on");                         // asks servers for lighter pages
            w.loadUrl(url, h);
        } else {
            w.loadUrl(url);
        }
    }

    private void closeTab(int index) {
        if (index < 0 || index >= tabs.size()) return;
        boolean wasActive = index == activeTab;
        destroyWeb(tabs.remove(index));
        if (tabs.isEmpty()) { activeTab = -1; newTab(null, true); return; }
        if (wasActive) { activeTab = -1; switchToTab(Math.min(index, tabs.size() - 1)); }
        else { if (index < activeTab) activeTab--; updateTabCount(); }
    }

    private void closeAllTabs() {
        for (Tab t : new ArrayList<>(tabs)) destroyWeb(t);
        tabs.clear();
        activeTab = -1;
        newTab(null, true);
    }

    private void destroyWeb(Tab t) {
        if (t.web == null) return;
        content.removeView(t.web);
        t.web.stopLoading();
        t.web.destroy();
        t.web = null;
    }

    /** Under memory pressure free every background WebView; they are rebuilt (with history) when opened again. */
    private void releaseBackgroundTabs() {
        for (Tab t : tabs) {
            if (t == cur() || t.web == null) continue;
            Bundle b = new Bundle();
            WebBackForwardList l = t.web.saveState(b);
            t.state = l == null ? null : b;
            String u = t.web.getUrl();
            if (u != null) t.url = u;
            destroyWeb(t);
        }
    }

    private void updateTabCount() { if (tabCountView != null) tabCountView.setText(String.valueOf(tabs.size())); }

    private void showHome() {
        Tab t = cur();
        if (t == null) return;
        if (t.web != null) t.web.stopLoading();
        t.home = true;
        render(t);
    }

    private void showTabsDialog() {
        String[] labels = new String[tabs.size() + 1];
        for (int i = 0; i < tabs.size(); i++) {
            Tab t = tabs.get(i);
            String name = t.home ? "নতুন ট্যাব" : (TextUtils.isEmpty(t.title) ? t.url : t.title);
            labels[i] = (i == activeTab ? "✓ " : "") + name;
        }
        labels[tabs.size()] = "＋ নতুন ট্যাব";
        final AlertDialog d = new AlertDialog.Builder(this)
                .setTitle("ট্যাব (" + tabs.size() + ") • চেপে ধরলে বন্ধ")
                .setItems(labels, (dd, which) -> { if (which == tabs.size()) newTab(null, true); else switchToTab(which); })
                .setNeutralButton("সব বন্ধ", (dd, w) -> closeAllTabs())
                .setNegativeButton("বন্ধ", null).create();
        d.show();
        d.getListView().setOnItemLongClickListener((p, v, pos, id) -> {
            if (pos < tabs.size()) { d.dismiss(); closeTab(pos); }
            return true;
        });
    }

    private void restoreTabs() {
        try {
            JSONArray a = new JSONArray(prefs.getString("tabs", "[]"));
            for (int i = 0; i < a.length() && tabs.size() < MAX_TABS; i++) {
                JSONObject o = a.optJSONObject(i);
                if (o == null) continue;
                Tab t = new Tab();
                t.url = o.optString("url", "");
                t.title = o.optString("title", "");
                t.home = t.url.isEmpty();
                tabs.add(t);
            }
            if (!tabs.isEmpty()) {                            // only the visible tab loads now; the rest wait until opened
                switchToTab(Math.max(0, Math.min(tabs.size() - 1, prefs.getInt("active_tab", 0))));
                return;
            }
        } catch (JSONException ignored) { }
        newTab(null, true);
    }

    private void saveTabs() {
        JSONArray a = new JSONArray();
        for (Tab t : tabs) {
            try {
                String u = t.home ? "" : (t.web != null && t.web.getUrl() != null ? t.web.getUrl() : t.url);
                JSONObject o = new JSONObject();
                o.put("url", u == null ? "" : u);
                o.put("title", t.title);
                a.put(o);
            } catch (JSONException ignored) { }
        }
        prefs.edit().putString("tabs", a.toString()).putInt("active_tab", Math.max(0, activeTab)).apply();
    }

    private void handleIntent(Intent i) {
        if (i == null || !Intent.ACTION_VIEW.equals(i.getAction()) || i.getData() == null) return;
        String scheme = i.getData().getScheme();
        if (scheme == null || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) return;
        String url = i.getDataString().replaceFirst("(?i)^http://", "https://");
        Tab t = cur();
        if (t == null || t.home || tabs.size() >= MAX_TABS) loadInCurrentTab(url); else newTab(url, true);
    }

    // ===================================================== WebView

    private WebView createWebView(final Tab tab) {
        WebView web = new WebView(this);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setUseWideViewPort(true);
        s.setLoadWithOverviewMode(true);
        s.setSupportZoom(true);
        s.setBuiltInZoomControls(true);
        s.setDisplayZoomControls(false);
        s.setJavaScriptCanOpenWindowsAutomatically(false);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);
        s.setCacheMode(WebSettings.LOAD_DEFAULT);             // use the HTTP cache whenever the server allows
        s.setMediaPlaybackRequiresUserGesture(true);          // no surprise autoplay downloads
        s.setLoadsImagesAutomatically(!dataSaver);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) s.setSafeBrowsingEnabled(true);
        web.setOverScrollMode(View.OVER_SCROLL_NEVER);

        web.setWebViewClient(new WebViewClient() {
            @Override public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                if (blockerEnabled && !request.isForMainFrame() && AdBlocker.shouldBlock(request.getUrl())) {
                    blockedCount.incrementAndGet();
                    return AdBlocker.emptyResponse();
                }
                return null;
            }

            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri uri = request.getUrl();
                String scheme = uri.getScheme();
                if (scheme == null) return true;
                if (scheme.equalsIgnoreCase("https")) return false;
                if (scheme.equalsIgnoreCase("http")) {         // cleartext is disabled, so upgrade to https
                    view.loadUrl(uri.buildUpon().scheme("https").build().toString());
                    return true;
                }
                if (request.hasGesture() && (scheme.equalsIgnoreCase("tel") || scheme.equalsIgnoreCase("mailto")
                        || scheme.equalsIgnoreCase("sms"))) {
                    try { startActivity(new Intent(Intent.ACTION_VIEW, uri)); } catch (ActivityNotFoundException ignored) { }
                }
                return true;
            }

            @Override public void onPageStarted(WebView view, String url, Bitmap favicon) {
                tab.url = url;
                tab.home = false;
                if (isActive(tab)) {
                    setLoading(true);
                    progressBar.setProgress(0);
                    progressBar.setVisibility(View.VISIBLE);
                    setAddress(url, false);
                    updateNav();
                }
            }

            @Override public void onPageFinished(WebView view, String url) {
                tab.url = url;
                String title = view.getTitle();
                boolean errorPage = "পেজ লোড হয়নি".equals(title);
                if (!errorPage) {
                    tab.title = TextUtils.isEmpty(title) ? url : title;
                    store.addHistory(tab.title, url);
                }
                if (isActive(tab)) {
                    setLoading(false);
                    progressBar.setVisibility(View.INVISIBLE);
                    setAddress(url, false);
                    updateNav();
                }
            }

            @Override public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                if (!request.isForMainFrame()) return;
                String failing = request.getUrl().toString();
                String html = "<html><head><meta name='viewport' content='width=device-width,initial-scale=1'>"
                        + "<title>পেজ লোড হয়নি</title></head>"
                        + "<body style='font-family:sans-serif;text-align:center;padding:64px 24px;color:#1C2A27;background:#F7FAF9'>"
                        + "<h2 style='margin:0 0 8px'>পেজ লোড হয়নি</h2>"
                        + "<p style='color:#697974;margin:0 0 28px'>ইন্টারনেট সংযোগ পরীক্ষা করে আবার চেষ্টা করুন।</p>"
                        + "<a href='" + TextUtils.htmlEncode(failing) + "' style='display:inline-block;background:#176B5B;"
                        + "color:#fff;text-decoration:none;padding:12px 28px;border-radius:24px'>আবার চেষ্টা করুন</a>"
                        + "</body></html>";
                view.loadDataWithBaseURL(failing, html, "text/html", "UTF-8", failing);
            }
        });

        web.setWebChromeClient(new WebChromeClient() {
            @Override public void onProgressChanged(WebView view, int progress) {
                if (isActive(tab)) {
                    progressBar.setProgress(progress);
                    progressBar.setVisibility(progress >= 100 ? View.INVISIBLE : View.VISIBLE);
                }
            }
        });

        web.setDownloadListener((url, userAgent, contentDisposition, mimeType, contentLength) ->
                requestDownload(url, userAgent, contentDisposition, mimeType));
        return web;
    }

    // ===================================================== address / navigation

    private void setAddress(String url, boolean force) {
        if (!force && addressBar.hasFocus()) return;           // don't overwrite what the user is typing
        String shown = url == null ? "" : (url.startsWith("https://") ? url.substring(8) : url);
        if (!shown.contentEquals(addressBar.getText())) addressBar.setText(shown);
    }

    private void setLoading(boolean value) {
        loading = value;
        reloadBtn.setImageResource(value ? R.drawable.ic_close : R.drawable.ic_refresh);
        reloadBtn.setContentDescription(value ? "থামান" : "রিলোড");
    }

    private void updateNav() {
        Tab t = cur();
        boolean page = t != null && !t.home && t.web != null;
        boolean canForward = t != null && t.web != null
                && ((!t.home && t.web.canGoForward()) || (t.home && t.web.getUrl() != null));
        backBtn.setAlpha(page ? 1f : 0.35f);
        forwardBtn.setAlpha(canForward ? 1f : 0.35f);
        siteIcon.setImageResource(page && t.url.startsWith("https://") ? R.drawable.ic_lock : R.drawable.ic_search);
    }

    private void loadAddress() {
        String input = addressBar.getText().toString().trim();
        if (input.isEmpty()) return;
        String url;
        if (input.regionMatches(true, 0, "http://", 0, 7)) url = "https://" + input.substring(7);
        else if (input.regionMatches(true, 0, "https://", 0, 8)) url = input;
        else if (DOMAIN_LIKE.matcher(input).matches()) url = "https://" + input;
        else url = "https://www.google.com/search?q=" + Uri.encode(input);
        loadInCurrentTab(url);
        InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) imm.hideSoftInputFromWindow(addressBar.getWindowToken(), 0);
        addressBar.clearFocus();
        content.requestFocus();
    }

    private void loadInCurrentTab(String url) {
        Tab t = cur();
        if (t == null) { newTab(url, true); return; }
        t.home = false;
        t.url = url;
        if (t.web == null) {                                   // render() creates the WebView and loads t.url once
            t.state = null;
            render(t);
        } else {
            render(t);
            loadUrl(t.web, url);
        }
    }

    private void focusAddressBar() {
        addressBar.requestFocus();
        InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) imm.showSoftInput(addressBar, InputMethodManager.SHOW_IMPLICIT);
    }

    private void shareCurrentPage() {
        Tab t = cur();
        String url = (t == null || t.home || t.url.isEmpty()) ? HOME_URL : t.url;
        Intent i = new Intent(Intent.ACTION_SEND);
        i.setType("text/plain");
        i.putExtra(Intent.EXTRA_TEXT, url);
        startActivity(Intent.createChooser(i, "লিংক শেয়ার করুন"));
    }

    // ===================================================== settings toggles

    private void toggleBlocker() {
        blockerEnabled = !blockerEnabled;
        prefs.edit().putBoolean("blocker_enabled", blockerEnabled).apply();
        toast(blockerEnabled ? "অ্যাড ও ট্র্যাকার ব্লকার চালু (ব্লক হয়েছে: " + blockedCount.get() + ")" : "ব্লকার বন্ধ করা হয়েছে");
        reloadCurrent();
    }

    private void toggleDataSaver() {
        dataSaver = !dataSaver;
        prefs.edit().putBoolean("data_saver", dataSaver).apply();
        for (Tab t : tabs) if (t.web != null) t.web.getSettings().setLoadsImagesAutomatically(!dataSaver);
        toast(dataSaver ? "ডেটা সেভার চালু: ছবি লোড হবে না" : "ডেটা সেভার বন্ধ");
        reloadCurrent();
    }

    private void reloadCurrent() {
        Tab t = cur();
        if (t != null && !t.home && t.web != null && t.web.getUrl() != null) t.web.reload();
    }

    private void confirmClearData() {
        new AlertDialog.Builder(this).setTitle("ক্যাশ ও কুকি মুছবেন?")
                .setMessage("সাইটের সংরক্ষিত ডেটা মুছে যাবে এবং লগইন করা সাইট থেকে সাইন-আউট হতে পারেন। হিস্টোরি ও বুকমার্ক থাকবে।")
                .setNegativeButton("না", null)
                .setPositiveButton("মুছুন", (d, w) -> {
                    for (Tab t : tabs) if (t.web != null) t.web.clearCache(true);
                    CookieManager.getInstance().removeAllCookies(null);
                    WebStorage.getInstance().deleteAllData();
                    toast("ক্যাশ ও কুকি মুছে ফেলা হয়েছে");
                }).show();
    }

    // ===================================================== bookmarks / history

    private void addBookmark() {
        Tab t = cur();
        if (t == null || t.home || t.url.isEmpty()) { toast("আগে একটি ওয়েবসাইট খুলুন"); return; }
        String title = TextUtils.isEmpty(t.title) ? t.url : t.title;
        toast(store.addBookmark(title, t.url) ? "বুকমার্কে সংরক্ষণ হয়েছে" : "এটি আগে থেকেই বুকমার্কে আছে");
    }

    private void showSavedList(final boolean bookmarks) {
        final String title = bookmarks ? "বুকমার্ক" : "হিস্টোরি";
        final List<Store.Item> items = bookmarks ? store.bookmarks() : store.history();
        if (items.isEmpty()) {
            AlertDialog.Builder b = new AlertDialog.Builder(this).setTitle(title)
                    .setMessage(bookmarks ? "এখনো কোনো বুকমার্ক নেই।" : "এখনো কোনো হিস্টোরি নেই।")
                    .setNegativeButton("বন্ধ", null);
            if (bookmarks) b.setPositiveButton("এই পেজ যোগ করুন", (d, w) -> addBookmark());
            b.show();
            return;
        }
        String[] labels = new String[items.size()];
        for (int i = 0; i < labels.length; i++) {
            Store.Item it = items.get(i);
            labels[i] = (TextUtils.isEmpty(it.title) ? it.url : it.title) + "\n" + it.url;
        }
        AlertDialog.Builder b = new AlertDialog.Builder(this).setTitle(title + " • চেপে ধরলে মুছুন")
                .setItems(labels, (d, which) -> loadInCurrentTab(items.get(which).url))
                .setNeutralButton("সব মুছুন", (d, w) -> confirmClearList(bookmarks))
                .setNegativeButton("বন্ধ", null);
        if (bookmarks) b.setPositiveButton("এই পেজ যোগ করুন", (d, w) -> addBookmark());
        final AlertDialog dialog = b.create();
        dialog.show();
        dialog.getListView().setOnItemLongClickListener((p, v, pos, id) -> {
            String url = items.get(pos).url;
            if (bookmarks) store.removeBookmark(url); else store.removeHistory(url);
            dialog.dismiss();
            toast("মুছে ফেলা হয়েছে");
            return true;
        });
    }

    private void confirmClearList(final boolean bookmarks) {
        new AlertDialog.Builder(this).setMessage("সব তথ্য মুছে ফেলবেন?")
                .setNegativeButton("না", null)
                .setPositiveButton("মুছুন", (d, w) -> { if (bookmarks) store.clearBookmarks(); else store.clearHistory(); })
                .show();
    }

    // ===================================================== downloads

    private void requestDownload(String url, String userAgent, String contentDisposition, String mimeType) {
        if (url == null || !(url.startsWith("https://") || url.startsWith("http://"))) {
            toast("এই ধরনের লিংক ডাউনলোড করা যায় না");
            return;
        }
        if (Build.VERSION.SDK_INT < 29 && checkSelfPermission(PERM_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            pendingDownload = new String[]{url, userAgent, contentDisposition, mimeType};
            requestPermissions(new String[]{PERM_STORAGE}, REQ_STORAGE);
            return;
        }
        Tab t = cur();
        String referer = t != null && !t.home ? t.url : null;
        beginDownload(url, userAgent, CookieManager.getInstance().getCookie(url), referer, mimeType,
                URLUtil.guessFileName(url, contentDisposition, mimeType));
    }

    private void beginDownload(String url, String ua, String cookie, String referer, String mime, String name) {
        Intent i = new Intent(this, DownloadService.class).setAction(DownloadService.ACTION_START)
                .putExtra("url", url).putExtra("ua", ua).putExtra("cookie", cookie)
                .putExtra("referer", referer).putExtra("mime", mime).putExtra("name", name);
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(i); else startService(i);
        toast("ডাউনলোড শুরু হয়েছে: " + name);
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(PERM_NOTIF) != PackageManager.PERMISSION_GRANTED
                && !prefs.getBoolean("asked_notif", false)) {
            prefs.edit().putBoolean("asked_notif", true).apply();
            requestPermissions(new String[]{PERM_NOTIF}, REQ_NOTIF);
        }
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQ_STORAGE) return;
        String[] p = pendingDownload;
        pendingDownload = null;
        if (p == null) return;
        if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) requestDownload(p[0], p[1], p[2], p[3]);
        else toast("ডাউনলোড করতে স্টোরেজ অনুমতি দরকার");
    }

    private void showDownloads() {
        final List<DownloadService.Task> all = DownloadService.tasks();
        if (all.isEmpty()) {
            new AlertDialog.Builder(this).setTitle("ডাউনলোড").setMessage("এখনো কোনো ডাউনলোড নেই।")
                    .setPositiveButton("Downloads ফোল্ডার", (d, w) -> openSystemDownloads())
                    .setNegativeButton("বন্ধ", null).show();
            return;
        }
        final ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, new ArrayList<String>());
        final List<DownloadService.Task> shown = new ArrayList<>();
        final Handler h = new Handler(Looper.getMainLooper());
        final Runnable[] refresh = new Runnable[1];
        refresh[0] = () -> {                                   // live progress while the dialog is open
            shown.clear();
            adapter.clear();
            List<DownloadService.Task> snap = new ArrayList<>(DownloadService.tasks());
            Collections.reverse(snap);
            for (DownloadService.Task t : snap) { shown.add(t); adapter.add(DownloadService.describe(this, t)); }
            adapter.notifyDataSetChanged();
            h.postDelayed(refresh[0], 800);
        };
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("ডাউনলোড")
                .setAdapter(adapter, (d, which) -> { if (which < shown.size()) showTaskActions(shown.get(which)); })
                .setPositiveButton("Downloads ফোল্ডার", (d, w) -> openSystemDownloads())
                .setNegativeButton("বন্ধ", null).create();
        dialog.setOnDismissListener(d -> h.removeCallbacks(refresh[0]));
        dialog.show();
        refresh[0].run();
    }

    private void showTaskActions(final DownloadService.Task t) {
        final String[] options;
        if (t.status == DownloadService.DONE) options = new String[]{"ফাইল খুলুন", "তালিকা থেকে সরান"};
        else if (t.status == DownloadService.FAILED || t.status == DownloadService.CANCELED) options = new String[]{"আবার চেষ্টা করুন", "তালিকা থেকে সরান"};
        else options = new String[]{"বাতিল করুন"};
        new AlertDialog.Builder(this).setTitle(t.name).setItems(options, (d, which) -> {
            String choice = options[which];
            if (choice.equals("ফাইল খুলুন")) {
                openDownloaded(t);
            } else if (choice.equals("আবার চেষ্টা করুন")) {
                DownloadService.remove(t);
                beginDownload(t.url, t.userAgent, t.cookie, t.referer, t.mime, t.name);
            } else if (choice.equals("বাতিল করুন")) {
                t.cancel();
            } else {
                DownloadService.remove(t);
            }
        }).setNegativeButton("বন্ধ", null).show();
    }

    private void openDownloaded(DownloadService.Task t) {
        if (t.savedUri == null) { openSystemDownloads(); return; }
        try {
            Intent i = new Intent(Intent.ACTION_VIEW);
            i.setDataAndType(t.savedUri, TextUtils.isEmpty(t.mime) ? "*/*" : t.mime);
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(i);
        } catch (ActivityNotFoundException e) {
            toast("এই ফাইল খোলার মতো অ্যাপ পাওয়া যায়নি");
        }
    }

    private void openSystemDownloads() {
        try { startActivity(new Intent(DownloadManager.ACTION_VIEW_DOWNLOADS)); }
        catch (Exception e) { toast("Downloads খুলতে পারিনি"); }
    }

    // ===================================================== small helpers

    private void toast(String msg) { Toast.makeText(this, msg, Toast.LENGTH_SHORT).show(); }

    private TextView text(String value, int size, int color) {
        TextView v = new TextView(this);
        v.setText(value);
        v.setTextSize(size);
        v.setTextColor(color);
        return v;
    }

    private int rippleRes() {
        TypedValue tv = new TypedValue();
        getTheme().resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, tv, true);
        return tv.resourceId;
    }

    private ImageButton iconButton(int res, String description) {
        ImageButton b = new ImageButton(this);
        b.setImageResource(res);
        b.setContentDescription(description);
        b.setBackgroundResource(rippleRes());
        b.setScaleType(ImageView.ScaleType.CENTER);
        return b;
    }

    private GradientDrawable roundRect(int color, int radius, int stroke) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(radius);
        if (stroke != Color.TRANSPARENT) d.setStroke(dp(1), stroke);
        return d;
    }

    private int dp(int n) { return (int) (n * getResources().getDisplayMetrics().density + 0.5f); }
}
