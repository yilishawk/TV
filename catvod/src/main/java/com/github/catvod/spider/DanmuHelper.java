package com.github.catvod.spider;

import com.github.catvod.net.OkHttp;
import com.github.catvod.utils.Json;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.io.ByteArrayInputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;

import okhttp3.Request;
import okhttp3.Response;

public class DanmuHelper {

    private static final Random RANDOM = new Random();

    private static final String[] DANMU_SOURCES = {
            "https://danmu.zxz.ee/?type=xml&id={md5}",
            "https://dmku.hls.one/?ac=dm&url={url}"
    };

    private static final String[] COLORS = {
            "16711680", "16776960", "65280", "255", "16711935",
            "65535", "16777215", "8388736", "16753920"
    };

    private static final String AD_PATTERN = ".*(请遵守弹幕礼仪|官方弹幕库|微信公众号|云烟小助手|未传入链接|弹幕列队|火花剧场|加群|防走失|备用|联系|侵权).*";

    public static Object[] getDanmuResponse(Map<String, String> params) {
        try {
            String title = params.get("title");
            String episodeStr = params.get("episode");

            if (title == null || title.isEmpty()) title = "未知标题";
            int episodeNum = 1;
            if (episodeStr != null) {
                try {
                    episodeNum = Integer.parseInt(episodeStr.replaceAll("\\D", ""));
                } catch (Exception ignored) {}
            }

            Proxy.log("🎯 [弹幕] title=" + title + " | episode=" + episodeNum);
            String videoUrl = searchVideoUrl(title, episodeNum);
            Proxy.log("🔗 [弹幕] searchVideoUrl结果=" + (videoUrl.isEmpty() ? "空！将不搜索弹幕" : videoUrl));

            String xmlContent = "";
            if (!videoUrl.isEmpty()) {
                xmlContent = fetchAndConvert(videoUrl);
            }

            if (xmlContent.isEmpty()) {
                xmlContent = "<?xml version=\"1.0\" encoding=\"UTF-8\"?><i>"
                        + "<d p=\"0,1,25,16777215,0,0,0,0\">[代理] " + escapeXml(title) + " 弹幕加载完成</d>"
                        + "</i>";
            }

            return new Object[]{
                    200,
                    "application/xml; charset=utf-8",
                    new ByteArrayInputStream(xmlContent.getBytes(StandardCharsets.UTF_8))
            };
        } catch (Exception e) {
            Proxy.log("❌ [弹幕总异常] " + e.getMessage());
            return new Object[]{
                    500,
                    "text/plain",
                    new ByteArrayInputStream(e.getMessage().getBytes())
            };
        }
    }

    private static String searchVideoUrl(String title, int episode) {
        try {
            String searchUrl = "https://api.so.360kan.com/index?force_v=1&kw="
                    + URLEncoder.encode(title, "UTF-8") + "&tab=all";
            // ✅ 修复：OkHttp.string() 只接受 url 参数
            String json = OkHttp.string(searchUrl);
            // ✅ 修复：Json.safeObject 接受 JsonElement，先 parse
            JsonObject data = Json.safeObject(Json.parse(json)).getAsJsonObject("data");
            JsonArray rows = data.getAsJsonObject("longData").getAsJsonArray("rows");

            for (JsonElement el : rows) {
                JsonObject row = el.getAsJsonObject();
                String rowTitle = row.get("titleTxt").getAsString();
                if (!rowTitle.contains(title) && !title.contains(rowTitle)) continue;

                if ("电影".equals(row.get("cat_name").getAsString())) {
                    JsonObject playlinks = row.getAsJsonObject("playlinks");
                    if (playlinks.has("qq")) return cleanUrl(playlinks.get("qq").getAsString());
                    if (playlinks.has("qiyi")) return cleanUrl(playlinks.get("qiyi").getAsString());
                } else {
                    JsonArray series = row.getAsJsonArray("seriesPlaylinks");
                    if (series.size() >= episode) {
                        return cleanUrl(series.get(episode - 1).getAsJsonObject().get("url").getAsString());
                    }
                }
            }
        } catch (Exception e) {
            Proxy.log("❌ [弹幕360搜索失败] " + e.getMessage());
        }
        return "";
    }

    private static String cleanUrl(String url) {
        return url.contains("?") ? url.split("\\?")[0] : url;
    }

    private static String fetchAndConvert(String videoUrl) {
        Proxy.log("🔍 [弹幕搜索] 开始搜索，videoUrl=" + videoUrl);
        String videoMd5 = "";
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("MD5");
            byte[] digest = md.digest(videoUrl.getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) sb.append(String.format("%02x", b));
            videoMd5 = sb.toString();
            Proxy.log("🔑 [弹幕] videoUrl MD5=" + videoMd5);
        } catch (Exception e) {
            Proxy.log("❌ [弹幕] MD5计算失败: " + e.getMessage());
        }

        for (String source : DANMU_SOURCES) {
            try {
                String api = source
                        .replace("{md5}", videoMd5)
                        .replace("{url}", URLEncoder.encode(videoUrl, "UTF-8"));
                String res = OkHttp.string(api);

                if (res.contains("<d")) return res;
                if (res.contains("<i>") && !res.contains("<d")) {
                    Proxy.log("⚠️ [弹幕] " + source + " 无弹幕数据，尝试下一源");
                    continue;
                }

                // ✅ 修复：Json.safeObject 接受 JsonElement，先 parse
                JsonObject json = Json.safeObject(Json.parse(res));
                JsonArray danmuku = null;

                if (json.has("danmuku")) {
                    danmuku = json.getAsJsonArray("danmuku");
                } else if (json.has("data") && json.get("data").isJsonObject()) {
                    JsonObject data = json.getAsJsonObject("data");
                    if (data.has("danmuku")) {
                        danmuku = data.getAsJsonArray("danmuku");
                    }
                }

                if (danmuku == null && json.has("data") && json.get("data").isJsonArray()) {
                    danmuku = json.getAsJsonArray("data");
                }

                Proxy.log("📦 [弹幕解析] source=" + source + " | danmuku条数=" + (danmuku != null ? danmuku.size() : 0));
                if (danmuku != null && danmuku.size() > 0) {
                    StringBuilder xml = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?><i>\n");
                    for (JsonElement d : danmuku) {
                        if (!d.isJsonArray()) continue;
                        JsonArray item = d.getAsJsonArray();
                        if (item.size() < 6) continue;

                        String content = item.get(5).getAsString();
                        if (content.matches(AD_PATTERN)) continue;

                        String time = item.get(0).getAsString();
                        String fontSize = item.get(2).getAsString().replaceAll("[^0-9]", "");
                        if (fontSize.isEmpty()) fontSize = "25";
                        String color = item.get(4).getAsString().replaceAll("[^0-9]", "");
                        if (color.isEmpty()) color = "16777215";
                        long ts = System.currentTimeMillis() / 1000;
                        xml.append(String.format("<d p=\"%s,1,%s,%s,%d,0,0,0\">%s</d>\n",
                                time, fontSize, color, ts, escapeXml(content)));
                    }
                    xml.append("</i>");
                    return xml.toString();
                }
            } catch (Exception e) {
                Proxy.log("❌ [弹幕源失败] source=" + source + " | error=" + e.getMessage());
            }
        }
        return "";
    }

    private static String escapeXml(String text) {
        if (text == null) return "";
        return text.replace("&", "&amp;")
                   .replace("<", "&lt;")
                   .replace(">", "&gt;")
                   .replace("\"", "&quot;")
                   .replace("'", "&apos;");
    }
}
