package com.fongmi.chaquo;

import android.content.Context;

import com.chaquo.python.PyException;
import com.chaquo.python.android.AndroidPlatform;
import com.chaquo.python.Python;

public class PyWarmup {

    private static volatile boolean warmed = false;

    /**
     * 在后台线程调用，提前初始化 Python 解释器并预 import 常用模块。
     * App 启动时调用一次，用户点 PY 规则时即可秒开。
     */
    public static void warmup(Context context) {
        if (warmed) return;
        try {
            // 初始化 Python 解释器（只能调用一次，重复调用无害会跳过）
            if (!Python.isStarted()) {
                Python.start(new AndroidPlatform(context));
            }
            Python py = Python.getInstance();

            // 预 import 耗时最长的几个包，后续调用直接从缓存取
            py.getModule("requests");
            py.getModule("lxml");
            py.getModule("pyquery");
            py.getModule("bs4");
            py.getModule("Crypto");

            warmed = true;
        } catch (PyException e) {
            // import 失败不影响正常流程，下次用到时再加载
            e.printStackTrace();
        } catch (Throwable e) {
            e.printStackTrace();
        }
    }
}
