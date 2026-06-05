package com.github.catvod.spider;

import com.github.catvod.net.OkHttp;
import com.github.catvod.net.OkResult;
import android.text.TextUtils;
import java.net.URL;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class KaiGeNet {

    // 🚀 Cookie 緩存：解決「二次請求」和「登錄狀態」核心
    private static final Map<String, String> cookieJar = new ConcurrentHashMap<>();
    private static final String MOBILE_UA = "Mozilla/5.0 (Linux; Android 12; Pixel 6) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/121.0.6167.178 Mobile Safari/537.36";

    /**
     * 凱哥智慧請求核心
     * @param siteUrl 來源站點（用於注入 Referer）
     * @param method 請求方式 get/post
     * @param url 目標網址
     * @param body 請求參數
     * @param headers 自定義頭
     */
public static OkResult smartRequest(String siteUrl, String method, String url, String body, Map<String, String> headers) {
        String host = getHost(url);
        if (headers == null) headers = new HashMap<>();

        // 1. 注入萬用 UA
        if (!headers.containsKey("User-Agent")) headers.put("User-Agent", MOBILE_UA);

        // 2. 🚀 凱哥防護：注入安全 Referer
        if (!headers.containsKey("Referer")) {
            if (!TextUtils.isEmpty(siteUrl) && siteUrl.matches("^[\\x00-\\x7F]*$")) {
                headers.put("Referer", siteUrl);
            } else {
                headers.put("Referer", getHost(siteUrl) + "/");
            }
        }

        // 3. 自動注入該站點之前的歷史 Cookie
        if (cookieJar.containsKey(host)) {
            headers.put("Cookie", cookieJar.get(host));
        }

        // 4. 執行正式請求
        OkResult res = execute(method, url, body, headers);

        // 5. 提取Set-Cookie更新cookieJar
        String setCookie = getSetCookie(res.getResp());
        if (!TextUtils.isEmpty(setCookie)) {
            String existCookie = cookieJar.getOrDefault(host, "");
            String mergedCookie = mergeCookies(existCookie, setCookie);
            cookieJar.put(host, mergedCookie);

            // CDN盾JS计算
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
    // 🚀 內部執行器：支持 POST(JSON/表單) 和 GET 參數自動轉換
    private static OkResult execute(String method, String url, String body, Map<String, String> headers) {
        method = (method == null) ? "get" : method.toLowerCase();
        
        if ("post".equals(method)) {
            // 如果 body 是 JSON 字符串則直接 POST 字符串，否則轉 Map 發送表單
            if (!TextUtils.isEmpty(body) && body.trim().startsWith("{")) {
                return OkHttp.post(url, body, headers);
            } else {
                return OkHttp.post(url, parseToMap(body), headers);
            }
        }
        
        // 默認使用 GET
        return OkHttp.get(url, parseToMap(body), headers);
    }

    // 輔助：從 OkResult 的響應頭中安全提取 Cookie 字符串
    private static String getSetCookie(Map<String, List<String>> respHeaders) {
        if (respHeaders == null) return "";
        List<String> cookies = respHeaders.get("Set-Cookie");
        if (cookies == null) cookies = respHeaders.get("set-cookie");
        if (cookies != null && !cookies.isEmpty()) {
            return TextUtils.join(";", cookies);
        }
        return "";
    }

    // 輔助：提取網址 Host 域名（帶層級兼容）
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

    // 輔助：將 URL 參數字符串轉為 Map
    private static Map<String, String> parseToMap(String body) {
        Map<String, String> map = new HashMap<>();
        if (TextUtils.isEmpty(body)) return map;
        try {
            String[] pairs = body.split("&");
            for (String pair : pairs) {
                String[] kv = pair.split("=", 2);
                if (kv.length == 2) map.put(kv[0], kv[1]);
            }
        } catch (Exception ignored) {}
        return map;
    }
    // ✅ 合并Cookie：避免新cookie覆盖旧cookie，相同key取新值
    private static String mergeCookies(String oldCookie, String newCookie) {
        if (TextUtils.isEmpty(oldCookie)) return newCookie;
        if (TextUtils.isEmpty(newCookie)) return oldCookie;
        Map<String, String> cookieMap = new java.util.LinkedHashMap<>();
        // 先放旧的
        for (String part : oldCookie.split(";")) {
            String[] kv = part.trim().split("=", 2);
            if (kv.length == 2) cookieMap.put(kv[0].trim(), kv[1].trim());
        }
        // 新的覆盖旧的（相同key取新值）
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
    // ✅ 外部写入cookie到cookieJar
    public static void putCookie(String url, String cookie) {
        String host = getHost(url);
        if (!TextUtils.isEmpty(host) && !TextUtils.isEmpty(cookie)) {
            String existing = cookieJar.getOrDefault(host, "");
            cookieJar.put(host, mergeCookies(existing, cookie));
        }
    }
    // ✅ CDN盾验证：自动计算 cdndefend_js_cookie
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
