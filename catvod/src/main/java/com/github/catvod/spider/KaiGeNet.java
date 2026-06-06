package com.github.catvod.spider;

import com.github.catvod.net.OkHttp;
import com.github.catvod.net.OkResult;
import android.text.TextUtils;
import java.net.URL;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import okhttp3.FormBody;
import okhttp3.Headers;
import okhttp3.MediaType;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

public class KaiGeNet {

    private static final Map<String, String> cookieJar = new ConcurrentHashMap<>();
    private static final String MOBILE_UA = "Mozilla/5.0 (Linux; Android 12; Pixel 6) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/121.0.6167.178 Mobile Safari/537.36";

    public static OkResult smartRequest(String siteUrl, String method, String url, String body, Map<String, String> headers) {
        String host = getHost(url);
        if (headers == null) headers = new HashMap<>();

        if (!headers.containsKey("User-Agent")) headers.put("User-Agent", MOBILE_UA);

        if (!headers.containsKey("Referer")) {
            if (!TextUtils.isEmpty(siteUrl) && siteUrl.matches("^[\\x00-\\x7F]*$")) {
                headers.put("Referer", siteUrl);
            } else {
                headers.put("Referer", getHost(siteUrl) + "/");
            }
        }

        if (cookieJar.containsKey(host)) {
            headers.put("Cookie", cookieJar.get(host));
        }

        OkResult res = execute(method, url, body, headers);

        String setCookie = getSetCookie(res.getResp());
        if (!TextUtils.isEmpty(setCookie)) {
            String existCookie = cookieJar.containsKey(host) ? cookieJar.get(host) : "";
            String mergedCookie = mergeCookies(existCookie, setCookie);
            cookieJar.put(host, mergedCookie);

            String bodyStr = res.getBody() == null ? "" : res.getBody().trim();
            if (bodyStr.contains("cdndefend_js_cookie")) {
                String jsCookie = cdnDefendCookie(bodyStr);
                if (!TextUtils.isEmpty(jsCookie)) {
                    mergedCookie = mergeCookies(mergedCookie, jsCookie);
                    cookieJar.put(host, mergedCookie);
                    headers.put("Cookie", mergedCookie);
                    res = execute(method, url, body, headers);
                    String thirdCookie = getSetCookie(res.getResp());
                    if (!TextUtils.isEmpty(thirdCookie)) {
                        cookieJar.put(host, mergeCookies(mergedCookie, thirdCookie));
                    }
                }
            }
        }

        return res;
    }

    private static OkResult execute(String method, String url, String body, Map<String, String> headers) {
        method = (method == null) ? "get" : method.toLowerCase();
        try {
            Request request;
            Headers okHeaders = Headers.of(headers);
            if ("post".equals(method)) {
                RequestBody requestBody;
                if (!TextUtils.isEmpty(body) && body.trim().startsWith("{")) {
                    requestBody = RequestBody.create(body, MediaType.parse("application/json; charset=utf-8"));
                } else {
                    FormBody.Builder fb = new FormBody.Builder();
                    Map<String, String> params = parseToMap(body);
                    for (Map.Entry<String, String> e : params.entrySet()) fb.add(e.getKey(), e.getValue());
                    requestBody = fb.build();
                }
                request = new Request.Builder().url(url).headers(okHeaders).post(requestBody).build();
            } else {
                request = new Request.Builder().url(url).headers(okHeaders).build();
            }
            try (Response response = OkHttp.client().newCall(request).execute()) {
                Map<String, List<String>> respHeaders = response.headers().toMultimap();
                String respBody = response.body() != null ? response.body().string() : "";
                return new OkResult(respBody, respHeaders, response.code());
            }
        } catch (Exception e) {
            return new OkResult("", null, 0);
        }
    }

