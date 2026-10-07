package com.dollhouse.app;

import android.content.Context;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import java.nio.charset.Charset;
import java.security.KeyStore;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * 【职责】API 密钥的加密存储：明文绝不落盘。
 *
 * 【实现】AndroidKeyStore 生成一把不可导出的 AES-256 密钥，用它做 AES/GCM/NoPadding 加密；
 *        落盘格式固定为 enc:1:&lt;base64(iv ‖ 密文)&gt;，前缀里的 1 是版本号，将来换算法好升级。
 *
 * 【坑】① 密钥在 KeyStore 里，同一台设备同一应用写进去还能解出来；换设备 / 清数据后解不开，
 *         此时一律返回空串让用户重填，绝不抛异常（否则整页崩）。
 *        ② 解密失败不等于数据损坏 —— 也可能只是这条数据本来就是别的设备上写的。
 *        ③ 本类不写任何日志输出明文，日志里只看长度。
 */
public final class KeyVault {
    private static final String TAG = "Dollhouse";
    private static final String STORE = "AndroidKeyStore";
    private static final String ALIAS = "dollhouse_kv_1";
    private static final String TRANSFORM = "AES/GCM/NoPadding";
    private static final int TAG_BITS = 128;
    private static final int IV_BYTES = 12;
    /** 密文前缀：版本 1。 */
    public static final String PREFIX = "enc:1:";
    private static final Charset UTF8 = Charset.forName("UTF-8");

    private KeyVault() {
    }

    /** 是否已经是本类产出的密文。 */
    public static boolean isEncrypted(String s) {
        return s != null && s.startsWith(PREFIX);
    }

    /**
     * 加密：成功返回 enc:1:...；失败返回空串（调用方据此拒绝保存密钥）。
     * 【为什么失败不给明文兜底】宁可让用户重试一次，也不能悄悄把明文写进磁盘。
     */
    public static String encrypt(String plain) {
        String p = plain == null ? "" : plain;
        if (p.isEmpty()) {
            return "";
        }
        if (isEncrypted(p)) {
            return p;
        }
        try {
            Cipher c = Cipher.getInstance(TRANSFORM);
            // 【修·必须】绝不能自己造 IV 再传给 Keystore 加密。
            //   本类的密钥由 secretKey() 用 setRandomizedEncryptionRequired(true) 生成，
            //   该模式下 Keystore 强制「IV 必须由系统随机生成」；调用方一旦传入自备 IV，
            //   Cipher.init 会抛 InvalidAlgorithmParameterException: Caller-provided IV not permitted。
            //   老实现正是 nextBytes 自造 IV 传进去，异常又被本方法的 catch 吞掉返回空串，
            //   结果是「保存密钥」永远失败（页面提示：密钥加密失败，未能保存），密文从未写成功过。
            //   正确姿势：init 时不传 IV，再由 getIV() 取回系统生成的那个，随密文一起落盘。
            c.init(Cipher.ENCRYPT_MODE, secretKey());
            byte[] iv = c.getIV();
            if (iv == null || iv.length == 0) {
                Logs.w(TAG, "encrypt failed: keystore returned no iv", null);
                return "";
            }
            byte[] ct = c.doFinal(p.getBytes(UTF8));
            byte[] all = new byte[iv.length + ct.length];
            System.arraycopy(iv, 0, all, 0, iv.length);
            System.arraycopy(ct, 0, all, iv.length, ct.length);
            return PREFIX + Base64.encodeToString(all, Base64.NO_WRAP);
        } catch (Throwable t) {
            Logs.w(TAG, "encrypt failed len=" + p.length(), t);
            return "";
        }
    }

    /**
     * 解密：enc:1: 开头才解；不是密文就原样返回（兼容历史明文）。
     * 【失败语义】返回空串 = 解不开，上层应提示重新填写并用空密钥继续跑。
     */
    public static String decrypt(String stored) {
        String s = stored == null ? "" : stored;
        if (s.isEmpty()) {
            return "";
        }
        if (!isEncrypted(s)) {
            return s;
        }
        try {
            byte[] all = Base64.decode(s.substring(PREFIX.length()), Base64.NO_WRAP);
            if (all.length <= IV_BYTES) {
                return "";
            }
            byte[] iv = new byte[IV_BYTES];
            System.arraycopy(all, 0, iv, 0, IV_BYTES);
            byte[] ct = new byte[all.length - IV_BYTES];
            System.arraycopy(all, IV_BYTES, ct, 0, ct.length);
            Cipher c = Cipher.getInstance(TRANSFORM);
            c.init(Cipher.DECRYPT_MODE, secretKey(), new GCMParameterSpec(TAG_BITS, iv));
            return new String(c.doFinal(ct), UTF8);
        } catch (Throwable t) {
            // 不打印任何密文片段，只记长度，避免日志泄密。
            Logs.w(TAG, "decrypt failed len=" + s.length(), t);
            return "";
        }
    }

    /**
     * 打码：只留首尾各 4 位，中间用星号。
     * 【用途】导出文件与界面回显；长度不足 12 位时一律整段打码，别把短密钥露出来。
     */
    public static String mask(String plain) {
        String p = plain == null ? "" : plain.trim();
        if (p.isEmpty()) {
            return "";
        }
        if (p.length() < 12) {
            return "****";
        }
        StringBuilder sb = new StringBuilder();
        sb.append(p.substring(0, 4));
        for (int i = 0; i < 6; i++) {
            sb.append('*');
        }
        sb.append(p.substring(p.length() - 4));
        return sb.toString();
    }

    /**
     * 诊断一条存储值当前能不能用。
     * 【用途】发请求前先判断，别让「本地根本没有密钥」被服务端回成 401，
     *        再被用户读成「这个中转站不支持」。
     * 【返回】空串 = 正常可用；否则是一句可直接展示给用户的原因。
     */
    public static String problem(String stored) {
        String s = stored == null ? "" : stored;
        if (s.isEmpty()) {
            return "本地没有保存 API 密钥，请重新填写";
        }
        if (!isEncrypted(s)) {
            return "";
        }
        if (decrypt(s).isEmpty()) {
            return "保存的密钥解不开（可能换过设备或清过应用数据），请重新填写";
        }
        return "";
    }

    /** 取（或首次创建）KeyStore 里的 AES 密钥。 */
    private static SecretKey secretKey() throws Exception {
        KeyStore ks = KeyStore.getInstance(STORE);
        ks.load(null);
        java.security.Key existing = ks.getKey(ALIAS, null);
        if (existing instanceof SecretKey) {
            return (SecretKey) existing;
        }
        KeyGenerator kg = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, STORE);
        kg.init(new KeyGenParameterSpec.Builder(ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setRandomizedEncryptionRequired(true)
                .build());
        return kg.generateKey();
    }

    /** 供自检用：能不能加解密（Keystore 不可用时为 false）。 */
    public static boolean selfTest(Context context) {
        try {
            String enc = encrypt("dollhouse-probe");
            return isEncrypted(enc) && "dollhouse-probe".equals(decrypt(enc));
        } catch (Throwable t) {
            Logs.w(TAG, "selfTest failed", t);
            return false;
        }
    }
}