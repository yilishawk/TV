package com.github.catvod.spider;

import org.json.JSONArray;
import org.json.JSONObject;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import android.text.TextUtils;
import java.util.*;

public class KaiGeSmart {

public static String buildResult(String data, String key) {
        try {
            if (TextUtils.isEmpty(data)) return "{\"list\":[]}";
            String trimData = data.trim();

            // 🚀 智慧識別 JSON 格式 (如蘋果 CMS 接口)
            if (trimData.startsWith("{") || trimData.startsWith("[")) {
                JSONObject json = new JSONObject(trimData);
                // 優先取蘋果 CMS 規範的 list 數組，其次取 data 數組
                JSONArray items = json.optJSONArray("list");
                if (items == null) items = json.optJSONArray("data");

                // 如果找不到數組，說明不是標準列表，回退原樣
                if (items == null) return trimData;

                JSONArray list = new JSONArray();
                for (int i = 0; i < items.length(); i++) {
                    JSONObject item = items.getJSONObject(i);
                    JSONObject vod = new JSONObject();

                    // 映射字段：id -> vod_id, name -> vod_name, pic -> vod_pic
                    // 同時兼容蘋果 CMS 的標準字段名和簡寫名
                    String vodId = item.optString("vod_id", item.optString("id"));
                    String vodName = item.optString("vod_name", item.optString("name"));
                    String vodPic = item.optString("vod_pic", item.optString("pic"));
                    String vodRemarks = item.optString("vod_remarks", item.optString("remarks", ""));

                    if (TextUtils.isEmpty(vodId) || TextUtils.isEmpty(vodName)) continue;

                    // 💡 凱哥注意：關鍵字過濾邏輯
                    if (!TextUtils.isEmpty(key) && !vodName.contains(key)) continue;

                    vod.put("vod_id", vodId);
                    vod.put("vod_name", vodName);
                    vod.put("vod_pic", fixUrl(vodPic));
                    vod.put("vod_remarks", vodRemarks);
                    list.put(vod);
                }
                return new JSONObject().put("list", list).toString();
            }

            // ---------------------------------------------------------
            // 💡 以下為 HTML 原有邏輯，保持不變
            // ---------------------------------------------------------
            JSONObject result = new JSONObject();
            JSONArray list = new JSONArray();
            Document doc = Jsoup.parse(trimData);

            // 💡 JSON 規則優先：如果傳入的是片段，直接解析其子節點
            Elements items = doc.body().children();

            // 💡 保底邏輯：如果傳入整頁，自動識別容器
            if (items.size() < 3) {
                items = doc.select(".myui-vodlist__item, .vodlist_item, .fed-list-item, .pack-ykpack, .list-item, .v-item, .module-item, .stui-vodlist__item, li:has(img), a:has(img)");
            }

            for (Element el : items) {
                JSONObject vod = parseList(el);
                if (vod.has("vod_id") && !TextUtils.isEmpty(vod.optString("vod_name"))) {

                    // 💡 凱哥注意：這裡直接從 vod 裡取名字來比對
                    if (!TextUtils.isEmpty(key) && !vod.optString("vod_name").contains(key)) continue;

                    list.put(vod);
                }
            }
            result.put("list", list);
            return result.toString();
        } catch (Exception e) {
            return "{\"list\":[]}";
        }
    }

    public static String buildDetail(String data) {
        try {
            if (TextUtils.isEmpty(data)) return "{\"list\":[]}";
            JSONObject result = new JSONObject();
            JSONArray list = new JSONArray();
            list.put(parseDetail(data));
            result.put("list", list);
            return result.toString();
        } catch (Exception e) {
            return "{\"list\":[]}";
        }
    }