    // ✅ 新增：获取重定向 Location header（供 KG.java 调用）
    public static Map<String, List<String>> getLocationHeader(String url, Map<String, String> headers) {
        try {
            Headers okHeaders = headers != null ? Headers.of(headers) : Headers.of(new HashMap<>());
            Request request = new Request.Builder().url(url).headers(okHeaders).build();
            try (Response response = OkHttp.noRedirect().newCall(request).execute()) {
                return response.headers().toMultimap();
            }
        } catch (Exception e) {
            return null;
        }
    }

    // ✅ 新增：从响应头里提取 Location 值（供 KG.java 调用）
    public static String getLocation(Map<String, List<String>> respHeaders) {
        if (respHeaders == null) return "";
        List<String> loc = respHeaders.get("Location");
        if (loc == null) loc = respHeaders.get("location");
        return (loc != null && !loc.isEmpty()) ? loc.get(0) : "";
    }

    private static String getSetCookie(Map<String, List<String>> respHeaders) {
        if (respHeaders == null) return "";
        List<String> cookies = respHeaders.get("Set-Cookie");
        if (cookies == null) cookies = respHeaders.get("set-cookie");
        if (cookies != null && !cookies.isEmpty()) {
            StringBuilder sb = new StringBuilder();
            for (String c : cookies) {
                String part = c.split(";")[0].trim();
                if (sb.length() > 0) sb.append("; ");
                sb.append(part);
            }
            return sb.toString();
        }
        return "";
    }

    private static String getHost(String urlStr) {
        if (TextUtils.isEmpty(urlStr)) return "";
        try {
            return new URL(urlStr).getHost();
        } catch (Exception e) {
            try {
                return java.net.URI.create(urlStr).getHost();
            } catch (Exception ex) {
                return urlStr;
            }
        }
    }

    private static Map<String, String> parseToMap(String body) {
        Map<String, String> map = new HashMap<>();
        if (TextUtils.isEmpty(body)) return map;
        try {
            for (String pair : body.split("&")) {
                String[] kv = pair.split("=", 2);
                if (kv.length == 2) map.put(kv[0], kv[1]);
            }
        } catch (Exception ignored) {}
        return map;
    }

    private static String mergeCookies(String oldCookie, String newCookie) {
        if (TextUtils.isEmpty(oldCookie)) return newCookie;
        if (TextUtils.isEmpty(newCookie)) return oldCookie;
        Map<String, String> cookieMap = new java.util.LinkedHashMap<>();
        for (String part : oldCookie.split(";")) {
            String[] kv = part.trim().split("=", 2);
            if (kv.length == 2) cookieMap.put(kv[0].trim(), kv[1].trim());
        }
        for (String part : newCookie.split(";")) {
            String[] kv = part.trim().split("=", 2);
            if (kv.length == 2) cookieMap.put(kv[0].trim(), kv[1].trim());
        }
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> entry : cookieMap.entrySet()) {
            if (sb.length() > 0) sb.append("; ");
            sb.append(entry.getKey()).append("=").append(entry.getValue());
        }
        return sb.toString();
    }

    public static void putCookie(String url, String cookie) {
        String host = getHost(url);
        if (!TextUtils.isEmpty(host) && !TextUtils.isEmpty(cookie)) {
            String existing = cookieJar.containsKey(host) ? cookieJar.get(host) : "";
            cookieJar.put(host, mergeCookies(existing, cookie));
        }
    }

    public static String cdnDefendCookie(String html) {
        try {
            java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("const a0_0x2a54=\\['([A-F0-9]+)'")
                .matcher(html);
            if (!m.find()) return "";
            String c = m.group(1);
            int n1 = Integer.parseInt(String.valueOf(c.charAt(0)), 16);
            for (int i = 0; i < 99999; i++) {
                byte[] sha1 = sha1Bytes(c + i);
                if (sha1 != null && (sha1[n1] & 0xFF) == 0xb0 && (sha1[n1 + 1] & 0xFF) == 0x0b) {
                    return "cdndefend_js_cookie=" + c + i;
                }
            }
        } catch (Exception e) {}
        return "";
    }

    private static byte[] sha1Bytes(String input) {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-1");
            return md.digest(input.getBytes("UTF-8"));
        } catch (Exception e) { return null; }
    }
}
