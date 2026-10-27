package com.qpoll.lite;

/** Retries only a failed connection, never a protocol exchange or image write. */
public final class ConnectionRetry {
    public static final class BeforeApdu extends java.io.IOException {
        public BeforeApdu(Exception cause){super("ISO-DEP 连接失败，尚未发送 APDU；原因="+cause,cause);}
    }
    public interface Operation {void run() throws Exception;}
    public static void run(Operation session,Operation rediscover) throws Exception {
        for(int attempt=1;;attempt++) {
            try {session.run();return;}
            catch(BeforeApdu e){if(attempt>=3)throw e;rediscover.run();}
        }
    }
}
