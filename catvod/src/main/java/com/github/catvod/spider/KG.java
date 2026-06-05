package com.github.catvod.spider;

import android.content.Context;
import android.text.TextUtils;
import com.github.catvod.crawler.Spider;
import com.github.catvod.net.OkHttp;
import com.github.catvod.net.OkResult;
import org.json.JSONArray;
import org.json.JSONObject;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.Collections;
import java.util.List;
import java.net.URLEncoder;
import java.util.Map;
import java.util.*;
import java.util.Iterator;

public class KG extends Spider {
    private String siteUrl = ""; 
    private JSONObject rule = new JSONObject();
    private Map<String, String> varPool = new HashMap<>();
    private ExecutorService picExecutor = Executors.newFixedThreadPool(8);
    private final Map<String, String> picCache = Collections.synchronizedMap(new HashMap<>());

    private void logger(String msg) {
        try {
            Proxy.log(msg);
        } catch (Exception ignored) {}
    }

private void logCheck(String title, String html, boolean showSource) {
        if (TextUtils.isEmpty(html)) {
            // 💡 显式报警：这里表示 OkHttp 根本没拿到任何数据
            logger("🚨 [网络请求失败] " + title + " 返回内容为空！请检查 UA、Referer 或网站是否开启了 CC 防护");
            return;
        }
        int len = html.length();
        logger("📥 [" + title + "] 请求成功 | 收到 " + len + " 字符");
        if (showSource) {
            String preview = (len > 500 ? html.substring(0, 500) : html)
                .trim().replace("\n", " ").replace("\r", " ");
            logger("📄 [源码预览]: " + preview.replace("<", "&lt;").replace(">", "&gt;") + "...");
        }
    }

