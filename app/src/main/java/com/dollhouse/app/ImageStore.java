package com.dollhouse.app;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.util.Base64;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.Arrays;
import java.util.Comparator;

/**
 * 【职责】聊天附件与应用内图片的本地落盘管理（应用私有目录 chat_img）。
 *
 * 【交互】PickFileActivity 选文件 → 这里存盘；ChatPanel 读回来发请求。
 *
 * 【坑】MAX_FILES / MAX_DIM 是自动清理阈值：超量会按时间删旧图，改小要当心把用户还在用的图删掉。
 *
 * 本类由原 smali 反编译重建（jadx），行为与原始包保持一致。
 */
public final class ImageStore {
    public static final String DIR = "chat_img";
    private static final int MAX_DIM = 1024;
    private static final int MAX_FILES = 80;

    private ImageStore() {
    }
    /** 【背景裁剪】读原图的长宽，失败返回 null；只读尺寸，不解码像素。 */
    public static int[] size(Context context, Uri uri) {
        try {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            InputStream in = context.getContentResolver().openInputStream(uri);
            if (in == null) {
                return null;
            }
            BitmapFactory.decodeStream(in, null, o);
            in.close();
            return (o.outWidth <= 0 || o.outHeight <= 0) ? null : new int[]{o.outWidth, o.outHeight};
        } catch (Throwable t) {
            return null;
        }
    }
    /**
     * 【背景裁剪】把选区裁出来、按目标比例缩放后存成聊天背景。
     * 【入参与出参】src 是相册回传的 Uri；框内坐标为源图像素；ratio = 宽/高。
     * 【为什么在这里落盘】与 saveFromUri 同一条通道，落盘后返回「chat_bg/img_xxx.jpg」相对路径，
     *   背景行读到的还是同一个相对路径，下游（ChatPanel.applyBackground / PetService）无需改动。
     */
    public static String saveCrop(Context context, Uri src, int left, int top, int width, int height,
                                 float ratio, int outW) throws Exception {
        Bitmap full = null;
        try {
            InputStream in = context.getContentResolver().openInputStream(src);
            if (in == null) {
                throw new IllegalStateException("读不到这张图");
            }
            full = BitmapFactory.decodeStream(in, null, null);
            in.close();
            if (full == null) {
                throw new IllegalStateException("这张图解不开");
            }
            return saveCropFrom(context, full, left, top, width, height, ratio, outW);
        } finally {
            if (full != null) {
                full.recycle();
            }
        }
    }
    /**
     * 【背景裁剪·位图版】直接吃一张已经解好的图，不再二次解码（裁剪页就是这么调的）。
     * 【注意】不回收传入的 full —— 它归调用方所有（裁剪页在 onDestroy 里自己释放）。
     */
    public static String saveCropFrom(Context context, Bitmap full, int left, int top, int width, int height,
                                      float ratio, int outW) throws Exception {
        if (full == null || full.isRecycled()) {
            throw new IllegalStateException("这张图解不开");
        }
        Bitmap crop = null;
        Bitmap scaled = null;
        FileOutputStream out = null;
        try {
            int l = Math.max(0, left);
            int t = Math.max(0, top);
            int w = Math.max(1, Math.min(width, full.getWidth() - l));
            int h = Math.max(1, Math.min(height, full.getHeight() - t));
            crop = Bitmap.createBitmap(full, l, t, w, h);
            int tw = Math.max(1, Math.min(1080, outW));
            int th = Math.max(1, Math.round(tw / Math.max(0.01f, ratio)));
            scaled = Bitmap.createScaledBitmap(crop, tw, th, true);
            File file = new File(dir(context, PetPrefs.BG_DIR), "img_" + System.currentTimeMillis() + ".jpg");
            out = new FileOutputStream(file);
            scaled.compress(Bitmap.CompressFormat.JPEG, 90, out);
            out.flush();
            return PetPrefs.BG_DIR + "/" + file.getName();
        } finally {
            try {
                if (out != null) {
                    out.close();
                }
            } catch (Throwable ignored) {
            }
            if (scaled != null && scaled != crop) {
                scaled.recycle();
            }
            if (crop != null && crop != full) {
                crop.recycle();
            }
        }
    }

    public static File dir(Context context) {
        return dir(context, DIR);
    }

    public static File dir(Context context, String str) {
        File file = new File(context.getFilesDir(), str);
        if (!file.isDirectory()) {
            file.mkdirs();
        }
        return file;
    }

    public static File fileFor(Context context, String str) {
        return new File(context.getFilesDir(), str);
    }

    public static boolean exists(Context context, String str) {
        return (str == null || str.isEmpty() || !fileFor(context, str).isFile()) ? false : true;
    }

