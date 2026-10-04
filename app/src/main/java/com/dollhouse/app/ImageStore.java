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

    public static boolean looksLikeImage(Context context, Uri uri) {
        InputStream inputStream = null;
        try {
            InputStream openInputStream = context.getContentResolver().openInputStream(uri);
            if (openInputStream == null) {
                if (openInputStream != null) {
                    try {
                        openInputStream.close();
                    } catch (Throwable unused) {
                    }
                }
                return false;
            }
            byte[] bArr = new byte[12];
            int read = openInputStream.read(bArr);
            if (read < 4) {
                if (openInputStream != null) {
                    try {
                        openInputStream.close();
                    } catch (Throwable unused2) {
                    }
                }
                return false;
            }
            byte b = bArr[0];
            int i = b & 255;
            byte b2 = bArr[1];
            int i2 = b2 & 255;
            if (i == 255 && i2 == 216) {
                if (openInputStream != null) {
                    try {
                        openInputStream.close();
                    } catch (Throwable unused3) {
                    }
                }
                return true;
            }
            if (i == 137 && b2 == MAX_FILES && bArr[2] == 78 && bArr[3] == 71) {
                if (openInputStream != null) {
                    try {
                        openInputStream.close();
                    } catch (Throwable unused4) {
                    }
                }
                return true;
            }
            if (b == 71 && b2 == 73 && bArr[2] == 70) {
                if (openInputStream != null) {
                    try {
                        openInputStream.close();
                    } catch (Throwable unused5) {
                    }
                }
                return true;
            }
            if (b == 66 && b2 == 77) {
                if (openInputStream != null) {
                    try {
                        openInputStream.close();
                    } catch (Throwable unused6) {
                    }
                }
                return true;
            }
            if (read >= 12 && b == 82 && b2 == 73 && bArr[2] == 70 && bArr[3] == 70 && bArr[8] == 87 && bArr[9] == 69 && bArr[10] == 66) {
                if (bArr[11] == MAX_FILES) {
                    if (openInputStream != null) {
                        try {
                            openInputStream.close();
                        } catch (Throwable unused7) {
                        }
                    }
                    return true;
                }
            }
            if (openInputStream != null) {
                try {
                    openInputStream.close();
                } catch (Throwable unused8) {
                }
            }
            return false;
        } catch (Throwable unused9) {
            if (0 != 0) {
                try {
                    inputStream.close();
                } catch (Throwable unused10) {
                }
            }
            return false;
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
