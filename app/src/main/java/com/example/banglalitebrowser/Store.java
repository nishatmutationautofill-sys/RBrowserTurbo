package com.example.banglalitebrowser;

import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * History + bookmarks. Kept in memory, written to SharedPreferences on a background
 * thread, so finishing a page never blocks the UI thread with JSON work.
 */
final class Store {
    static final class Item {
        final String title, url;
        final long time;
        Item(String title, String url, long time) { this.title = title; this.url = url; this.time = time; }
    }

    private static final int MAX_HISTORY = 200, MAX_BOOKMARKS = 300;
    private static final String K_HISTORY = "history", K_BOOKMARKS = "bookmarks";

    private final SharedPreferences prefs;
    private final ExecutorService io = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "store-io");
        t.setDaemon(true);
        return t;
    });
    private List<Item> history, bookmarks;

    Store(SharedPreferences prefs) { this.prefs = prefs; }

    // ---- history ----
    synchronized List<Item> history() { return new ArrayList<>(hist()); }

    synchronized void addHistory(String title, String url) {
        if (url == null || url.isEmpty() || url.startsWith("about:") || url.startsWith("data:")) return;
        List<Item> l = hist();
        if (!l.isEmpty() && l.get(0).url.equals(url)) return;               // same page, nothing to do
        removeByUrl(l, url);
        l.add(0, new Item(title == null || title.isEmpty() ? url : title, url, System.currentTimeMillis()));
        while (l.size() > MAX_HISTORY) l.remove(l.size() - 1);
        persist(K_HISTORY, l);
    }

    synchronized void removeHistory(String url) { removeByUrl(hist(), url); persist(K_HISTORY, hist()); }
    synchronized void clearHistory() { hist().clear(); persist(K_HISTORY, hist()); }

    // ---- bookmarks ----
    synchronized List<Item> bookmarks() { return new ArrayList<>(marks()); }

    /** @return false if it was already bookmarked */
    synchronized boolean addBookmark(String title, String url) {
        List<Item> l = marks();
        for (Item i : l) if (i.url.equals(url)) return false;
        l.add(0, new Item(title == null || title.isEmpty() ? url : title, url, System.currentTimeMillis()));
        while (l.size() > MAX_BOOKMARKS) l.remove(l.size() - 1);
        persist(K_BOOKMARKS, l);
        return true;
    }

    synchronized void removeBookmark(String url) { removeByUrl(marks(), url); persist(K_BOOKMARKS, marks()); }
    synchronized void clearBookmarks() { marks().clear(); persist(K_BOOKMARKS, marks()); }

    // ---- internals ----
    private List<Item> hist() { if (history == null) history = parse(prefs.getString(K_HISTORY, "[]")); return history; }
    private List<Item> marks() { if (bookmarks == null) bookmarks = parse(prefs.getString(K_BOOKMARKS, "[]")); return bookmarks; }

    private static void removeByUrl(List<Item> l, String url) {
        for (int i = l.size() - 1; i >= 0; i--) if (l.get(i).url.equals(url)) l.remove(i);
    }

    private static List<Item> parse(String json) {
        List<Item> out = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(json);
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.optJSONObject(i);
                if (o == null) continue;
                String url = o.optString("url", "");
                if (!url.isEmpty()) out.add(new Item(o.optString("title", url), url, o.optLong("time", 0)));
            }
        } catch (JSONException ignored) { }
        return out;
    }

    private void persist(final String key, List<Item> list) {
        final List<Item> snapshot = new ArrayList<>(list);                   // cheap copy; JSON is built off the UI thread
        io.execute(() -> {
            JSONArray a = new JSONArray();
            try {
                for (Item i : snapshot) {
                    JSONObject o = new JSONObject();
                    o.put("title", i.title);
                    o.put("url", i.url);
                    o.put("time", i.time);
                    a.put(o);
                }
            } catch (JSONException ignored) { }
            prefs.edit().putString(key, a.toString()).apply();
        });
    }
}
