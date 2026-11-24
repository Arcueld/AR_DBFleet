package com.arsenal.dbfleet.ui;

import java.util.Random;

/**
 * 输入框的示例连接串。
 * <p>
 * 主机用 RFC 5737 文档保留网段（192.0.2.0/24、198.51.100.0/24），账号密码每次随机生成。
 * 源码里不出现任何真实环境信息，本插件按公开分发处理。
 */
public final class SampleSupport {

    private static final String LOWER = "abcdefghijkmnpqrstuvwxyz";
    private static final String PWD_CHARS =
            "abcdefghijkmnpqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ23456789!@#%^&*";
    private static final String UPPER = "ABCDEFGHJKLMNPQRSTUVWXYZ";
    private static final String DIGITS = "23456789";

    private static final Random RND = new Random();

    private SampleSupport() {
    }

    public static String sample() {
        String host = "192.0.2." + between(1, 254);
        String dbHost = "198.51.100." + between(1, 254);
        String user = token(5, 8);

        return "[+] Postgres:" + host + ":5432:" + user + " '" + password(10, 14) + "'\n"
                + "[+] mssql " + host + ":1433:sa " + password(8, 12) + "\n"
                + "[+] oracle " + host + ":1521:sys " + password(8, 12) + "\n"
                + "[+] mysql " + host + ":3306:root " + password(8, 12) + "\n"
                + "[+] Redis " + host + ":6379 unauthorized\n"
                + "jdbc:postgresql://" + dbHost + ":5432/app?user=" + user
                + "&password=" + password(8, 12) + "\n"
                + host + ":1433 " + user + " / " + password(8, 12);
    }

    private static int between(int min, int max) {
        return min + RND.nextInt(max - min + 1);
    }

    private static String pick(String alphabet, int len) {
        StringBuilder sb = new StringBuilder(len);
        for (int i = 0; i < len; i++) {
            sb.append(alphabet.charAt(RND.nextInt(alphabet.length())));
        }
        return sb.toString();
    }

    private static String token(int min, int max) {
        return pick(LOWER, between(min, max));
    }

    /** 至少各含一个符号、一个大写、一个数字，示例更像真密码。 */
    private static String password(int min, int max) {
        int len = Math.max(between(min, max), 5);
        return pick(PWD_CHARS, len - 3) + '!'
                + pick(UPPER, 1)
                + pick(DIGITS, 1);
    }
}
