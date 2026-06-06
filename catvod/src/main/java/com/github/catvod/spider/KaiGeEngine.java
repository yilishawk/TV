package com.github.catvod.spider;

import android.text.TextUtils;
import android.util.Base64;

import com.github.catvod.utils.AESEncryption;
import com.github.catvod.utils.Util;

import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import java.io.StringReader;
import java.net.URLDecoder;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.xml.xpath.XPath;
import javax.xml.xpath.XPathConstants;
import javax.xml.xpath.XPathFactory;

public class KaiGeEngine {

    private static boolean isEmpty(String str) {
        return str == null || str.length() == 0;
    }

    public static ExtractionResult doExtract(String html, String rule, String host) {
        ExtractionResult result = new ExtractionResult();
        if (isEmpty(html) || isEmpty(rule)) return result;

        String[] segments = rule.split("\\s*;;\\s*");
        String coreLogic = segments[0].trim();

        for (int i = 1; i < segments.length; i++) {
            String tag = segments[i].trim();
            if (tag.equalsIgnoreCase("[full]")) result.shouldFull = true;
            if (tag.matches("\\[\\d+\\]")) {
                result.index = Integer.parseInt(tag.replaceAll("[\\[\\]]", ""));
            }
            if (tag.startsWith("[包含:")) result.includeKey = tag.substring(4, tag.length() - 1);
            if (tag.startsWith("[排除:")) result.excludeKey = tag.substring(4, tag.length() - 1);
        }

        String finalValue = "";

        // ✅ xpath 模式：用 Android 内置 javax.xml.xpath 处理
        if (coreLogic.startsWith("xpath:")) {
            try {
                String xpathQuery = coreLogic.substring(6).trim();
                XPath xpath = XPathFactory.newInstance().newXPath();
                InputSource source = new InputSource(new StringReader(html));
                NodeList nodes = (NodeList) xpath.evaluate(xpathQuery, source, XPathConstants.NODESET);
                if (nodes != null && nodes.getLength() > 0) {
                    StringBuilder sb = new StringBuilder();
                    if (result.index >= 0 && result.index < nodes.getLength()) {
                        sb.append(nodes.item(result.index).getTextContent().trim());
                    } else {
                        for (int i = 0; i < nodes.getLength(); i++) {
                            if (sb.length() > 0) sb.append("$$$");
                            sb.append(nodes.item(i).getTextContent().trim());
                        }
                    }
                    result.value = sb.toString();
                }
            } catch (Exception e) {
                result.value = "";
            }
            return result;
        }

        if (coreLogic.contains(">")) {
            String[] steps = coreLogic.split("\\s*>\\s*");
            finalValue = html;
            for (String step : steps) {
                finalValue = processStep(finalValue, step.trim(), host);
            }
        } else {
            finalValue = processStep(html, coreLogic, host);
        }

        if (!isEmpty(result.includeKey) && !finalValue.contains(result.includeKey)) finalValue = "";
        if (!isEmpty(result.excludeKey) && finalValue.contains(result.excludeKey)) finalValue = "";
        if (result.shouldFull && !isEmpty(finalValue)) finalValue = autoFullUrl(finalValue, host);
        result.value = finalValue;
        return result;
    }

