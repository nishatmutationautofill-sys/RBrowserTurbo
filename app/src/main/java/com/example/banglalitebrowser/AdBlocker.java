package com.example.banglalitebrowser;

import android.net.Uri;
import android.webkit.WebResourceResponse;

import java.io.ByteArrayInputStream;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Very fast ad/tracker filter.
 * The check runs on WebView's network thread for EVERY sub-request, so it must be cheap:
 * one HashSet lookup per host label (no regex, no loops over the whole list).
 * This is a lightweight built-in list, not a full EasyList engine.
 */
final class AdBlocker {
    private AdBlocker() {}

    private static final Set<String> HOSTS = new HashSet<>(Arrays.asList(
        // Ad networks
        "doubleclick.net", "googlesyndication.com", "googleadservices.com", "adservice.google.com",
        "2mdn.net", "adnxs.com", "adsrvr.org", "taboola.com", "outbrain.com", "criteo.com",
        "criteo.net", "pubmatic.com", "openx.net", "rubiconproject.com", "smartadserver.com",
        "adform.net", "advertising.com", "amazon-adsystem.com", "casalemedia.com", "contextweb.com",
        "lijit.com", "sovrn.com", "33across.com", "bidswitch.net", "teads.tv", "mgid.com",
        "revcontent.com", "zemanta.com", "moatads.com", "adsafeprotected.com", "serving-sys.com",
        "adcolony.com", "yieldmo.com", "sharethrough.com", "media.net", "popads.net",
        "propellerads.com", "exoclick.com", "adsterra.com", "juicyads.com", "trafficjunky.net",
        "ads.yahoo.com", "ads.twitter.com", "static.ads-twitter.com", "ct.pinterest.com",
        // Trackers / analytics
        "scorecardresearch.com", "pixel.facebook.com", "connect.facebook.net", "analytics.google.com",
        "google-analytics.com", "hotjar.com", "hotjar.io", "clarity.ms", "segment.io", "segment.com",
        "mixpanel.com", "amplitude.com", "fullstory.com", "mouseflow.com", "crazyegg.com",
        "analytics.tiktok.com", "snap.licdn.com", "px.ads.linkedin.com", "bat.bing.com",
        "analytics.twitter.com", "quantserve.com", "demdex.net", "omtrdc.net", "branch.io",
        "app-measurement.com", "appsflyer.com", "krxd.net", "bluekai.com", "rlcdn.com",
        "tapad.com", "everesttech.net", "chartbeat.com", "chartbeat.net"
    ));

    /** True if this SUB-resource request should be dropped. Never call for main-frame navigations. */
    static boolean shouldBlock(Uri uri) {
        if (uri == null) return false;
        String host = uri.getHost();
        if (host == null) return false;
        host = host.toLowerCase(Locale.ROOT);

        // Check the full host, then each parent domain: a.b.c.com -> b.c.com -> c.com -> com
        int i = 0;
        while (true) {
            if (HOSTS.contains(i == 0 ? host : host.substring(i))) return true;
            int dot = host.indexOf('.', i);
            if (dot < 0) break;
            i = dot + 1;
        }

        String path = uri.getPath();
        if (path == null || path.length() < 3) return false;
        path = path.toLowerCase(Locale.ROOT);
        if (path.contains("/ads/") || path.contains("/ad/") || path.contains("/adserver/")
                || path.contains("/adservice/") || path.contains("/advertising/")
                || path.contains("/tracking-pixel/") || path.endsWith("/pixel.gif")
                || path.endsWith("/beacon.gif") || path.endsWith("/g/collect")) return true;
        if (path.endsWith("/collect")) {
            String q = uri.getEncodedQuery();
            if (q != null && q.startsWith("v=")) return true;          // Google Analytics hit
        }
        return host.endsWith("facebook.com") && (path.equals("/tr") || path.equals("/tr/")); // FB pixel
    }

    /** Instant empty answer so the page doesn't wait on a blocked request. */
    static WebResourceResponse emptyResponse() {
        return new WebResourceResponse("text/plain", "UTF-8", 204, "No Content",
                Collections.<String, String>emptyMap(), new ByteArrayInputStream(new byte[0]));
    }
}