    @Override
    public void init(Context context, String extend) {
        try {
            logger("------------------------------------------");
            logger("🚀❤️ <b>凱哥全能獨立引擎啟動 (Full Power)...</b>");

            if (TextUtils.isEmpty(extend)) {
                logger("🚨 [系統] 初始化失敗: 配置路徑為空");
                return;
            }

            String json;
            if (extend.startsWith("http")) {
                // 🚀 關鍵修復：手動過濾掉可能包含中文Referer 隱患
                Map<String, String> initHeaders = new HashMap<>();
                initHeaders.put("Referer", ""); // 清空 Referer，防止 OkHttp 報錯

                // 使用最原始的 OkHttp 請求，避免被 smartRequest 裡的自動 Header 帶偏
                OkResult res = OkHttp.get(extend, null, initHeaders);
                json = res.getBody();
            } else {
                json = extend;
            }

            if (TextUtils.isEmpty(json) || !json.trim().startsWith("{")) {
                logger("🚨 [系統] 初始化失敗: 讀取到的 JSON 格式不正確");
                return;
            }

            this.rule = new JSONObject(json);
            String indexUrl = rule.optString("index_url", "");
            if (!TextUtils.isEmpty(indexUrl)) {
                try {
                    logger("🔍 [发布页] 正在请求: " + indexUrl);
                    Map<String, String> indexHeaders = new HashMap<>();
                    indexHeaders.put("User-Agent", rule.optString("ua", "Mozilla/5.0"));
                    indexHeaders.put("Referer", "");

                    // ✅ 情况一：先尝试跟随 302 跳转
                    String realSite = "";
                    try {
                        Map<String, List<String>> locationHeaders = OkHttp.getLocationHeader(indexUrl, indexHeaders);
                        String realLocation = OkHttp.getLocation(locationHeaders);
                        if (!TextUtils.isEmpty(realLocation) && realLocation.startsWith("http")) {
                            java.net.URL realUrl = new java.net.URL(realLocation);
                            realSite = realUrl.getProtocol() + "://" + realUrl.getHost();
                            logger("✅ [发布页] 302跳转检测到真实域名: " + realSite);
                        }
                    } catch (Exception ignored) {}

                    // ✅ 情况二：302 没拿到，尝试解析 HTML 提取链接
                    if (TextUtils.isEmpty(realSite)) {
                        try {
                            OkResult indexRes = KaiGeNet.smartRequest(indexUrl, "get", indexUrl, null, indexHeaders);
                            String indexHtml = indexRes.getBody();
                            if (!TextUtils.isEmpty(indexHtml)) {
                                String indexRule = rule.optString("index_rule", "");
                                String extracted = "";
                                if (!TextUtils.isEmpty(indexRule)) {
                                    extracted = KaiGeEngine.doExtract(indexHtml, indexRule, "").value;
                                    logger("🔍 [发布页] 使用自定义规则提取: " + extracted);
                                } else {
                                    String indexHost = new java.net.URL(indexUrl).getHost();
                                    Document indexDoc = Jsoup.parse(indexHtml);
                                    for (Element a : indexDoc.select("a[href]")) {
                                        String href = a.attr("abs:href");
                                        if (!TextUtils.isEmpty(href) && href.startsWith("http")) {
                                            String hrefHost = new java.net.URL(href).getHost();
                                            if (!hrefHost.equals(indexHost)) {
                                                extracted = href;
                                                logger("🔍 [发布页] 智能识别到跳转链接: " + extracted);
                                                break;
                                            }
                                        }
                                    }
                                }
                                if (!TextUtils.isEmpty(extracted) && extracted.startsWith("http")) {
                                    java.net.URL realUrl = new java.net.URL(extracted);
                                    realSite = realUrl.getProtocol() + "://" + realUrl.getHost();
                                    logger("✅ [发布页] HTML提取真实域名: " + realSite);
                                }
                            }
                        } catch (Exception ignored) {}
                    }

                    // ✅ 更新域名
                    if (!TextUtils.isEmpty(realSite)) {
                        rule.put("site_url", realSite);
                        rule.put("host", realSite);
                        logger("🌐 [发布页] 域名已更新为: " + realSite);
                    } else {
                        logger("⚠️ [发布页] 两种方式均未获取到域名，保持原域名");
                    }

                } catch (Exception ex) {
                    logger("⚠️ [发布页] 处理失败，保持原域名: " + ex.getMessage());
                }
            }

            // 🚀 從配置中自動提取域名（必须在 index_url 处理之后）
            this.siteUrl = rule.optString("site_url", rule.optString("host", ""));

            logger("✅ [系統] 站點配置加載完成: " + rule.optString("site_name"));
            logger("🌐 [系統] 域名自動綁定: " + this.siteUrl);

            // ✅ 仅当规则明确开启 cdndefend 时才触发预热和CDN盾检测
            if (rule.optBoolean("cdndefend", false)) {
                try {
                    Map<String, List<String>> redirectHeaders = OkHttp.getLocationHeader(
                        this.siteUrl, getHeaders(null));
                    // ✅ 安全检测：如果跳转目标不是同域名则拒绝
                    String location = OkHttp.getLocation(redirectHeaders);
                    if (!TextUtils.isEmpty(location)) {
                        String locationHost = "";
                        try { locationHost = new java.net.URL(location).getHost(); } catch (Exception ignored) {}
                        String siteHost = "";
                        try { siteHost = new java.net.URL(this.siteUrl).getHost(); } catch (Exception ignored) {}
                        if (!locationHost.equals(siteHost)) {
                            logger("<span style='color:#f1c40f;'>⚠️ [预热] 跨域跳转已拒绝: </span>" + location);
                            throw new Exception("cross domain redirect blocked");
                        }
                    }
                    String redirectCookie = "";
                    if (redirectHeaders != null) {
                        List<String> cookies = redirectHeaders.get("Set-Cookie");
                        if (cookies == null) cookies = redirectHeaders.get("set-cookie");
                        if (cookies != null && !cookies.isEmpty()) {
                            StringBuilder sb = new StringBuilder();
                            for (String c : cookies) {
                                String part = c.split(";")[0].trim();
                                if (sb.length() > 0) sb.append("; ");
                                sb.append(part);
                            }
                            redirectCookie = sb.toString();
                        }
                    }
                    if (!TextUtils.isEmpty(redirectCookie)) {
                        JSONObject hdrs = rule.optJSONObject("headers");
                        if (hdrs == null) hdrs = new JSONObject();
                        String existCookie = hdrs.optString("Cookie", "");
                        hdrs.put("Cookie", TextUtils.isEmpty(existCookie) ? redirectCookie : existCookie + "; " + redirectCookie);
                        rule.put("headers", hdrs);
                        KaiGeNet.putCookie(this.siteUrl, redirectCookie);
                        logger("<span style='color:#2ecc71;'>🍪 [302Token] cookie成功: </span>" + redirectCookie);
                    }
                    // 带cookie预热一次，处理CDN盾
                    String homeHtml = KaiGeNet.smartRequest(this.siteUrl, "get", this.siteUrl, null, getHeaders(null)).getBody();
                    // ✅ 检测CDN盾
                    if (!TextUtils.isEmpty(homeHtml) && homeHtml.contains("cdndefend_js_cookie")) {
                        String cookie = KaiGeNet.cdnDefendCookie(homeHtml);
                        if (!TextUtils.isEmpty(cookie)) {
                            JSONObject hdrs = rule.optJSONObject("headers");
                            if (hdrs == null) hdrs = new JSONObject();
                            hdrs.put("Cookie", cookie);
                            rule.put("headers", hdrs);
                            logger("<span style='color:#2ecc71;'>🍪 [CDN盾] 自动计算cookie成功: </span>" + cookie);
                        }
                    }
                    logger("<span style='color:#2ecc71;'>✅ [首页预热] 完成</span>");
                } catch (Exception ex) {
                    logger("<span style='color:#f1c40f;'>⚠️ [首页预热] 异常: </span>" + ex.getMessage());
                }
            }

        } catch (Exception e) {
            // 這裡會捕獲到 Unexpected char 報錯並顯示
            logger("🚨 [系統] 初始化崩潰: " + e.getMessage());
        }
    }


@Override
    public String categoryContent(String tid, String pg, boolean f, HashMap<String, String> e) {
        try {
            // 1. 動態讀取分類配置
            String method = rule.optString("cate_method", "get").toLowerCase();
            String url = (pg.equals("1") && rule.has("cate_page_1") ? rule.optString("cate_page_1") : rule.optString("cate_url"));
            String body = rule.optString("cate_body", "");

            // 1. 確定最終 TID (處理電視劇子類篩選覆蓋)
            String rTid = tid;
            if (e != null) {
                if (e.containsKey("tid")) rTid = e.get("tid");
                else if (e.containsKey("type_id")) rTid = e.get("type_id");
            }

            // 2. 處理基礎變量與篩選變量替換
            url = url.replace("{tid}", URLEncoder.encode(rTid, "UTF-8")).replace("{pg}", pg);
            if (!TextUtils.isEmpty(body)) body = body.replace("{tid}", rTid).replace("{pg}", pg);

            String[] filterKeys = {"area", "class", "year", "by", "lang", "letter", "字母"};
            for (String key : filterKeys) {
                String val = (e != null && e.containsKey(key)) ? e.get(key) : "";
                url = url.replace("{" + key + "}", URLEncoder.encode(val, "UTF-8"));
                if (!TextUtils.isEmpty(body)) body = body.replace("{" + key + "}", val);
            }

            if (url.startsWith("/") && !url.startsWith("//")) url = this.siteUrl + url;

            // 💡 凱哥監控：顯示完整請求鏈接與 POST 參數
            Proxy.log("<b style='color:#2ecc71;'>📂 [分類啟動]</b> " + url);
            if (method.equals("post") && !TextUtils.isEmpty(body)) {
                Proxy.log("<span style='color:#f1c40f;'>[POST參數]</span> " + body);
            }

            // --- 3. 基礎門票 (針對普通網站的 Session) ---
            if (pg.equals("1")) {
                KaiGeNet.smartRequest(this.siteUrl, "get", this.siteUrl, null, getHeaders(null));
                try { Thread.sleep(200); } catch (Exception ignored) {}
            }

            // --- 4. 正式發起請求 ---
            OkResult res = KaiGeNet.smartRequest(this.siteUrl, method, url, body, getHeaders(null));
            String html = res.getBody();

            // 🚀 [修正位置的診斷日誌]
            if (TextUtils.isEmpty(html)) {
                Proxy.log("<b style='color:red;'>🚨 [網絡層錯誤] 請求返回為 0 字節！請檢查 Referer 或 UA。</b>");
            } else {
                Proxy.log("<b style='color:#2ecc71;'>📥 [網絡層成功] 收到源碼: " + html.length() + " 字節</b>");

                // 先解析出 items，再进行逻辑判断
                String itemRule = rule.optString("cate_item");
                if (!TextUtils.isEmpty(itemRule)) {
                    Document doc = Jsoup.parse(html);
                    Elements items = doc.select(itemRule);
                    if (!items.isEmpty()) {
                        Proxy.log("<b style='color:#2ecc71;'>✅ [定位層成功] 匹配到項目数量: " + items.size() + "</b>");
                    } else {
                        Proxy.log("<b style='color:red;'>❌ [定位層錯誤] 規則 [" + itemRule + "] 找不到內容，請修改 cate_item！</b>");
                    }
                } else {
                    Proxy.log("<b style='color:#3498db;'>📋 [定位層] JSON接口模式，跳過CSS選擇器</b>");
                }
            }
            // 💡 凱哥監控：顯示返回數據長度
            Proxy.log("<b style='color:#3498db;'>📊 [數據返回]</b> 長度: " + (html != null ? html.length() : 0));

            // --- 5. 基礎重試補償 ---
            if (res.getCode() != 200 || TextUtils.isEmpty(html) || html.length() < 300) {
                Proxy.log("<b style='color:#f1c40f;'>⚠️ 內容異常，嘗試二次刷新...</b>");
                try { Thread.sleep(1000); } catch (Exception ignored) {}
                res = KaiGeNet.smartRequest(this.siteUrl, method, url, body, getHeaders(null));
                html = res.getBody();
            }

            // --- 6. 交給解析器 ---
            logCheck("分類", html, false);
            return parseList(html, pg, false);
        } catch (Exception ex) { 
            Proxy.log("<b style='color:red;'>🚨 [分類異常]:</b> " + ex.getMessage());
            return "{\"list\":[]}"; 
        }
    }

@Override
    public String searchContent(String key, boolean quick) {
        try {
            // 1. 動態讀取規則中的方法、網址和 Body
            String method = rule.optString("search_method", "get").toLowerCase();
            String url = rule.optString("search_url");
            String body = rule.optString("search_body", "");

            // 2. 處理關鍵字替換 (🚀 修復二次編碼問題)
            if (method.equals("post")) {
                // POST 模式：直接用原始 key，交給底層 KaiGeNet/OkHttp 自動編碼，防止 % 變成 %25
                body = body.replace("{wd}", key);
            } else {
                // GET 模式：必須手動編碼，因為 URL 字符串拼接不支持原始中文
                url = url.replace("{wd}", URLEncoder.encode(key, "UTF-8"));
            }

            // 域名補全
            if (url.contains("{host}")) url = url.replace("{host}", this.siteUrl);
            else if (url.startsWith("/") && !url.startsWith("//")) url = this.siteUrl + url;

            // 💡 凱哥監控：這裡是關鍵，看紫色和藍色日誌的輸出
            Proxy.log("<b style='color:#3498db;'>🔍 [搜索啟動]</b> 方法: " + method.toUpperCase());
            Proxy.log("<span style='color:#9b59b6;'>[搜索網址]</span> " + url);
            if (method.equals("post")) {
                Proxy.log("<span style='color:#f1c40f;'>[POST參數]</span> " + body);
            }

            // 3. 🚀 關鍵修復：將寫死的 "get" 改為動態 method，將 null 改為 body
            OkResult res = KaiGeNet.smartRequest(this.siteUrl, method, url, body, getHeaders(null));

            logCheck("搜索", res.getBody(), false);
            return parseList(res.getBody(), "1", true);
        } catch (Exception e) { 
            Proxy.log("<b style='color:red;'>🚨 [搜索異常]:</b> " + e.getMessage());
            return "{\"list\":[]}"; 
        }
    }

@Override
    public String detailContent(List<String> ids) {
        try {
            String id = ids.get(0);

            // 1. 動態讀取詳情配置
            String method = rule.optString("detail_method", "get").toLowerCase();
            String url = id.startsWith("http") ? id : this.siteUrl + (id.startsWith("/") ? "" : "/") + id;
            String body = rule.optString("detail_body", "");

            // 2. 處理變量替換 (POST 傳原始值，GET 傳編碼值)
            if (method.equals("post")) {
                body = body.replace("{id}", id);
            } else {
                // 如果 URL 包含 {id} 佔位符則替換，否則保持原有拼接邏輯
                if (url.contains("{id}")) {
                    url = url.replace("{id}", URLEncoder.encode(id, "UTF-8"));
                }
            }

            // 💡 凱哥監控：詳情請求日誌
            Proxy.log("<b style='color:#f39c12;'>📋 [詳情啟動]</b> 方法: " + method.toUpperCase() + " | ID: " + id);
            if (method.equals("post")) {
                Proxy.log("<span style='color:#f1c40f;'>[POST參數]</span> " + body);
            }

            // 3. 🚀 升級：使用動態 method 和 body 調用 KaiGeNet
            OkResult res = KaiGeNet.smartRequest(this.siteUrl, method, url, body, getHeaders(null));
            String html = res.getBody();
            logCheck("詳情", html, false);

// ✅ 新增：如果详情接口返回的是苹果CMS标准JSON，直接解析
if (html != null && html.trim().startsWith("{")) {
    try {
        JSONObject json = new JSONObject(html.trim());
        JSONArray dataList = json.optJSONArray("list");
        if (dataList != null && dataList.length() > 0) {
            JSONObject item = dataList.getJSONObject(0);
            JSONObject vod = new JSONObject();
            vod.put("vod_id",       ids.get(0));
            vod.put("vod_name",     item.optString("vod_name",     item.optString("name",     "")));
            vod.put("vod_pic",      item.optString("vod_pic",      item.optString("pic",      "")));
            vod.put("vod_remarks",  item.optString("vod_remarks",  item.optString("remarks",  "")));
            vod.put("vod_actor",    item.optString("vod_actor",    item.optString("actor",    "")));
            vod.put("vod_director", item.optString("vod_director", item.optString("director", "")));
            vod.put("vod_content",  item.optString("vod_content",  item.optString("content",  "")));
            vod.put("vod_play_from",item.optString("vod_play_from",""));
            vod.put("vod_play_url", item.optString("vod_play_url", ""));
            Proxy.log("<b style='color:#2ecc71;'>✅ [详情] JSON直解成功: </b>" + vod.optString("vod_name"));
            // ✅ 保存标题和集数到 varPool，供弹幕使用
            varPool.put("vod_name", vod.optString("vod_name", "未知标题"));
            String playUrl = vod.optString("vod_play_url", "");
            if (!TextUtils.isEmpty(playUrl)) {
                String[] episodes = playUrl.split("#");
                varPool.put("vod_total_episode", String.valueOf(episodes.length));
                varPool.put("vod_play_url", playUrl);
            }
            return new JSONObject().put("list", new JSONArray().put(vod)).toString();
        }
    } catch (Exception ex) {
        Proxy.log("<b style='color:red;'>❌ [详情] JSON解析失败: </b>" + ex.getMessage());
    }
}

// 原有HTML解析逻辑继续往下走
Document doc = Jsoup.parse(html);

            // 🚀 升級：智慧保底模式
            // 首先嘗試用 KaiGeSmart 掃描全圖
            JSONObject smartVod = KaiGeSmart.parseDetail(html);

            JSONObject vod = new JSONObject();
            vod.put("vod_id", id);

            // 策略：如果規則有寫就用規則，規則沒寫或抓不到就用大腦智慧識別
            String name = extract(doc, rule.optString("dt_name"));
            vod.put("vod_name", TextUtils.isEmpty(name) ? smartVod.optString("vod_name") : name);

            String pic = extract(doc, rule.optString("dt_pic"));
            vod.put("vod_pic", TextUtils.isEmpty(pic) ? smartVod.optString("vod_pic") : pic);

            vod.put("vod_remarks", extract(doc, rule.optString("dt_remarks")));

            String actor = extract(doc, rule.optString("dt_actor"));
            vod.put("vod_actor", TextUtils.isEmpty(actor) ? smartVod.optString("vod_actor") : actor);

            String director = extract(doc, rule.optString("dt_director"));
            vod.put("vod_director", TextUtils.isEmpty(director) ? smartVod.optString("vod_director") : director);

            String content = extract(doc, rule.optString("dt_content"));
            vod.put("vod_content", TextUtils.isEmpty(content) ? smartVod.optString("vod_content") : content);

            // 🚀 升級：播放列表處理
            if (TextUtils.isEmpty(rule.optString("dt_list"))) {
                // 如果規則沒寫列表選擇器，直接用大腦識別出的線路
                vod.put("vod_play_from", smartVod.optString("vod_play_from"));
                vod.put("vod_play_url", smartVod.optString("vod_play_url"));
            } else {
                // 如果規則寫了，則執行你原本的精確配對邏輯
                processOriginalDetail(doc, vod);
            }

            // ✅ 保存标题和集数到 varPool，供弹幕使用
            varPool.put("vod_name", vod.optString("vod_name", "未知标题"));
            String playUrl = vod.optString("vod_play_url", "");
            if (!TextUtils.isEmpty(playUrl)) {
                String[] episodes = playUrl.split("#");
                varPool.put("vod_total_episode", String.valueOf(episodes.length));
                varPool.put("vod_play_url", playUrl);
            }
            return new JSONObject().put("list", new JSONArray().put(vod)).toString();
        } catch (Exception e) { 
            Proxy.log("<b style='color:red;'>🚨 [詳情異常]:</b> " + e.getMessage());
            return ""; 
        }
    }

