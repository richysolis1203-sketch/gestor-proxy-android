package com.gestorproxy.core;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/** Lista básica de dominios de rastreo (publicidad, analítica, grabación de sesiones). Generada a partir de la versión de escritorio. */
public final class TrackerList {
    private TrackerList() {}

    private static final String[] DOMAINS = {
        "google-analytics.com", "analytics.google.com", "googletagmanager.com", "googletagservices.com",
        "googleadservices.com", "doubleclick.net", "googlesyndication.com", "adservice.google.com",
        "app-measurement.com", "ads.google.com", "pixel.facebook.com", "an.facebook.com",
        "analytics.twitter.com", "static.ads-twitter.com", "ads-api.twitter.com", "ads.twitter.com",
        "ads.linkedin.com", "px.ads.linkedin.com", "snap.licdn.com", "analytics.tiktok.com",
        "analytics-sg.tiktok.com", "ads.tiktok.com", "business-api.tiktok.com", "ct.pinterest.com",
        "trk.pinterest.com", "log.pinterest.com", "tr.snapchat.com", "sc-static.net", "ads.reddit.com",
        "alb.reddit.com", "events.redditmedia.com", "bat.bing.com", "clarity.ms", "ads.yahoo.com",
        "analytics.yahoo.com", "advertising.com", "adtechus.com", "adnxs.com", "adsrvr.org",
        "rubiconproject.com", "pubmatic.com", "openx.net", "casalemedia.com", "criteo.com", "criteo.net",
        "taboola.com", "outbrain.com", "amazon-adsystem.com", "adform.net", "smartadserver.com",
        "bidswitch.net", "contextweb.com", "3lift.com", "sharethrough.com", "indexww.com", "yieldmo.com",
        "teads.tv", "sovrn.com", "lijit.com", "media.net", "moatads.com", "adsafeprotected.com",
        "doubleverify.com", "serving-sys.com", "eyeota.net", "tapad.com", "rlcdn.com", "liveramp.com",
        "agkn.com", "mathtag.com", "mookie1.com", "yieldlab.net", "improvedigital.com", "gumgum.com",
        "triplelift.com", "33across.com", "spotxchange.com", "springserve.com", "rhythmone.com",
        "undertone.com", "adroll.com", "perfectaudience.com", "rtbhouse.com", "bidr.io", "adition.com",
        "mgid.com", "revcontent.com", "zedo.com", "adblade.com", "adcash.com", "exoclick.com",
        "trafficjunky.net", "juicyads.com", "popads.net", "propellerads.com", "adscale.de", "adkernel.com",
        "smaato.net", "inmobi.com", "adcolony.com", "applovin.com", "unityads.unity3d.com", "vungle.com",
        "chartboost.com", "ironsrc.com", "bluekai.com", "krxd.net", "demdex.net", "everesttech.net",
        "omtrdc.net", "2o7.net", "exelator.com", "addthis.com", "sharethis.com", "quantserve.com",
        "quantcount.com", "scorecardresearch.com", "imrworldwide.com", "chartbeat.com", "chartbeat.net",
        "parsely.com", "permutive.com", "permutive.app", "lotame.com", "crwdcntrl.net", "nexac.com",
        "turn.com", "zemanta.com", "onaudience.com", "hotjar.com", "hotjar.io", "mouseflow.com",
        "crazyegg.com", "fullstory.com", "logrocket.com", "lr-ingest.io", "logrocket.io", "smartlook.com",
        "inspectlet.com", "luckyorange.com", "luckyorange.net", "quantummetric.com", "contentsquare.net",
        "contentsquare.com", "clicktale.net", "heapanalytics.com", "heap.io", "mixpanel.com", "mxpnl.com",
        "amplitude.com", "segment.io", "segment.com", "kissmetrics.com", "kissmetrics.io", "optimizely.com",
        "vwo.com", "visualwebsiteoptimizer.com", "pendo.io", "bam.nr-data.net", "js-agent.newrelic.com",
        "statcounter.com", "histats.com", "clicky.com", "getclicky.com", "woopra.com", "gauges.com",
        "static.cloudflareinsights.com", "cloudflareinsights.com", "hs-analytics.net", "hs-scripts.com",
        "hsadspixel.net", "marketo.net", "mktoresp.com", "pardot.com", "bizible.com", "bounceexchange.com",
        "bouncex.net", "track.hubspot.com", "appsflyer.com", "adjust.com", "branch.io", "kochava.com",
        "singular.net", "adobedtm.com", "tiqcdn.com", "tealiumiq.com", "ensighten.com", "mc.yandex.ru",
        "mc.yandex.com", "an.yandex.ru", "hm.baidu.com", "tongji.baidu.com", "cnzz.com", "umeng.com",
        "51.la",
    };

