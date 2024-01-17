package org.example;

import java.text.SimpleDateFormat;
import java.util.Date;

class Logging {
    private static SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS");

    static void log(String msg) {
        System.out.println(getLogMessage(msg));
    }

    static void log(String msg, Throwable e) {
        System.out.println(getLogMessage(msg) + "\n" + e);
    }

    private static String getLogMessage(String msg) {
        return dateFormat.format(new Date()) + " [" + Thread.currentThread().getName() + "] :: " + msg;
    }
}