    // 提取出的原本詳情解析邏輯（保持凱哥原汁原味）
    private void processOriginalDetail(Document doc, JSONObject vod) throws Exception {
        String fromRule = rule.optString("dt_from");
        String listRule = rule.optString("dt_list");
        String cssFrom = fromRule;
        if (fromRule.contains("&&")) {
            String[] parts = fromRule.split("&&");
            cssFrom = parts[0].contains("[包含:") ? (parts.length > 1 ? parts[1] : "h3") : parts[0];
        }
                // ← 加这几行调试
        Elements allListsDebug = doc.select(listRule);
        Proxy.log("🔍 [調試] dt_from 規則: " + cssFrom);
        Proxy.log("🔍 [調試] dt_list 規則: " + listRule);
        Proxy.log("🔍 [調試] dt_list 匹配到列表数: " + allListsDebug.size());
        for (int i = 0; i < allListsDebug.size(); i++) {
            Elements links = allListsDebug.get(i).select("a");
            Proxy.log("🔍 [調試] 第" + (i+1) + "个列表 链接数: " + links.size() + " 第一个链接: " + (links.isEmpty() ? "空" : links.get(0).attr("href")));
        }
        // ← 调试结束
        Elements fromElements = doc.select(cssFrom);
        List<String> fList = new ArrayList<>();
        List<String> pLists = new ArrayList<>();

        for (Element from : fromElements) {
            String sourceName = from.text().trim();
            if (TextUtils.isEmpty(sourceName)) sourceName = "播放線路 " + (fromElements.indexOf(from) + 1);

            // 直接按索引取对应列表
            Elements allLists = doc.select(listRule);
            int idx = fromElements.indexOf(from);
            Element nextList = (idx < allLists.size()) ? allLists.get(idx) : null;

            if (nextList != null) {
                Proxy.log("🔍 [pLists存入] 第" + fList.size() + "条线路 HTML前50: " + nextList.outerHtml().substring(0, Math.min(50, nextList.outerHtml().length())));
                fList.add(sourceName);
                pLists.add(nextList.outerHtml());
            }
        }

        List<String> playList = new ArrayList<>();
        for (int i = 0; i < pLists.size(); i++) {
            List<String> urls = new ArrayList<>();
            Document listDoc = Jsoup.parse(pLists.get(i));
            Elements aElements = listDoc.select("a");
            for (Element a : aElements) {
                String pName = a.text().trim();
                String pUrl = a.attr("href").trim();
                if (!pName.isEmpty() && !pUrl.isEmpty() && !pUrl.contains("javascript")) {
                    urls.add(pName + "$" + pUrl);
                }
            }
            playList.add(TextUtils.join("#", urls));
        }

        vod.put("vod_play_from", TextUtils.join("$$$", fList));
        vod.put("vod_play_url", TextUtils.join("$$$", playList));

        // ← 加这行
        Proxy.log("🔍 [最終組裝] from: " + vod.optString("vod_play_from") + " | url前100: " + vod.optString("vod_play_url").substring(0, Math.min(100, vod.optString("vod_play_url").length())));
    }

@Override
    public String playerContent(String flag, String id, List<String> vipFlags) {
        String originalUrl = id.startsWith("/") && !id.startsWith("//") ? this.siteUrl + id : id;
        try {
            // 🎬 啟動日誌
            Proxy.log("<b style='color:#e74c3c;'>🎬 [播放解析啟動]</b> 原始ID: " + originalUrl);

            // ✅ 备份弹幕数据（必须在 clear 之前！）
            String danmuTitle = varPool.get("vod_name");
            String danmuPlayUrl = varPool.get("vod_play_url");

            varPool.clear();
            // ✅ 备份恢复，防止 clear 丢失
            if (!TextUtils.isEmpty(danmuTitle)) varPool.put("vod_name", danmuTitle);
            if (!TextUtils.isEmpty(danmuPlayUrl)) varPool.put("vod_play_url", danmuPlayUrl);

            // ✅ 从播放链接反查集数（弹幕需要）
            String currentEpisode = "1";
if (!TextUtils.isEmpty(danmuPlayUrl)) {
    String[] episodes = danmuPlayUrl.split("#");
    Proxy.log("🔍 [弹幕集数] 共 " + episodes.length + " 集，正在匹配 id=" + id);
    String cleanId = id.contains("?") ? id.split("\\?")[0] : id;
    for (int i = 0; i < episodes.length; i++) {
        String[] parts = episodes[i].split("\\$");
        String epUrl = parts.length > 1 ? parts[parts.length - 1].trim() : "";
        String cleanEpUrl = epUrl.contains("?") ? epUrl.split("\\?")[0] : epUrl;
        if (!epUrl.isEmpty() && (epUrl.equals(id) || cleanEpUrl.equals(cleanId) || epUrl.contains(cleanId) || cleanId.contains(cleanEpUrl))) {
            String epName = parts[0].replaceAll("[^0-9]", "");
            currentEpisode = TextUtils.isEmpty(epName) ? String.valueOf(i + 1) : epName;
            Proxy.log("✅ [弹幕集数] 匹配成功！第 " + currentEpisode + " 集，集名原文=" + parts[0]);
            break;
        }
        // 最后一集仍未匹配，打印第一集信息便于排查
        if (i == episodes.length - 1) {
            Proxy.log("⚠️ [弹幕集数] 全部 " + episodes.length + " 集均未匹配，cleanId=" + cleanId + " | 第1集epUrl=" + (episodes[0].split("\\$").length > 1 ? episodes[0].split("\\$")[episodes[0].split("\\$").length - 1] : "无"));
        }
    }
}

            // ✅ 恢复弹幕数据到 varPool
            if (!TextUtils.isEmpty(danmuTitle)) varPool.put("vod_name", danmuTitle);
            varPool.put("vod_episode", currentEpisode);
            Proxy.log("<span style='color:#9b59b6;'>[弹幕] 标题=" + varPool.get("vod_name") + ", 集数=" + currentEpisode + "</span>");
            varPool.put("play_id", originalUrl);
            varPool.put("final_url", originalUrl); 

            JSONObject play = rule.has("play") ? rule.getJSONObject("play") : new JSONObject();
            JSONArray steps = play.optJSONArray("steps");
            int stepCount = (steps != null ? steps.length() : 0);
            boolean finalStepSuccess = false;
            // ✅ jx 动态解析配置
            String jx = play.optString("jx", "");
            if (!TextUtils.isEmpty(jx)) {
                try {
                    Proxy.log("<span style='color:#9b59b6;'>[jx配置] 请求: </span>" + jx);
                    OkResult cfgRes = KaiGeNet.smartRequest(this.siteUrl, "get", jx, null, getHeaders(null));
                    String cfgBody = cfgRes.getBody();

                    if (!TextUtils.isEmpty(cfgBody)) {
                        String jxList  = play.optString("jx_list",  "");
                        String jxTitle = play.optString("jx_title", "");
                        String jxParse = play.optString("jx_parse", "");

                        if (!TextUtils.isEmpty(jxList) && !TextUtils.isEmpty(jxTitle) && !TextUtils.isEmpty(jxParse)) {
                            // 第一步：切出线路列表片段
                            // ✅ 支持两种写法：JSON路径(data.jiexiDataList) 或 切刀规则(jiexiDataList&&[)
                            String block = "";
                            if (jxList.contains(".") && !jxList.contains("&&")) {
                                try {
                                    JSONObject cfgJson = new JSONObject(cfgBody);
                                    Object pathResult = getJsonByPath(cfgJson, jxList);
                                    block = pathResult != null ? pathResult.toString() : "";
                                } catch (Exception ignored) {}
                            } else {
                                block = KaiGeEngine.doExtract(cfgBody, jxList, this.siteUrl).value;
                            }
                            Proxy.log("<span style='color:#3498db;'>[jx配置] 切出片段长度: </span>" + block.length());

                            String val = "";
                            try {
                                // ✅ 优先：标准JSON数组格式（如 hktvyb）
                                JSONArray jxArray = new JSONArray(block);
                                for (int j = 0; j < jxArray.length(); j++) {
                                    JSONObject entry = jxArray.getJSONObject(j);
                                    if (flag.equals(entry.optString(jxTitle))) {
                                        val = entry.optString(jxParse, "");
                                        break;
                                    }
                                }
                            } catch (Exception ignored) {
                                // ✅ 兜底：切刀规则（如 qdys1 的JS文件格式）
                                String titleRule = "\"" + jxTitle + "\":\"" + flag + "\"&&\"" + jxParse + "\":\"&&\"";
                                val = KaiGeEngine.doExtract(block, titleRule, this.siteUrl).value;
                            }
                            val = val.replace("\\/", "/").replace("\\", "").trim();

                            varPool.put("jx_parse", val);
                            if (!TextUtils.isEmpty(val)) {
                                Proxy.log("<span style='color:#2ecc71;'>[jx配置] 命中 [" + flag + "] jx_parse = </span>" + val);
                            } else {
                                Proxy.log("<span style='color:#f1c40f;'>[jx配置] 线路 [" + flag + "] 无解析前缀，视为直链</span>");
                            }
                        }
                    }
                } catch (Exception ex) {
                    Proxy.log("<b style='color:red;'>[jx配置] 失败: </b>" + ex.getMessage());
                }
            }
            if (stepCount == 0) {
                boolean isStream = originalUrl.toLowerCase().contains(".m3u8") || originalUrl.toLowerCase().contains(".mp4");
                JSONObject res = new JSONObject();
                res.put("parse", isStream ? 0 : 1);
                res.put("url", originalUrl);
                res.put("header", getPlayHeaders(play));
                // ✅ 注入弹幕
                String _danmuTitle1 = varPool.get("vod_name");
                String _danmuEp1 = varPool.get("vod_episode");
                if (rule.optBoolean("danmaku", false) && !TextUtils.isEmpty(_danmuTitle1)) {
                    try {
                        String _danmakuUrl1 = "http://127.0.0.1:10086/danmu"
        + "?title=" + URLEncoder.encode(_danmuTitle1, "UTF-8")
        + "&episode=" + URLEncoder.encode(TextUtils.isEmpty(_danmuEp1) ? "1" : _danmuEp1, "UTF-8");
                        res.put("danmaku", _danmakuUrl1);
                        Proxy.log("<b style='color:#2ecc71;'>💬 [弹幕] 已注入 danmaku=" + _danmakuUrl1 + "</b>");
                    } catch (Exception e) {
                        Proxy.log("<b style='color:red;'>❌ [弹幕] 注入失败: " + e.getMessage() + "</b>");
                    }
                }
                Proxy.log("<b style='color:#2ecc71;'>🚀 [Direct] 無解析步驟，直接推送原始地址</b>");
                return res.toString();
            }

            for (int i = 0; i < stepCount; i++) {
                if (i >= 5) break;
                JSONObject step = steps.getJSONObject(i);
                String stepUrl = replaceStepVars(step.optString("url", varPool.get("final_url")));
                String method = step.optString("method", "get");

                // 💡 獲取當前步驟的請求頭
                Map<String, String> headers = getHeaders(step.optJSONObject("headers"));

                // 🚀 凱哥監控：Step 請求細節（含 URL 和 Headers）
                Proxy.log("<span style='color:#3498db;'>[Step " + (i + 1) + " 請求]</span> " + method.toUpperCase() + " -> " + stepUrl);
                Proxy.log("<span style='color:#9b59b6;'>[請求頭查看]</span> " + headers.toString());

            // 1. 發起請求
            OkResult res = KaiGeNet.smartRequest(this.siteUrl, method, stepUrl, replaceStepVars(step.optString("body")), getHeaders(step.optJSONObject("headers")));
            String html = res.getBody();

            // 🚀 凱哥暴力監控：不論 html 是否為空，通通打印！
            Proxy.log("<b style='color:#3498db;'>📥 [Step " + (i + 1) + " 返回監控]</b>");
            if (TextUtils.isEmpty(html)) {
                // 如果是空的，說明請求連通都沒通（可能是網址帶了特殊字符、引號或網絡超時）
                Proxy.log("<b style='color:red;'>❌ [致命] 返回內容完全為空！</b> 請檢查 URL 格式或網絡連通性。");
            } else {
                // 🚀 核心：強制噴出前 500 字符源碼，確保你在日誌能看到數據「真身」
                String preview = (html.length() > 500 ? html.substring(0, 500) : html)
                                .trim().replace("\n", " ").replace("\r", " ");
                Proxy.log("<div style='background:#2c3e50; color:#ecf0f1; padding:5px; border-left:5px solid #e74c3c;'>源碼預覽: " + preview.replace("<", "&lt;").replace(">", "&gt;") + "...</div>");
            }

            // 2. 繼續執行變量提取
            JSONObject vars = step.optJSONObject("vars");
if (vars != null) {
                    boolean currentStepAnyOk = false;
                    for (Iterator<String> it = vars.keys(); it.hasNext(); ) {
                        String k = it.next();
                        String vRule = vars.optString(k).trim(); // 去掉可能存在的空格
                        String val = "";

                        String[] vRules = vRule.split("\\|\\|");
                        for (String singleRule : vRules) {
                            singleRule = singleRule.trim();
                            if (singleRule.startsWith("json:")) {
                                try {
                                    String keyName = singleRule.substring(5).trim();
                                    JSONObject jsonObj = new JSONObject(html.trim());
                                    val = jsonObj.optString(keyName);
                                } catch (Exception e) {
                                    val = "";
                                }
                            } else {
                                val = KaiGeEngine.doExtract(html, singleRule, this.siteUrl).value;
                            }
                            if (!TextUtils.isEmpty(val)) {
                                Proxy.log("    └─ <span style='color:#f1c40f;'>[提取成功]</span> 命中規則: " + singleRule);
                                break;
                            } else {
                                Proxy.log("    └─ <span style='color:#95a5a6;'>⚠️ [規則未命中]</span> 嘗試下一條: " + singleRule);
                            }
                        }

                        // 🚀 2. 暴力清洗提取到的數據
                        if (!TextUtils.isEmpty(val)) {
                            // 幹掉所有反斜槓，把 \/ 變成 /
                            val = val.replace("\\/", "/").replace("\\", "").trim();

                            varPool.put(k, val);

                            // 💡 變量提取監控
                            Proxy.log("    └─ <span style='color:#f1c40f;'>[提取成功]</span> " + k + " = " + (val.length() > 80 ? val.substring(0, 80) + "..." : val));

                            // 🚀 3. 核心鎖定：只要 key 包含 url，就更新最終地址
                            if (k.contains("url") || k.matches("p[1-4]")) {
                                varPool.put("final_url", val); 
                                currentStepAnyOk = true;
                            }
                        } else {
                            Proxy.log("    └─ <b style='color:#95a5a6;'>⚠️ [提取為空]</b> 鍵: " + k + " | 規則: " + vRule);
                        }
                    }
                    if (i == stepCount - 1 && currentStepAnyOk) finalStepSuccess = true;
                }
            } 

            String finalUrl = varPool.get("final_url").replace("\\/", "/");
            boolean finalHasStream = finalUrl.toLowerCase().contains(".m3u8") || finalUrl.toLowerCase().contains(".mp4");
            int pValue = (finalStepSuccess || finalHasStream) ? 0 : 1;

            JSONObject resJson = new JSONObject();
            resJson.put("parse", pValue);
            resJson.put("url", (pValue == 0) ? finalUrl : originalUrl);
            resJson.put("header", getPlayHeaders(play));
            // ✅ 注入弹幕
            String _danmuTitle2 = varPool.get("vod_name");
            String _danmuEp2 = varPool.get("vod_episode");
            if (rule.optBoolean("danmaku", false) && !TextUtils.isEmpty(_danmuTitle2)) {
                try {
                    String _danmakuUrl2 = "http://127.0.0.1:10086/danmu"
        + "?title=" + URLEncoder.encode(_danmuTitle2, "UTF-8")
        + "&episode=" + URLEncoder.encode(TextUtils.isEmpty(_danmuEp2) ? "1" : _danmuEp2, "UTF-8");
                    resJson.put("danmaku", _danmakuUrl2);
                    Proxy.log("<b style='color:#2ecc71;'>💬 [弹幕] 已注入 danmaku=" + _danmakuUrl2 + "</b>");
                } catch (Exception e) {
                    Proxy.log("<b style='color:red;'>❌ [弹幕] 注入失败: " + e.getMessage() + "</b>");
                }
            }

            // 🚀 最終推送 JSON 日誌
            String finalPush = resJson.toString();
            Proxy.log("<b style='color:#2ecc71;'>🚀 [Final:推送 JSON]</b>");
            Proxy.log("<div style='background:#1a1a1a; color:#00ff00; padding:8px; border:1px solid #2ecc71; font-family:monospace;'>" + finalPush + "</div>");

            return finalPush;
        } catch (Exception e) {
            Proxy.log("<b style='color:red;'>❌ [播放解析崩潰]:</b> " + e.getMessage());
            return "{\"parse\":1,\"url\":\"" + originalUrl + "\",\"header\":{}}";
        }
    }

