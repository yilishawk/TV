package com.github.catvod.spider;

import com.github.catvod.crawler.Spider;
import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.util.HashMap;
import java.util.Map;

public class Proxy extends Spider {
    private static StringBuilder sb = new StringBuilder("<div style='color:#888;'>--- 凱哥全能矩陣引擎已啟動 ---</div>");
    private static boolean isServerRunning = false;
    private static final int MY_LOG_PORT = 10086;

    public static int getPort() { return 9978; }
    public static String getUrl() { return "http://127.0.0.1:9978/proxy"; }

    public static void log(String msg) {
        if (msg == null) return;
        if (sb.length() > 200000) sb.delete(0, 100000);
        String time = new java.text.SimpleDateFormat("HH:mm:ss").format(new java.util.Date());
        sb.append("<div class='line'><span class='time'>[").append(time).append("]</span> ")
          .append("<span class='msg'>").append(msg).append("</span></div>");
        if (!isServerRunning) startLegacyServer();
    }

    private static void startLegacyServer() {
        if (isServerRunning) return;
        new Thread(() -> {
            try (ServerSocket server = new ServerSocket(MY_LOG_PORT)) {
                server.setReuseAddress(true);
                isServerRunning = true;
                while (true) {
                    try (Socket client = server.accept()) {
                        BufferedReader in = new BufferedReader(new InputStreamReader(client.getInputStream()));
                        String req = in.readLine();
                        if (req == null) continue;

                        try (OutputStream out = client.getOutputStream()) {
                            if (req.contains("/danmu")) {
                                String query = req.contains("?") ? req.split("\\?")[1].split(" ")[0] : "";
                                Map<String, String> qparams = new HashMap<>();
                                for (String kv : query.split("&")) {
                                    String[] pair = kv.split("=", 2);
                                    if (pair.length == 2) {
                                        try { qparams.put(pair[0], URLDecoder.decode(pair[1], "UTF-8")); } catch (Exception ignored) {}
                                    }
                                }
                                log("📨 [弹幕入口] 收到请求 title=" + qparams.get("title") + " | episode=" + qparams.get("episode"));
                                Object[] danmuResult = DanmuHelper.getDanmuResponse(qparams);
                                byte[] body = new byte[0];
                                if (danmuResult.length >= 3 && danmuResult[2] instanceof InputStream) {
                                    body = ((InputStream) danmuResult[2]).readAllBytes();
                                }
                                out.write("HTTP/1.1 200 OK\r\nContent-Type: application/xml; charset=utf-8\r\nConnection: close\r\n\r\n".getBytes());
                                out.write(body);
                                out.flush();
                            } else if (req.contains("/?clean")) {
                                sb.setLength(0);
                                sb.append("<div style='color:red;'>--- 日誌已手動清空 ---</div>");
                                out.write("HTTP/1.1 200 OK\r\n\r\nOK".getBytes());
                            } else if (req.contains("/get_logs")) {
                                String data = sb.toString();
                                String resp = "HTTP/1.1 200 OK\r\nContent-Type: text/plain; charset=utf-8\r\nConnection: close\r\n\r\n" + data;
                                out.write(resp.getBytes("UTF-8"));
                            } else {
                                String html = "HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\n\r\n" +
                                        "<html><head><meta charset='utf-8'><style>" +
                                        "body{background:#fff;color:#000;font-family:monospace;font-size:12px;margin:0;padding:10px;}" +
                                        ".header{position:sticky;top:0;background:#fff;padding:5px;border-bottom:1px solid #000;display:flex;justify-content:space-between;z-index:9;}" +
                                        ".time{color:#888;margin-right:5px;}.line{border-bottom:1px solid #eee;padding:2px 0;}" +
                                        "button{background:#000;color:#fff;border:none;padding:4px 8px;border-radius:3px;}" +
                                        "</style></head><body>" +
                                        "<div class='header'><b>📟 凱哥監聽</b><button onclick='clr()'>🧹 清空</button></div>" +
                                        "<div id='logs'>正在對接矩陣數據...</div>" +
                                        "<script>" +
                                        "function clr(){fetch('/?clean').then(()=>location.reload());}" +
                                        "let last = '';" +
                                        "setInterval(() => {" +
                                        "  fetch('/get_logs').then(r=>r.text()).then(data=>{" +
                                        "    if(data !== last) {" +
                                        "      document.getElementById('logs').innerHTML = data;" +
                                        "      last = data;" +
                                        "      window.scrollTo(0, document.body.scrollHeight);" +
                                        "    }" +
                                        "  });" +
                                        "}, 1000);" +
                                        "</script></body></html>";
                                out.write(html.getBytes("UTF-8"));
                            }
                            out.flush();
                        }
                    } catch (Exception ignored) {}
                }
            } catch (Exception e) { isServerRunning = false; }
        }).start();
    }

    /**
     * 注意：此方法不是重写父类方法，而是因为 TV 应用会通过反射调用它。
     * 不要加 @Override 注解，否则编译会失败。
     */
    public Object[] proxy(Map<String, String> params) {
    log("📨 [弹幕入口] 收到 proxy 调用: " + params);

    String doParam = params.get("do");
    if (doParam == null || !doParam.equals("danmu")) {
        log("❌ [弹幕入口] do 参数错误: " + doParam);
        return errorResponse(400, "Missing or invalid 'do' parameter");
    }

    String title = params.get("title");
    String episode = params.get("episode");
    if (title == null || title.isEmpty() || episode == null || episode.isEmpty()) {
        log("❌ [弹幕入口] 缺少 title 或 episode，原始params: " + params);
        return errorResponse(400, "Missing title or episode");
    }

    // ✅ decode 后写回 params
    try { title = URLDecoder.decode(title, "UTF-8"); params.put("title", title); } catch (Exception e) { log("⚠️ [弹幕入口] title decode失败: " + e.getMessage()); }
    try { episode = URLDecoder.decode(episode, "UTF-8"); params.put("episode", episode); } catch (Exception e) { log("⚠️ [弹幕入口] episode decode失败: " + e.getMessage()); }

    log("✅ [弹幕入口] decode后 title=" + title + " | episode=" + episode);
    return DanmuHelper.getDanmuResponse(params);
}

    private Object[] errorResponse(int code, String message) {
        Map<String, String> headers = new HashMap<>();
        headers.put("Content-Type", "text/plain; charset=utf-8");
        log("错误响应: " + code + " " + message);
        return new Object[]{code, "text/plain; charset=utf-8", 
                new ByteArrayInputStream(message.getBytes()), headers};
    }
}
