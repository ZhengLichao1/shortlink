package com.zlc.shortlink.util;

public class Base62 {

    private static final char[] CHARS =
            "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz".toCharArray();
    private static final  int BASE = 62;

    private Base62() {}

    /** 十进制序号 -> 62 进制短码 */
    public static String encode(long num) {
        StringBuilder sb = new StringBuilder();
        do {
            sb.append(CHARS[(int) (num % BASE)]);
            num /= BASE;
        }while (num > 0);
        return sb.reverse().toString();
    }
}