    private JSONObject getPlayHeaders(JSONObject play) {
        JSONObject headJson = play.optJSONObject("play_headers");
        if (headJson == null) {
            headJson = new JSONObject();
            try {
                headJson.put("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36");
                headJson.put("Referer", this.siteUrl + "/");
            } catch (Exception ignored) {}
        }
        return headJson;
    }

private String parseList(String html, String pg, boolean isSearch) {
    try {
        JSONArray list = new JSONArray();
        String prefix = isSearch ? "sc_" : "cate_";
        String detailTemplate = rule.optString("detail_url", "");

        String itemRule = rule.optString(prefix + "item", rule.optString("cate_item", ""));
        String idRule     = rule.optString(prefix + "id",      rule.optString("cate_id",      ""));
        String nameRule   = rule.optString(prefix + "name",    rule.optString("cate_name",    ""));
        String picRule    = rule.optString(prefix + "pic",     rule.optString("cate_pic",     ""));
        String remarkRule = rule.optString(prefix + "remarks", rule.optString("cate_remarks", ""));

        // ✅ 判断是否走 json: 模式（cate_item 以 json: 开头）
        if (itemRule.toLowerCase().startsWith("json:")) {
            // 取数组key，如 json:list -> list
            String arrayKey = itemRule.substring(5).trim();
            JSONObject json = new JSONObject(html);
            JSONArray array = json.optJSONArray(arrayKey);
            if (array != null) {
                for (int i = 0; i < array.length(); i++) {
                    JSONObject item = array.getJSONObject(i);
                    JSONObject vod = new JSONObject();

                    // 取各字段：json:movie_id -> movie_id，没写json:则用原值兜底
                    String vId      = item.optString(stripJson(idRule),      item.optString("vod_id", item.optString("id", "")));
                    String vName    = item.optString(stripJson(nameRule),    item.optString("vod_name", item.optString("name", "")));
                    String vPic     = item.optString(stripJson(picRule),     item.optString("vod_pic", item.optString("pic", "")));
                    String vRemarks = item.optString(stripJson(remarkRule),  item.optString("vod_remarks", item.optString("remarks", "")));

                    if (TextUtils.isEmpty(vId) || TextUtils.isEmpty(vName)) continue;

                    if (!detailTemplate.isEmpty() && !vId.startsWith("http")) {
                        vod.put("vod_id", detailTemplate.replace("{id}", vId));
                    } else {
                        vod.put("vod_id", vId.startsWith("http") ? vId : this.siteUrl + (vId.startsWith("/") ? "" : "/") + vId);
                    }

                    vod.put("vod_name", vName);
                    vPic = fixPicUrl(vPic);
                    vod.put("vod_pic",     vPic);
                    vod.put("vod_remarks", vRemarks);

                    if (vod.has("vod_id")) list.put(vod);
                }
            }

        // ✅ 没有写 cate_item，但返回的是标准JSON（自动兼容苹果CMS）
        } else if (html != null && html.trim().startsWith("{")) {
            JSONObject json = new JSONObject(html);
            String listPath = rule.optString("cate_list_path", "list");
            Object pathResult = getJsonByPath(json, listPath);
            JSONArray array = pathResult instanceof JSONArray ? (JSONArray) pathResult : null;
            // 兼容旧逻辑：找不到指定路径则尝试默认 list
            if (array == null) array = json.optJSONArray("list");
            if (array != null) {
                for (int i = 0; i < array.length(); i++) {
                    JSONObject vod = KaiGeSmart.parseListItem(array.getJSONObject(i));
                    if (!vod.has("vod_id")) continue;

                    String vId = vod.optString("vod_id");
                    if (!detailTemplate.isEmpty() && !vId.startsWith("http")) {
                        vod.put("vod_id", detailTemplate.replace("{id}", vId));
                    } else {
                        vod.put("vod_id", vId.startsWith("http") ? vId : this.siteUrl + (vId.startsWith("/") ? "" : "/") + vId);
                    }
                    list.put(vod);
                }
            }

        // ✅ 原有 HTML CSS选择器逻辑，完全保留
        } else {
            Document doc = Jsoup.parse(html);
            Elements items = doc.select(itemRule);

            for (Element item : items) {
                JSONObject smartVod = KaiGeSmart.parseList(item);
                JSONObject vod = new JSONObject();

                String vId = extract(item, idRule);
                if (TextUtils.isEmpty(vId)) vId = smartVod.optString("vod_id");

                if (!TextUtils.isEmpty(vId)) {
                    if (isSearch && !detailTemplate.isEmpty() && !vId.startsWith("http")) {
                        vod.put("vod_id", detailTemplate.replace("{id}", vId));
                    } else {
                        vod.put("vod_id", vId.startsWith("http") ? vId : this.siteUrl + (vId.startsWith("/") ? "" : "/") + vId);
                    }
                }

                String vName = extract(item, nameRule);
                vod.put("vod_name", TextUtils.isEmpty(vName) ? smartVod.optString("vod_name") : vName);

                String vPic = extract(item, picRule);
                if (TextUtils.isEmpty(vPic)) vPic = smartVod.optString("vod_pic");
                vPic = fixPicUrl(vPic);
                vod.put("vod_pic", vPic);

                String vRemarks = extract(item, remarkRule);
                vod.put("vod_remarks", TextUtils.isEmpty(vRemarks) ? smartVod.optString("vod_remarks") : vRemarks);

                if (vod.has("vod_id")) list.put(vod);
            }
        }

        return new JSONObject().put("list", list).put("page", pg).toString();
    } catch (Exception e) {
        return "{\"list\":[]}";
    }
}
    private String fixPicUrl(String pic) {
        if (TextUtils.isEmpty(pic)) return pic;

        // 缓存命中直接返回
        if (picCache.containsKey(pic)) {
            return picCache.get(pic);
        }

        // ==================== 图片跳转 + API二次提取模式（多线程） ====================
        if (rule.optBoolean("pic_jump", false)) {
            String prefix = rule.optString("pic_prefix", "");
            String suffix = rule.optString("pic_suffix", "");
            String extractRule = rule.optString("pic_extract", "");

            if (!TextUtils.isEmpty(prefix)) {
                String apiUrl = prefix + pic + suffix;

                try {
                    Future<String> future = picExecutor.submit(() -> {
                        try {
                            OkResult res = KaiGeNet.smartRequest(this.siteUrl, "get", apiUrl, null, getHeaders(null));
                            String response = res.getBody();

                            if (!TextUtils.isEmpty(response) && !TextUtils.isEmpty(extractRule)) {
                                String finalPic = KaiGeEngine.doExtract(response, extractRule, "").value;

                                if (!TextUtils.isEmpty(finalPic)) {
                                    String picHost = rule.optString("pic_host", "");
                                    if (!finalPic.startsWith("http") && !TextUtils.isEmpty(picHost)) {
                                        finalPic = picHost + (finalPic.startsWith("/") ? "" : "/") + finalPic;
                                    }
                                    return finalPic;
                                }
                            }
                        } catch (Exception e) {
                            logger("⚠️ [pic_jump] 请求失败: " + e.getMessage());
                        }
                        return pic; // 失败回退
                    });

                    String result = future.get(8, TimeUnit.SECONDS);
                    picCache.put(pic, result);
                    return result;

                } catch (Exception e) {
                    logger("⚠️ [pic_jump] 多线程超时或异常: " + e.getMessage());
                }
            }
        }

        // ==================== 普通模式（向下兼容）====================
        if (pic.startsWith("http://") || pic.startsWith("https://")) {
            picCache.put(pic, pic);
            return pic;
        }
        if (pic.startsWith("//")) {
            String result = "https:" + pic;
            picCache.put(pic, result);
            return result;
        }

        String picHost = rule.optString("pic_host", this.siteUrl);
        String result = picHost + (pic.startsWith("/") ? "" : "/") + pic;
        picCache.put(pic, result);
        return result;
    }
        // ==================== 新增：stripJson 方法（解决编译错误）====================
    private String stripJson(String rule) {
        if (TextUtils.isEmpty(rule)) return "";
        String r = rule.trim();
        if (r.toLowerCase().startsWith("json:")) {
            return r.substring(5).trim();
        }
        return r;
    }
private String extract(Object root, String ruleStr) {
        try {
            if (TextUtils.isEmpty(ruleStr) || root == null) return "";

            // 🚀 關鍵點 1：讓規則支持變量替換
            // 這樣你才能在規則裡寫 "{host}/vod/" 或者使用之前 vars 存下的 {kkk}
            String realRule = replaceStepVars(ruleStr);

            // 判斷是 Jsoup 選擇器還是 KaiGeEngine 規則
            if (!realRule.contains("&&")) {
                if (root instanceof Element) {
                    Element el = (Element) root;
                    if (realRule.contains("@")) {
                        String[] parts = realRule.split("@");
                        Element target = parts[0].trim().isEmpty() ? el : el.selectFirst(parts[0].trim());
                        return target != null ? target.attr(parts[1].trim()) : "";
                    } else {
                        Element target = el.selectFirst(realRule);
                        return target != null ? target.text() : "";
                    }
                }
            } else {
                // 🚀 關鍵點 2：調用凱哥 2.0 引擎
                // 此時 realRule 已經是替換好變量的完整規則了
                String content = (root instanceof Document) ? ((Document) root).outerHtml() : (root instanceof Element) ? ((Element) root).outerHtml() : root.toString();
                return KaiGeEngine.doExtract(content, realRule, this.siteUrl).value;
            }
            return "";
        } catch (Exception e) { return ""; }
    }

