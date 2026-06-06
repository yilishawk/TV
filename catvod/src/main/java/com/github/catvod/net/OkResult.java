package com.github.catvod.net;

import java.util.List;
import java.util.Map;

public class OkResult {

    private final String body;
    private final Map<String, List<String>> resp;
    private final int code;

    public OkResult(String body, Map<String, List<String>> resp, int code) {
        this.body = body;
        this.resp = resp;
        this.code = code;
    }

    public String getBody() {
        return body != null ? body : "";
    }

    public Map<String, List<String>> getResp() {
        return resp;
    }

    public int getCode() {
        return code;
    }
}