    // host, prefijo de ruta (o null), texto que debe contener la ruta (o null)
    private static final String[][] PATH_RULES = {
        {"connect.facebook.net", null, "/fbevents.js"},
        {"connect.facebook.net", null, "/signals/"},
        {"facebook.com", "/tr", null},
        {"www.google.com", "/pagead/", null},
        {"www.google.com", "/ads/", null},
        {"www.linkedin.com", "/px/", null},
    };

    private static final Set<String> SET = new HashSet<String>();

    static {
        for (String d : DOMAINS) SET.add(d);
    }

    public static int size() {
        return SET.size();
    }

    /** ¿El nombre de host (o alguno de sus dominios superiores) está en la lista? */
    public static boolean isTrackerHost(String hostname) {
        if (hostname == null) return false;
        String host = hostname.trim().toLowerCase(Locale.ROOT);
        if (host.endsWith(".")) host = host.substring(0, host.length() - 1);
        if (host.isEmpty()) return false;
        int start = 0;
        while (true) {
            int dot = host.indexOf('.', start);
            if (dot < 0) return false; // no se prueba la última etiqueta sola
            if (SET.contains(host.substring(start))) return true;
            start = dot + 1;
        }
    }

    /** Nombre de host de una URL (sin usuario, sin puerto, en minúsculas) o null. */
    static String hostOf(String url) {
        if (url == null) return null;
        int s = url.indexOf("://");
        if (s < 0) return null;
        int begin = s + 3;
        int end = url.length();
        for (int i = begin; i < url.length(); i++) {
            char c = url.charAt(i);
            if (c == '/' || c == '?' || c == '#') {
                end = i;
                break;
            }
        }
        String auth = url.substring(begin, end);
        int at = auth.lastIndexOf('@');
        if (at >= 0) auth = auth.substring(at + 1);
        if (auth.startsWith("[")) {
            int close = auth.indexOf(']');
            return close < 0 ? null : auth.substring(1, close).toLowerCase(Locale.ROOT);
        }
        int colon = auth.indexOf(':');
        if (colon >= 0) auth = auth.substring(0, colon);
        return auth.isEmpty() ? null : auth.toLowerCase(Locale.ROOT);
    }

    /** Ruta (sin consulta ni fragmento) de una URL; "/" si no tiene. */
    static String pathOf(String url) {
        int s = url.indexOf("://");
        if (s < 0) return "/";
        int i = s + 3;
        while (i < url.length() && url.charAt(i) != '/' && url.charAt(i) != '?' && url.charAt(i) != '#') i++;
        if (i >= url.length() || url.charAt(i) != '/') return "/";
        int j = i;
        while (j < url.length() && url.charAt(j) != '?' && url.charAt(j) != '#') j++;
        return url.substring(i, j);
    }

    private static boolean pathMatches(String path, String prefix, String includes) {
        if (prefix != null) {
            if (prefix.endsWith("/")) return path.startsWith(prefix);
            return path.equals(prefix) || path.startsWith(prefix + "/");
        }
        if (includes != null) return path.contains(includes);
        return false;
    }

    /** ¿Esta URL completa es de un rastreador conocido (por dominio o por ruta)? */
    public static boolean isTrackerUrl(String url) {
        String host = hostOf(url);
        if (host == null) return false;
        if (isTrackerHost(host)) return true;
        String path = null;
        for (String[] r : PATH_RULES) {
            if (host.equals(r[0]) || host.endsWith("." + r[0])) {
                if (path == null) path = pathOf(url);
                if (pathMatches(path, r[1], r[2])) return true;
            }
        }
        return false;
    }
}