    private String replaceStepVars(String text) {
        if (text == null) return "";
        String res = text;
        for (String k : varPool.keySet()) res = res.replace("{" + k + "}", varPool.get(k));
        return res.replace("{host}", this.siteUrl);
    }

    private Map<String, String> getHeaders(JSONObject custom) {
        Map<String, String> hb = new HashMap<>();
        hb.put("User-Agent", rule.optString("ua", "Mozilla/5.0"));
        JSONObject hd = custom != null ? custom : rule.optJSONObject("headers");
        if (hd != null) {
            Iterator<String> keys = hd.keys();
            while (keys.hasNext()) {
                String k = keys.next();
                hb.put(k, replaceStepVars(hd.optString(k)));
            }
        }
        return hb;
    }

@Override
public String homeContent(boolean filter) {
    try {
        logger("🏠 [主頁] 正在加載分類導航...");
        JSONArray classes = rule.optJSONArray("classes");

        if (classes == null || classes.length() == 0) {
            logger("🚨 [主頁] 警告：JSON 規則中未定義 classes 或格式錯誤");
            return "";
        }

        // 🚀 凱哥特製：字段自動對接
        // 很多 JSON 寫的是 type_name/type_id，有些殼子要的是 name/id
        // 我們在這裡做一個轉換，保證 100% 顯示
        JSONArray resultClasses = new JSONArray();
        for (int i = 0; i < classes.length(); i++) {
            JSONObject oldCate = classes.getJSONObject(i);
            JSONObject newCate = new JSONObject();

            String name = oldCate.optString("type_name", oldCate.optString("name"));
            String id = oldCate.optString("type_id", oldCate.optString("id"));

            newCate.put("type_name", name);
            newCate.put("type_id", id);
            resultClasses.put(newCate);
        }

        logger("✅ [主頁] 分類加載成功，共 " + resultClasses.length() + " 個頻道");

        JSONObject result = new JSONObject();
        result.put("class", resultClasses);
        result.put("list", new JSONArray());

        // 如果規則裡有篩選數據(filters)，也可以在這裡放進去
        if (rule.has("filters")) {
            result.put("filters", rule.optJSONObject("filters"));
        }

        return result.toString();
    } catch (Exception e) {
        logger("🚨 [主頁異常]: " + e.getMessage());
        return "";
    }
}
    // ✅ 通用JSON路径取值，支持多级路径如 data.list、data.jiexiDataList
    private Object getJsonByPath(JSONObject json, String path) {
        try {
            Object current = json;
            for (String key : path.split("\\.")) {
                if (current instanceof JSONObject) {
                    current = ((JSONObject) current).opt(key);
                } else {
                    return null;
                }
            }
            return current;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * ✅ 弹幕入口方法
     * FongMi 框架播放视频时会调用此方法获取弹幕地址
     */
    public String danmaku(String url) throws Exception {
        String title = varPool.get("vod_name");
        String episode = varPool.get("vod_episode");

        // 兜底：从 URL 中提取集数
        if (TextUtils.isEmpty(episode) && url.contains("$")) {
            String epPart = url.split("\\$")[0];
            episode = epPart.replaceAll("[^0-9]", "");
        }

        if (TextUtils.isEmpty(title)) title = "未知标题";
        if (TextUtils.isEmpty(episode)) episode = "1";

        return Proxy.getUrl() + "?do=danmu&title=" + URLEncoder.encode(title, "UTF-8")
             + "&episode=" + URLEncoder.encode(episode, "UTF-8");
    }
        @Override
    public void destroy() {
        try {
            if (picExecutor != null && !picExecutor.isShutdown()) {
                picExecutor.shutdownNow();
                logger("🧹 [线程池] 图片多线程池已释放");
            }
            picCache.clear();
        } catch (Exception ignored) {}
    }
}
