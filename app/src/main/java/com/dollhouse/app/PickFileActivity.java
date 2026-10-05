package com.dollhouse.app;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.provider.MediaStore;

/**
 * 【职责】一次性选图 Activity：所有「选一张图」的入口（发图 / 聊天背景）都走这里。
 *
 * 【交互】用静态 Listener 把结果回传给发起方（MainActivity 或 ChatPanel），随后立即 finish。
 *
 * 【坑】listener 是静态字段，属于「本进程内凑合能用」的写法：如果发起方在选图过程中被回收，回调就会落空，所以用完必须置回 null。
 *
 * 【修·历史缺陷】原先本页走 GET_CONTENT 且 type 写成通配的任意文件，必然拉起文件管理器（用户找不到图片）；
 *   且 Manifest 上挂着 noHistory="true"，本页在被选择器覆盖的瞬间就被 finish，
 *   onActivityResult 永远收不到结果 —— 发图失效与聊天背景不生效共用这一个根因。两者均已修掉。
 *
 * 本类由原 smali 反编译重建（jadx），行为与原始包保持一致。
 */
public class PickFileActivity extends Activity {
    private static final int MAX_TEXT_BYTES = 24576;
    private static final int REQ = 81;
    private static final String LOG_TAG = "DollhousePick";
    private static PickFileActivity.Listener listener = null;
    private static String purpose = "chat_img";

    public interface Listener {
        void onFailed(String str);

        void onPicked(String str, String str2);
    }

    public static void setListener(PickFileActivity.Listener listener2) {
        listener = listener2;
    }

    public static void setPurpose(String str) {
        if (str == null) {
            str = ImageStore.DIR;
        }
        purpose = str;
    }

    public static void start(Context context) {
        Intent intent = new Intent(context, (Class<?>) PickFileActivity.class);
        intent.setFlags(335544320);
        context.startActivity(intent);
    }

    @Override
    protected void onCreate(Bundle bundle) {
        super.onCreate(bundle);
        // 【修·选图回调丢失】原实现在重建时直接 finish()，配合 Manifest 的 noHistory 会让本页
        //   在系统相册覆盖它的瞬间就死掉，结果永远回不来。现在改成「重建不自尽、也不重弹」：
        //   只等系统把选择结果投递进 onActivityResult。
        if (bundle != null) {
            Logs.i(LOG_TAG, "[重建] 跳过重复拉起选择器，等待结果");
            return;
        }
        try {
            startActivityForResult(pickImageIntent(), REQ);
        } catch (ActivityNotFoundException anf) {
            Logs.w(LOG_TAG, "照片选择器不可用，降级", anf);
            try {
                startActivityForResult(compatImageIntent(), REQ);
            } catch (ActivityNotFoundException anf2) {
                try {
                    startActivityForResult(legacyImageIntent(), REQ);
                } catch (Throwable th) {
                    fail("打不开相册：" + th.getMessage());
                }
            }
        } catch (Throwable th) {
            fail("打不开相册：" + th.getMessage());
        }
    }

    /** Android 13+ 原生照片选择器；10~12 上由 Google Play 服务回填，无需存储权限。 */
    private Intent pickImageIntent() {
        Intent intent = new Intent("android.provider.action.PICK_IMAGES");
        intent.setType("image/*");
        return intent;
    }

    /** 回退一：只认图片的 GET_CONTENT —— 由相册/图库应用接单，不再进文件管理器。 */
    private Intent compatImageIntent() {
        Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("image/*");
        return intent;
    }

    /** 回退二：传统相册 ACTION_PICK。 */
    private Intent legacyImageIntent() {
        Intent intent = new Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI);
        intent.setType("image/*");
        return intent;
    }

    private void fail(String str) {
        PickFileActivity.Listener listener2 = listener;
        if (listener2 != null) {
            listener2.onFailed(str);
        }
        finish();
    }

    @Override
    protected void onActivityResult(int i, int i2, Intent intent) {
        super.onActivityResult(i, i2, intent);
        if (i != REQ) {
            return;
        }
        if (i2 != -1 || intent == null || intent.getData() == null) {
            Logs.i(LOG_TAG, "[取消] 没选图，直接收尾");
            finish();
            return;
        }
        final Uri data = intent.getData();
        final String valueOf = String.valueOf(intent.getType());
        final PickFileActivity.Listener listener2 = listener;
        Logs.i(LOG_TAG, "[回传] type=" + valueOf + " purpose=" + purpose);
        new Thread(new Runnable() {
            @Override
            public void run() {
                String imgPath = null;
                String textOut = null;
                String errOut = null;
                try {
                    if (PickFileActivity.this.isImage(data, valueOf)) {
                        imgPath = ImageStore.saveFromUri(PickFileActivity.this, data, PickFileActivity.purpose);
                    } else {
                        textOut = ImageStore.readTextFile(PickFileActivity.this, data, PickFileActivity.MAX_TEXT_BYTES);
                        if (textOut == null) {
                            errOut = "这个文件既不是图片，也读不出文本内容";
                        }
                    }
                } catch (Throwable th) {
                    errOut = th.getMessage() == null ? th.getClass().getSimpleName() : th.getMessage();
                }
                final String fImg = imgPath;
                final String fText = textOut;
                final String fErr = errOut;
                PickFileActivity.this.runOnUiThread(new Runnable() {                    @Override
                    public void run() {
                        if (listener2 != null) {
                            if (fErr != null) {
                                listener2.onFailed(fErr);
                            } else {
                                listener2.onPicked(fImg, fText);
                            }
                        }
                        PickFileActivity.this.finish();
                    }
                });
            }
        }, "feiyu-pick").start();
    }

    public boolean isImage(Uri uri, String str) {
        if (str != null && str.startsWith("image/")) {
            return true;
        }
        try {
            String type = getContentResolver().getType(uri);
            if (type != null) {
                if (type.startsWith("image/")) {
                    return true;
                }
            }
        } catch (Throwable unused) {
        }
        return ImageStore.looksLikeImage(this, uri);
    }
}