    public static JSONObject parseList(Element el) {
        JSONObject vod = new JSONObject();
        try {
            String id = findUrl(el);
            if (TextUtils.isEmpty(id) || id.contains("javascript")) return vod;
            
            String name = findTitle(el);
            String pic = findPic(el);

            // 智慧回溯父節點（適配 a:has(img) 結構）
            if (TextUtils.isEmpty(pic) || TextUtils.isEmpty(name)) {
                Element p = el.parent();
                if (p != null) {
                    if (TextUtils.isEmpty(name)) name = findTitle(p);
                    if (TextUtils.isEmpty(pic)) pic = findPic(p);
                }
            }

            if (TextUtils.isEmpty(name)) return vod;
            
            vod.put("vod_name", name);
            vod.put("vod_id", id);
            vod.put("vod_pic", pic);
            
            // 狀態備註抓取
            String remarks = "";
            Element remarkNode = el.selectFirst(".pic-text, .remarks, .state, .pic-tag-bottom, .tag, .label, .badge, .pic-tag, .status");
            if (remarkNode != null) remarks = remarkNode.text().trim();
            if (TextUtils.isEmpty(remarks)) {
                Elements tags = el.select("span, em, b, i, p");
                for (Element tag : tags) {
                    String text = tag.text().trim();
                    if (text.matches(".*(更新|至|[0-9]集|期|完|版|HD|BD|TS|蓝|藍).*")) {
                        remarks = text;
                        break; 
                    }
                }
            }
            vod.put("vod_remarks", remarks);
        } catch (Exception ignored) {}
        return vod;
    }

    public static JSONObject parseDetail(String html) {
        Document doc = Jsoup.parse(html);
        JSONObject vod = new JSONObject();
        try {
            Element titleNode = doc.selectFirst("h1, .title, .myui-content__detail h1, .module-info-heading h1, .detail-title");
            vod.put("vod_name", titleNode != null ? titleNode.text().trim() : "未知標題");
            vod.put("vod_pic", findPic(doc));

            Elements contents = doc.select(".content, .sketch, .data, #desc, .vod_content, .module-info-introduction-content, .detail-content");
            String bestContent = "";
            for (Element c : contents) {
                if (c.text().length() > bestContent.length()) bestContent = c.text().trim();
            }
            vod.put("vod_content", bestContent);

            Elements dataNodes = doc.select(".data, p, li, .myui-content__detail p, .module-info-item, .detail-info-item");
            for (Element node : dataNodes) {
                String text = node.text();
                if (text.contains("主演")) vod.put("vod_actor", getTagsOrText(node, "主演"));
                else if (text.contains("导演") || text.contains("導演")) vod.put("vod_director", getTagsOrText(node, "导演"));
                else if (text.contains("地区") || text.contains("地區")) vod.put("vod_area", getTagsOrText(node, "地区"));
                else if (text.contains("年份") || text.contains("年代")) vod.put("vod_year", getTagsOrText(node, "年份"));
                else if (text.matches(".*(更新|狀態|状态).*")) {
                    vod.put("vod_remarks", text.replaceAll(".*[:：]", "").trim());
                }
            }
            processPlaylist(doc, vod);
        } catch (Exception ignored) {}
        return vod;
    }

    private static void processPlaylist(Document doc, JSONObject vod) {
        try {
            List<String> fromList = new ArrayList<>();
            List<String> urlList = new ArrayList<>();
            Elements tabs = doc.select(".tabs li, .line-title, .from-list li, .playlist-tab li, .myui-panel__head li, .module-tab-item, .anthology-tab a");
            Elements blocks = doc.select(".playlist, .content_playlist, .play-list-box, #playlist, .myui-content__list, .myui-panel_bd .tab-content, .module-play-list, .anthology-list-box");

            if (blocks.isEmpty()) {
                String links = findAllLinks(doc);
                if (!links.isEmpty()) {
                    fromList.add("默認線路");
                    urlList.add(links);
                }
            } else {
                for (int i = 0; i < blocks.size(); i++) {
                    String name = (i < tabs.size()) ? tabs.get(i).text().trim() : "線路 " + (i + 1);
                    String links = findAllLinks(blocks.get(i));
                    if (!links.isEmpty()) {
                        fromList.add(name);
                        urlList.add(links);
                    }
                }
            }
            vod.put("vod_play_from", TextUtils.join("$$$", fromList));
            vod.put("vod_play_url",  TextUtils.join("$$$", urlList));
        } catch (Exception ignored) {}
    }

    private static String findAllLinks(Element root) {
        StringBuilder sb = new StringBuilder();
        for (Element a : root.select("a")) {
            String n = a.text().trim();
            String h = a.attr("href");
            if (!h.isEmpty() && n.length() < 25 && !n.contains("下載") && !h.contains("javascript")) {
                if (sb.length() > 0) sb.append("#");
                sb.append(n).append("$").append(h);
            }
        }
        return sb.toString();
    }

