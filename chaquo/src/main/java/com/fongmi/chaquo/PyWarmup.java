package com.fongmi.chaquo;

import com.chaquo.python.PyException;
import com.chaquo.python.Python;

public class PyWarmup {

    private static volatile boolean warmed = false;

    public static void warmup() {
        if (warmed) return;
        try {
            Python py = Python.getInstance();
            py.getModule("app");
            py.getModule("runner");
            py.getModule("trigger");
        } catch (PyException e) {
            e.printStackTrace();
        } catch (Throwable e) {
            e.printStackTrace();
        }
        warmed = true;
    }
}
