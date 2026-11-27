package nl.wapnet.outlookwrapper;

import java.io.UnsupportedEncodingException;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.util.Locale;
import java.util.Set;

/** Which URLs stay in the app, which go to Android, and mailto: to an Outlook draft. */
final class Links {
    static final String START_URL = "https://outlook.office.com/mail/";
    private static final String COMPOSE_URL = "https://outlook.office.com/mail/deeplink/compose";
    private static final Set<String> APP_HOSTS = Set.of(
            "outlook.office.com", "outlook.office365.com", "outlook.cloud.microsoft", "outlook.live.com",
            // Microsoft sign-in may open in a new window; it must stay in-app to keep the session.
            "login.microsoftonline.com", "login.microsoft.com", "login.live.com", "login.windows.net");
    private static final Set<String> EXTERNAL_SCHEMES = Set.of("http", "https", "mailto", "tel");
    private static final Set<String> MAILTO_FIELDS = Set.of("cc", "bcc", "subject", "body");

    /** New windows on Outlook or Microsoft sign-in open in the app; everything else does not. */
    static boolean opensInApp(String url) {
        try {
            URI uri = new URI(url);
            String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
            return "https".equals(uri.getScheme()) && uri.getRawUserInfo() == null
                    && (uri.getPort() == -1 || uri.getPort() == 443) && APP_HOSTS.contains(host);
        } catch (Exception e) {
            return false;
        }
    }

    /** HTTPS origins of Outlook itself (not sign-in) for the page bridge. */
    static Set<String> appOrigins() {
        Set<String> origins = new java.util.HashSet<>();
        for (String host : APP_HOSTS) {
            if (host.startsWith("outlook.")) {
                origins.add("https://" + host);
            }
        }
        return origins;
    }

    /** Schemes safe to hand to Android (browser, mail, dialer). intent:, file:, javascript: etc. are not. */
    static boolean opensExternally(String url) {
        try {
            String scheme = new URI(url).getScheme();
            return scheme != null && EXTERNAL_SCHEMES.contains(scheme.toLowerCase(Locale.ROOT));
        } catch (Exception e) {
            return false;
        }
    }

    static boolean isMailto(String url) {
        return url != null && url.regionMatches(true, 0, "mailto:", 0, 7);
    }

    /** Outlook compose URL for a mailto: link (RFC 6068 to/cc/bcc/subject/body), or null. */
    static String compose(String mailto) {
        if (!isMailto(mailto)) {
            return null;
        }
        String rest = mailto.substring(7);
        int q = rest.indexOf('?');
        StringBuilder url = new StringBuilder(COMPOSE_URL);
        char sep = '?';
        try {
            String to = decode(q < 0 ? rest : rest.substring(0, q));
            if (!to.isEmpty()) {
                url.append(sep).append("to=").append(encode(to));
                sep = '&';
            }
            if (q >= 0) {
                for (String pair : rest.substring(q + 1).split("&")) {
                    String[] kv = pair.split("=", 2);
                    String key = decode(kv[0]).toLowerCase(Locale.ROOT);
                    if (kv.length == 2 && MAILTO_FIELDS.contains(key)) {
                        url.append(sep).append(key).append('=').append(encode(decode(kv[1])));
                        sep = '&';
                    }
                }
            }
        } catch (IllegalArgumentException | UnsupportedEncodingException e) {
            return null;
        }
        return url.toString();
    }

    // String charset overloads: the Charset ones need API 33.
    private static String decode(String s) throws UnsupportedEncodingException {
        // mailto: uses %20 for spaces; a literal + is a plus (e.g. user+tag@example.com).
        return URLDecoder.decode(s.replace("+", "%2B"), "UTF-8");
    }

    private static String encode(String s) throws UnsupportedEncodingException {
        return URLEncoder.encode(s, "UTF-8").replace("+", "%20");
    }

    public static void main(String[] args) {
        assert opensInApp("https://outlook.office.com/mail/");
        assert opensInApp("https://OUTLOOK.cloud.microsoft:443/mail/");
        assert opensInApp("https://login.microsoftonline.com/common/oauth2");
        assert !opensInApp("http://outlook.office.com/");
        assert !opensInApp("https://outlook.office.com.evil.example/");
        assert !opensInApp("https://x@outlook.office.com/");
        assert !opensInApp("https://outlook.office.com:8443/");
        assert !opensInApp("https://example.com/?outlook.office.com");
        assert appOrigins().contains("https://outlook.office.com") && appOrigins().size() == 4;
        assert opensExternally("https://example.com/") && opensExternally("tel:123");
        assert !opensExternally("intent://x#Intent;end") && !opensExternally("javascript:alert(1)");
        assert (COMPOSE_URL + "?to=a%2Btag%40example.com&subject=Hi%20there&body=a%26b")
                .equals(compose("mailto:a+tag@example.com?subject=Hi%20there&body=a%26b"));
        assert (COMPOSE_URL + "?to=a%40x.nl%2Cb%40y.nl&cc=c%40z.nl")
                .equals(compose("MAILTO:a@x.nl,b@y.nl?cc=c@z.nl&evil=1"));
        assert (COMPOSE_URL + "?subject=x").equals(compose("mailto:?subject=x"));
        assert compose("mailto:a@b.c?subject=%zz") == null;
        assert compose("https://outlook.office.com/") == null;
        assert compose(null) == null;
    }

    private Links() {}
}
