package com.fongmi.chaquo;

import com.chaquo.python.PyException;
import com.chaquo.python.Python;

public class PyWarmup {

    private static volatile boolean warmed = false;

    public static void warmup() {
        if (warmed) return;
        try {
            Python py = Python.getInstance();
            py.getModule("requests");
            py.getModule("lxml");
            py.getModule("pyquery");
            py.getModule("bs4");
            py.getModule("Crypto");
        } catch (PyException e) {
            e.printStackTrace();
        } catch (Throwable e) {
            e.printStackTrace();
        }
        warmed = true;
    }
}