    /**
     * 🚀 凱哥特調：精準圖片抓取邏輯 (相容舊版 Jsoup)
     * 優先級：<a>標籤下的 lazyload -> <img>標籤屬性 -> 背景圖
     */
    public static String findPic(Element el) {
        if (el == null) return "";

        // 1. 優先找帶有 lazyload 類名的標籤（尤其是 a, div, span）
        Elements lazies = el.select(".lazyload, .lazy, .videopic, .img-responsive");
        for (Element lazy : lazies) {
            String val = getImgFromAttributes(lazy);
            if (!val.isEmpty()) return fixUrl(val);
        }

        // 2. 其次找 img 標籤（哪怕它沒有 lazyload 類名）
        Elements imgs = el.select("img");
        for (Element img : imgs) {
            String val = getImgFromAttributes(img);
            if (!val.isEmpty()) return fixUrl(val);
        }

        // 3. 背景圖兜底 (將 allElements() 替換為 select("*") 以兼容舊版本)
        Elements all = el.select("*"); 
        for (Element item : all) {
            String style = item.attr("style");
            if (style.contains("url(")) {
                try {
                    String val = style.substring(style.indexOf("url(") + 4, style.lastIndexOf(")")).replace("'", "").replace("\"", "").trim();
                    if (isValidPic(val)) return fixUrl(val);
                } catch (Exception ignored) {}
            }
        }
        return "";
    }

    /**
     * 💡 私有工具：從屬性中提取圖片地址
     */
    private static String getImgFromAttributes(Element item) {
        // 凱哥，這裡的屬性順序就是抓取的優先級順序
        String[] attrs = {"data-original", "data-src", "src", "data-main", "data-lazy-src", "data-srcset", "_src"};
        for (String a : attrs) {
            String val = item.attr(a).trim();
            if (isValidPic(val)) return val;
        }
        return "";
    }

    /**
     * 💡 私有工具：驗證地址是否為真實圖片
     */
    private static boolean isValidPic(String url) {
        if (TextUtils.isEmpty(url)) return false;
        String u = url.toLowerCase();
        // 排除掉 loading 動圖和 base64
        if (u.contains(".gif") || u.contains("base64,")) return false;
        // 只要是 http 開頭或是相對路徑地址就認為是潛在圖片
        return u.startsWith("http") || u.startsWith("/") || u.startsWith("//");
    }

    private static String fixUrl(String url) {
        if (TextUtils.isEmpty(url)) return "";
        if (url.startsWith("//")) return "http:" + url;
        return url;
    }

    public static String findTitle(Element el) {
        String t = el.attr("title").trim();
        if (t.isEmpty()) t = el.select("a").attr("title").trim();
        if (t.isEmpty()) t = el.select("img").attr("alt").trim();
        if (t.isEmpty()) {
            Element h = el.selectFirst("h1,h2,h3,h4,.title,.name,.module-item-title");
            t = (h != null) ? h.text().trim() : "";
        }
        if (t.isEmpty()) {
            Element a = el.selectFirst("a");
            t = (a != null) ? a.text().trim() : "";
        }
        return t;
    }

    public static String findUrl(Element el) {
        Element a = el.selectFirst("a[href*='vod'], a[href*='detail'], a[href*='play'], a[href*='v-'], a[href$='.html']");
        if (a == null) a = el.is("a") ? el : el.selectFirst("a");
        return a != null ? a.attr("href") : "";
    }

    private static String getTagsOrText(Element node, String key) {
        Elements links = node.select("a");
        if (!links.isEmpty()) {
            StringBuilder sb = new StringBuilder();
            for (Element a : links) {
                String t = a.text().trim();
                if (!t.isEmpty() && !t.equals("更多")) {
                    if (sb.length() > 0) sb.append(", ");
                    sb.append(t);
                }
            }
            return sb.toString();
        }
        return node.text().replaceAll(key + "[:：]", "").trim();
    }
public static JSONObject parseListItem(JSONObject item) {
        JSONObject vod = new JSONObject();
        try {
            String vodId      = item.optString("vod_id",      item.optString("id",      ""));
            String vodName    = item.optString("vod_name",    item.optString("name",    ""));
            String vodPic     = item.optString("vod_pic",     item.optString("pic",     ""));
            String vodRemarks = item.optString("vod_remarks", item.optString("remarks", ""));

            if (TextUtils.isEmpty(vodId) || TextUtils.isEmpty(vodName)) return vod;

            vod.put("vod_id",      vodId);
            vod.put("vod_name",    vodName);
            vod.put("vod_pic",     fixUrl(vodPic));
            vod.put("vod_remarks", vodRemarks);
        } catch (Exception ignored) {}
        return vod;
    }
}