    // 从外部 Uri 落盘一张图并返回内部文件名。
    public static String saveFromUri(Context context, Uri uri) throws Exception {
        return saveFromUri(context, uri, DIR);
    }

    public static String saveFromUri(Context context, Uri uri, String str) throws Exception {
        Bitmap decodeScaled = decodeScaled(context, uri, MAX_DIM);
        if (decodeScaled == null) {
            throw new IllegalStateException("这个文件不是图片，或者解不开");
        }
        File file = new File(dir(context, str), "img_" + System.currentTimeMillis() + ".jpg");
        FileOutputStream fileOutputStream = new FileOutputStream(file);
        decodeScaled.compress(Bitmap.CompressFormat.JPEG, 85, fileOutputStream);
        fileOutputStream.flush();
        fileOutputStream.close();
        decodeScaled.recycle();
        prune(context, str);
        return str + "/" + file.getName();
    }

    private static Bitmap decodeScaled(Context context, Uri uri, int i) throws Exception {
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inJustDecodeBounds = true;
        InputStream openInputStream = context.getContentResolver().openInputStream(uri);
        if (openInputStream == null) {
            return null;
        }
        BitmapFactory.decodeStream(openInputStream, null, options);
        openInputStream.close();
        if (options.outWidth <= 0 || options.outHeight <= 0) {
            return null;
        }
        int i2 = 1;
        while (true) {
            int i3 = i * 2;
            if (options.outWidth / i2 <= i3 && options.outHeight / i2 <= i3) {
                break;
            }
            i2 *= 2;
        }
        BitmapFactory.Options options2 = new BitmapFactory.Options();
        options2.inSampleSize = i2;
        InputStream openInputStream2 = context.getContentResolver().openInputStream(uri);
        if (openInputStream2 == null) {
            return null;
        }
        Bitmap decodeStream = BitmapFactory.decodeStream(openInputStream2, null, options2);
        openInputStream2.close();
        if (decodeStream == null) {
            return null;
        }
        int width = decodeStream.getWidth();
        int height = decodeStream.getHeight();
        int max = Math.max(width, height);
        if (max <= i) {
            return decodeStream;
        }
        float f = (float) i / max;
        Bitmap createScaledBitmap = Bitmap.createScaledBitmap(decodeStream, Math.max(1, Math.round(width * f)), Math.max(1, Math.round(height * f)), true);
        if (createScaledBitmap != decodeStream) {
            decodeStream.recycle();
        }
        return createScaledBitmap;
    }

    // 把本地图片转成 data:image/...;base64 供接口直传。
    public static String dataUrl(Context context, String str) throws Exception {
        File fileFor = fileFor(context, str);
        if (!fileFor.isFile()) {
            throw new IllegalStateException("图片文件不见了");
        }
        int length = (int) fileFor.length();
        byte[] bArr = new byte[length];
        FileInputStream fileInputStream = new FileInputStream(fileFor);
        int i = 0;
        while (i < length) {
            int read = fileInputStream.read(bArr, i, length - i);
            if (read <= 0) {
                break;
            }
            i += read;
        }
        fileInputStream.close();
        return "data:image/jpeg;base64," + Base64.encodeToString(bArr, 2);
    }

    // 按目标边长加载并缩放位图，用于列表缩略图。
    public static Bitmap loadScaled(Context context, String str, int i) {
        try {
            File fileFor = fileFor(context, str);
            if (!fileFor.isFile()) {
                return null;
            }
            BitmapFactory.Options options = new BitmapFactory.Options();
            int i2 = 1;
            options.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(fileFor.getAbsolutePath(), options);
            while (true) {
                int i3 = i * 2;
                if (options.outWidth / i2 <= i3 && options.outHeight / i2 <= i3) {
                    BitmapFactory.Options options2 = new BitmapFactory.Options();
                    options2.inSampleSize = i2;
                    return BitmapFactory.decodeFile(fileFor.getAbsolutePath(), options2);
                }
                i2 *= 2;
            }
        } catch (Throwable unused) {
            return null;
        }
    }

    // 超过 MAX_FILES 时按时间删最旧的文件。
    private static void prune(Context context, String str) {
        File[] listFiles = dir(context, str).listFiles();
        if (listFiles == null || listFiles.length <= MAX_FILES) {
            return;
        }
        Arrays.sort(listFiles, new Comparator<File>() {            @Override
            public int compare(File file, File file2) {
                return Long.compare(file.lastModified(), file2.lastModified());
            }
        });
        for (int i = 0; i < listFiles.length - MAX_FILES; i++) {
            listFiles[i].delete();
        }
    }