    private static String processStep(String content, String step, String host) {
        if (isEmpty(step)) return content;

        if (step.contains("json:")) {
            try {
                String path = step.replace("[", "").replace("]", "").replace("json:", "").trim();
                String cleanContent = content.replace("\\/", "/");
                org.json.JSONObject obj = new org.json.JSONObject(cleanContent);
                if (path.contains(".")) {
                    String[] keys = path.split("\\.");
                    Object current = obj;
                    for (int i = 0; i < keys.length; i++) {
                        if (i == keys.length - 1) {
                            return ((org.json.JSONObject) current).optString(keys[i], "");
                        } else {
                            current = ((org.json.JSONObject) current).optJSONObject(keys[i]);
                            if (current == null) return "";
                        }
                    }
                }
                return obj.optString(path, "");
            } catch (Exception e) {
                String rawPath = step.replace("[", "").replace("]", "").replace("json:", "").trim();
                String keyName = rawPath.contains(".") ? rawPath.substring(rawPath.lastIndexOf(".") + 1) : rawPath;
                java.util.regex.Matcher m = java.util.regex.Pattern.compile("\"" + keyName + "\"\\s*:\\s*\"(.*?)\"").matcher(content);
                return m.find() ? m.group(1).replace("\\/", "/") : "";
            }
        }

        if (step.equalsIgnoreCase("[base64]")) {
            try { return new String(Base64.decode(content, Base64.DEFAULT)); } catch (Exception e) { return content; }
        }
        if (step.equalsIgnoreCase("[url_decode]")) {
            try { return URLDecoder.decode(content, "UTF-8"); } catch (Exception e) { return content; }
        }
        if (step.startsWith("[reg:")) {
            java.util.regex.Matcher m = java.util.regex.Pattern.compile(step.substring(5, step.length() - 1)).matcher(content);
            return m.find() ? m.group(1).trim() : "";
        }
        if (step.startsWith("[提取:") && step.endsWith("]")) {
            return executeSingleRule(content, step.substring(4, step.length() - 1));
        }
        if (step.startsWith("[替换:") && step.endsWith("]")) {
            try {
                String params = step.substring(4, step.length() - 1);
                int arrow = params.indexOf(">");
                if (arrow > -1) return content.replace(params.substring(0, arrow), params.substring(arrow + 1));
            } catch (Exception e) { return content; }
        }
        if (step.startsWith("[排序:") && step.endsWith("]")) {
            try {
                String[] order = step.substring(4, step.length() - 1).split(">");
                int idx = content.indexOf("?");
                String base = idx > -1 ? content.substring(0, idx + 1) : "";
                String query = idx > -1 ? content.substring(idx + 1) : content;
                String[] pairs = query.split("&");
                StringBuilder sb = new StringBuilder(base);
                for (String o : order) {
                    int pos = Integer.parseInt(o.trim()) - 1;
                    if (pos >= 0 && pos < pairs.length) {
                        if (sb.length() > base.length()) sb.append("&");
                        sb.append(pairs[pos]);
                    }
                }
                return sb.toString();
            } catch (Exception e) { return content; }
        }
        if (step.equalsIgnoreCase("[time]")) {
            return String.valueOf(System.currentTimeMillis() / 1000);
        }
        if (step.equalsIgnoreCase("[time13]")) {
            return String.valueOf(System.currentTimeMillis());
        }
        if (step.equalsIgnoreCase("[sort_params]")) {
            return sortQueryString(content);
        }
        if (step.equalsIgnoreCase("[md5]")) {
            return Util.md5(content);
        }
        if (step.equalsIgnoreCase("[sha1]")) {
            try {
                java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-1");
                byte[] bytes = md.digest(content.getBytes("UTF-8"));
                StringBuilder sb = new StringBuilder();
                for (byte b : bytes) sb.append(String.format("%02x", b));
                return sb.toString();
            } catch (Exception e) { return ""; }
        }
        if (step.startsWith("[aes_cbc:") && step.endsWith("]")) {
            try {
                String[] p = step.substring(9, step.length() - 1).split(",");
                String key = p[0].trim();
                String iv = (p.length > 1) ? p[1].trim() : "";
                return AESEncryption.decrypt(content, key, iv, AESEncryption.CBC_PKCS_7_PADDING);
            } catch (Exception e) { return ""; }
        }
        if (step.startsWith("[aes_ecb:") && step.endsWith("]")) {
            try {
                String key = step.substring(9, step.length() - 1).trim();
                return AESEncryption.decrypt(content, key, "", AESEncryption.ECB_PKCS_7_PADDING);
            } catch (Exception e) { return ""; }
        }
        if (step.contains("+")) {
            return handleCombination(content, step, host);
        }
        return executeSingleRule(content, step);
    }

    private static String sortQueryString(String url) {
        try {
            int idx = url.indexOf("?");
            if (idx < 0) return url;
            String base = url.substring(0, idx + 1);
            String query = url.substring(idx + 1);
            String[] pairs = query.split("&");
            java.util.Arrays.sort(pairs);
            return base + String.join("&", pairs);
        } catch (Exception e) { return url; }
    }

    private static String executeSingleRule(String html, String rule) {
        if (rule.contains("@")) {
            String[] parts = rule.split("@");
            String attrName = parts[parts.length - 1].trim();
            Pattern p = Pattern.compile(attrName + "\\s*=\\s*[\"']([^\"']*)[\"']", Pattern.CASE_INSENSITIVE);
            Matcher m = p.matcher(html);
            if (m.find()) return m.group(1).trim();
            return "";
        }
        if (rule.contains("&&")) {
            String[] parts = rule.split("&&");
            String start = parts[0].trim();
            String end = parts.length > 1 ? parts[1].trim() : "";
            return start.contains("*") ? cutWithWildcard(html, start, end) : simpleCut(html, start, end);
        }
        return html;
    }

    private static String handleCombination(String html, String logic, String host) {
        String[] parts = logic.split("\\s*\\+\\s*");
        StringBuilder sb = new StringBuilder();
        for (String p : parts) {
            String item = p.trim();
            if (item.startsWith("\"") && item.endsWith("\"") && item.length() >= 2) {
                sb.append(item.substring(1, item.length() - 1));
            } else if (item.contains("@") || item.contains("&&")) {
                sb.append(executeSingleRule(html, item));
            } else {
                sb.append(item);
            }
        }
        return sb.toString();
    }

    private static String cutWithWildcard(String html, String startRule, String end) {
        try {
            String regexStart = Pattern.quote(startRule).replace("*", "\\E.*?\\Q");
            String fullRegex = regexStart + "(.*?)" + (isEmpty(end) ? "$" : Pattern.quote(end));
            Matcher matcher = Pattern.compile(fullRegex, Pattern.DOTALL).matcher(html);
            return matcher.find() ? matcher.group(1).trim() : "";
        } catch (Exception e) { return ""; }
    }

    private static String simpleCut(String html, String start, String end) {
        try {
            int s = html.indexOf(start);
            if (s > -1) {
                s += start.length();
                if (isEmpty(end)) return html.substring(s).trim();
                int e = html.indexOf(end, s);
                if (e > -1) return html.substring(s, e).trim();
            }
        } catch (Exception e) { return ""; }
        return "";
    }

    private static String autoFullUrl(String path, String host) {
        if (isEmpty(path) || path.startsWith("http")) return path;
        if (isEmpty(host)) return path;
        if (path.startsWith("//")) return "https:" + path;
        if (path.startsWith("/")) return host.endsWith("/") ? host + path.substring(1) : host + path;
        return host + (host.endsWith("/") ? "" : "/") + path;
    }

    public static class ExtractionResult {
        public String value = "";
        public boolean shouldFull = false;
        public int index = 0;
        public String includeKey = "";
        public String excludeKey = "";
    }
}
