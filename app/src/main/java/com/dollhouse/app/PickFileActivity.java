package com.dollhouse.app;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;

/**
 * 【职责】一次性选文件 Activity：图片与文本文件都从这里进来。
 *
 * 【交互】用静态 Listener 把结果回传给发起方（MainActivity 或 ChatPanel），随后立即 finish。
 *
 * 【坑】listener 是静态字段，属于「本进程内凑合能用」的写法：如果发起方在选文件过程中被回收，回调就会落空，所以用完必须置回 null。
 *
 * 本类由原 smali 反编译重建（jadx），行为与原始包保持一致。
 */
public class PickFileActivity extends Activity {
    private static final int MAX_TEXT_BYTES = 24576;
    private static final int REQ = 81;
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
        if (bundle != null) {
            finish();
            return;
        }
        try {
            Intent intent = new Intent("android.intent.action.GET_CONTENT");
            intent.addCategory("android.intent.category.OPENABLE");
            intent.setType("*/*");
            intent.putExtra("android.intent.extra.MIME_TYPES", new String[]{"image/*", "text/*", "application/json", "application/xml", "application/javascript"});
            startActivityForResult(Intent.createChooser(intent, "选图片或文本文件"), REQ);
        } catch (Throwable th) {
            fail("打不开文件选择器：" + th.getMessage());
        }
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
            finish();
            return;
        }
        final Uri data = intent.getData();
        final String valueOf = String.valueOf(intent.getType());
        final PickFileActivity.Listener listener2 = listener;
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