    /* ------------------------- 文件签名常量（按魔数含义命名） ------------------------- */
    /** JPEG 起始两字节 FF D8。 */
    private static final int SIG_JPG_0 = 0xFF;
    private static final int SIG_JPG_1 = 0xD8;
    /** PNG 前四字节 89 50 4E 47。 */
    private static final int SIG_PNG_0 = 0x89;
    private static final int SIG_PNG_1 = 0x50;
    private static final int SIG_PNG_2 = 0x4E;
    private static final int SIG_PNG_3 = 0x47;
    /** GIF 前三字节 47 49 46。 */
    private static final int SIG_GIF_0 = 0x47;
    private static final int SIG_GIF_1 = 0x49;
    private static final int SIG_GIF_2 = 0x46;
    /** BMP 前两字节 42 4D。 */
    private static final int SIG_BMP_0 = 0x42;
    private static final int SIG_BMP_1 = 0x4D;
    /** WEBP：0-3 字节 RIFF，8-11 字节 WEBP。 */
    private static final int SIG_RIFF_0 = 0x52;
    private static final int SIG_RIFF_1 = 0x49;
    private static final int SIG_RIFF_2 = 0x46;
    private static final int SIG_WEBP_3 = 0x50;
    private static final int SIG_WEBP_8 = 0x57;
    private static final int SIG_WEBP_9 = 0x45;
    private static final int SIG_WEBP_10 = 0x42;
    /** 签名探测所需的头部字节数。 */
    private static final int HEAD_PROBE_BYTES = 12;

    /** 安静关流：失败无所谓，调用点原先各自抄了一遍「try/catch 吞掉」。 */
    private static void closeQuietly(InputStream in) {
        if (in != null) {
            try {
                in.close();
            } catch (Throwable ignored) {
            }
        }
    }

    /**
     * 按文件头魔数判断是否图片。
     * 【重写说明】原实现把 9 段关闭逻辑抄了 9 遍，且魔数被反编译工具误替成
     *   MAX_FILES / MAX_DIM（数值巧合相等但语义错位）。
     *   本次只做等价改写：判定顺序、比较值、短路行为与原来完全一致。
     */
    public static boolean looksLikeImage(Context context, Uri uri) {
        InputStream in = null;
        try {
            in = context.getContentResolver().openInputStream(uri);
            if (in == null) {
                return false;
            }
            byte[] head = new byte[HEAD_PROBE_BYTES];
            int read = in.read(head);
            if (read < 4) {
                return false;
            }
            int b0 = head[0] & 0xFF;
            int b1 = head[1] & 0xFF;
            if (b0 == SIG_JPG_0 && b1 == SIG_JPG_1) {
                return true;
            }
            if (b0 == SIG_PNG_0 && b1 == SIG_PNG_1 && head[2] == SIG_PNG_2 && head[3] == SIG_PNG_3) {
                return true;
            }
            if (b0 == SIG_GIF_0 && b1 == SIG_GIF_1 && head[2] == SIG_GIF_2) {
                return true;
            }
            if (b0 == SIG_BMP_0 && b1 == SIG_BMP_1) {
                return true;
            }
            if (read >= HEAD_PROBE_BYTES && b0 == SIG_RIFF_0 && b1 == SIG_RIFF_1
                    && head[2] == SIG_RIFF_2 && head[3] == SIG_RIFF_2
                    && head[8] == SIG_WEBP_8 && head[9] == SIG_WEBP_9 && head[10] == SIG_WEBP_10
                    && head[11] == SIG_WEBP_3) {
                return true;
            }
            return false;
        } catch (Throwable ignored) {
            return false;
        } finally {
            closeQuietly(in);
        }
    }

    // 读取文本类附件（限制最大字节数，超出即拒绝）。
    public static String readTextFile(Context context, Uri uri, int i) throws Exception {
        InputStream openInputStream = context.getContentResolver().openInputStream(uri);
        if (openInputStream == null) {
            return null;
        }
        ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
        byte[] bArr = new byte[8192];
        int i2 = 0;
        do {
            int read = openInputStream.read(bArr);
            if (read <= 0) {
                break;
            }
            byteArrayOutputStream.write(bArr, 0, read);
            i2 += read;
        } while (i2 < i);
        openInputStream.close();
        byte[] byteArray = byteArrayOutputStream.toByteArray();
        for (byte b : byteArray) {
            if (b == 0) {
                return null;
            }
        }
        String str = new String(byteArray, "UTF-8");
        if (byteArray.length < i) {
            return str;
        }
        return str + "\n…（文件太长，只读了前 " + (i / MAX_DIM) + " KB）";
    }
}
