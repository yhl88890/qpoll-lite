package com.qpoll.lite;

import android.os.IBinder;
import android.os.Parcel;

/**
 * 借 Shizuku 的 shell 身份发 Q-Poll 开关。
 *
 * <p><b>为什么不自己在 App 里发</b>：Android 16 起，这条厂商私有 NCI 指令被系统锁成了
 * {@code WRITE_SECURE_SETTINGS}（签名级权限），普通 App 永远拿不到；而 shell（UID 2000）
 * 天生就有，并且 Shizuku 拉起的进程不受「非 SDK 接口限制」约束，能反射到隐藏的
 * {@code INfcAdapter}。所以正确分工是：
 * <ul>
 *   <li><b>发 Q-Poll</b> → 交给 Shizuku（本类的 transact 5）</li>
 *   <li><b>收标签 + 传图</b> → 留在前台 App 里（12T Pro 那类 ROM 只认前台调用方）</li>
 * </ul>
 *
 * <p>老系统（Android 15 及以下）App 自己就能发，{@link FrontSession} 会先试自己，
 * 失败了才走到这里——所以 12T Pro 不需要 Shizuku 也能刷。
 */
final class ShizukuQtag {
    private static final String INTERFACE = "com.qpoll.lite.NfcSession";
    /** 后端不可用或调用失败时的返回值，跟 NFC 服务的错误码区分开。 */
    static final int UNAVAILABLE = Integer.MIN_VALUE;

    private static volatile IBinder bridge;

    private ShizukuQtag() { }

    static void attach(IBinder binder) {
        bridge = binder;
    }

    static void detach() {
        bridge = null;
    }

    static boolean available() {
        IBinder binder = bridge;
        return binder != null && binder.isBinderAlive();
    }

    /** @return NFC 服务的原始返回值（0 = 接受）；{@link #UNAVAILABLE} 表示这条通道用不了。 */
    static int send(int action) {
        IBinder binder = bridge;
        if (binder == null || !binder.isBinderAlive()) return UNAVAILABLE;
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(INTERFACE);
            data.writeInt(action);
            binder.transact(5, data, reply, 0);
            reply.readException();
            return reply.readInt();
        } catch (Throwable e) {
            return UNAVAILABLE;
        } finally {
            data.recycle();
            reply.recycle();
        }
    }
}
