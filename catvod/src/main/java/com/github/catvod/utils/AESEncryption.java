package com.github.catvod.utils;

import android.util.Base64;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

public class AESEncryption {

    public static final String CBC_PKCS_7_PADDING = "AES/CBC/PKCS7Padding";
    public static final String ECB_PKCS_7_PADDING = "AES/ECB/PKCS7Padding";

    public static String decrypt(String content, String key, String iv, String mode) {
        try {
            byte[] keyBytes = fixKey(key.getBytes("UTF-8"));
            SecretKeySpec keySpec = new SecretKeySpec(keyBytes, "AES");
            Cipher cipher = Cipher.getInstance(mode);
            if (mode.contains("CBC")) {
                byte[] ivBytes = iv.isEmpty() ? new byte[16] : fixKey(iv.getBytes("UTF-8"));
                cipher.init(Cipher.DECRYPT_MODE, keySpec, new IvParameterSpec(ivBytes));
            } else {
                cipher.init(Cipher.DECRYPT_MODE, keySpec);
            }
            byte[] decoded = Base64.decode(content, Base64.DEFAULT);
            return new String(cipher.doFinal(decoded), "UTF-8");
        } catch (Exception e) {
            return "";
        }
    }

    public static String encrypt(String content, String key, String iv, String mode) {
        try {
            byte[] keyBytes = fixKey(key.getBytes("UTF-8"));
            SecretKeySpec keySpec = new SecretKeySpec(keyBytes, "AES");
            Cipher cipher = Cipher.getInstance(mode);
            if (mode.contains("CBC")) {
                byte[] ivBytes = iv.isEmpty() ? new byte[16] : fixKey(iv.getBytes("UTF-8"));
                cipher.init(Cipher.ENCRYPT_MODE, keySpec, new IvParameterSpec(ivBytes));
            } else {
                cipher.init(Cipher.ENCRYPT_MODE, keySpec);
            }
            byte[] encrypted = cipher.doFinal(content.getBytes("UTF-8"));
            return Base64.encodeToString(encrypted, Base64.NO_WRAP);
        } catch (Exception e) {
            return "";
        }
    }

    private static byte[] fixKey(byte[] key) {
        if (key.length == 16 || key.length == 24 || key.length == 32) return key;
        byte[] fixed = new byte[16];
        System.arraycopy(key, 0, fixed, 0, Math.min(key.length, 16));
        return fixed;
    }
}
