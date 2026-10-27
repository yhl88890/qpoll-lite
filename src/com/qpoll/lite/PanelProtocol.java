package com.qpoll.lite;

import java.io.ByteArrayOutputStream;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

/** Protocol and factory scan packing adapted from kur0neko/huawei-epd-pn532.
 * Required Notice: Copyright (c) 2026 kur0neko
 * PolyForm Noncommercial 1.0.0; see bundled licenses/kur0neko-LICENSE.txt. */
public final class PanelProtocol {
    public static final int WIDTH=552, HEIGHT=768, IMAGE_BYTES=105984;
    public static final int DEFAULT_POLL_MS=500;
    /**
     * D4 之后继续供能的秒数。
     *
     * <p>墨水屏的刷新波形要跑十几秒，这期间全靠 NFC 场供电。发完 D4 就撒手等于中途断电，
     * 画面会回滚成原样——「提示刷完了但屏没变」多半就是这里被掐了。
     */
    static final int HOLD_SECONDS=20;
    /**
     * D4 之后是否持续发 {@code DE} 喂场。默认 <b>true（喂）</b>——12T 小线圈的活命开关。
     *
     * <p><b>为什么从 final 常量改成设备画像开关（1.4.11 定案）</b>：
     * 这段 DE 是 1.3.4 补回来的，救的是 12T：链路空转 20 秒控制器把场降下来，波形跑不完。
     * 但 2026-10-27 凌晨的对照实验钉死了大线圈屏恰恰相反——
     * <ul>
     *   <li><b>K90（SN220U / 大线圈）</b>：1.3.3「D4 后干睡 20 秒、DE 发 0 次」能刷；
     *       1.4.5~1.4.10 每张图后喂 DE ~40 次，全部「只闪一下」。装回 1.3.3 立即复活，
     *       dex 反编译证实 1.3.3 的 write() 里 DE 交换 0 次、1.4.10 里 1 处循环调用。
     *       结论：屏跑波形时被 DE 一次次打断，波形跑不完整。</li>
     *   <li><b>12T（SN100U / 小线圈）</b>：1.3.0~1.3.3 干睡必败，1.3.4+ 喂 DE 才成。</li>
     * </ul>
     * 两台的供能特性相反，谁也不能替谁——由 {@code FrontSession.begin()} 按设备画像
     * 设置（K90 关、12T 默认开）。worker 路（shell 身份）不经过该赋值，保持默认 true。
     *
     * <p>⚠️ {@code DE} 的返回码<b>一律不看</b>。1.3.1 拿 payload[0]==1 当忙闲门控，
     * 结果 70 秒 35 次全报忙、空转 90 秒报假阴性。两件事要分清楚：
     * <b>它的返回码不可信，它的「发出去」这个动作本身是保场所必需的（仅限小线圈）。</b>
     */
    static volatile boolean holdKeepAlive=true;
    public static final byte[] D1=hex("00D1000000"), D8=hex("F0D8000005000000000E"), DE=hex("F0DE000001");
    private static final byte[] PIN=hex("00200001055265699919");
    private static final byte[] GEOMETRY=hex("F0C72006000228"), SCAN=hex("00120030FFFFFF");
    public interface Transport { byte[] exchange(byte[] command,int timeoutMs) throws Exception; }
    public interface Control {
        long now();
        void sleep(long ms) throws Exception;
        void check() throws Exception;
        void event(String kind,String message);
    }
    public static final class Info {
        public final Map<Integer,byte[]> fields;
        public final String uid;
        public boolean topDown(){return fields.get(0xA1)[0]==1;}
        Info(Map<Integer,byte[]> fields){this.fields=fields;byte[] id=fields.get(0xC1);this.uid=id==null?"未提供":toHex(id);}
        public String description(){return "UID="+uid+" · 552×768 四色 · 保留当前原厂配置";}
    }
    public static byte[] hex(String s) {
        if((s.length()&1)!=0)throw new IllegalArgumentException("Odd hex length");
        byte[] out=new byte[s.length()/2];
        for(int i=0;i<out.length;i++)out[i]=(byte)Integer.parseInt(s.substring(i*2,i*2+2),16);
        return out;
    }
    public static String toHex(byte[] data){StringBuilder s=new StringBuilder();for(byte b:data)s.append(String.format("%02X",b&255));return s.toString();}
    public static int status(byte[] response) {
        if(response==null||response.length<2)throw new IllegalArgumentException("回包没有状态字");
        return ((response[response.length-2]&255)<<8)|(response[response.length-1]&255);
    }
    private static String meaning(int sw) {
        switch(sw) {
            case 0x6502:case 0x68CA:case 0x698A:return "低电压或供能不足";
            case 0x68CB:return "屏幕刷新超时";
            case 0x6992:return "数据解压失败";
            case 0x6993:return "解压后的数据长度错误";
            case 0x6995:return "图像分包序号错误";
            case 0x6982:return "安全状态不满足";
            case 0x6985:return "访问条件不满足";
            // 墨小佳的 refreshScreen() 明确把 6986 当成「正在刷新，再发一次 D4」，不是故障。
            case 0x6986:case 0x68C6:return "屏幕正在刷新中";
            default:return "设备返回错误";
        }
    }
    public static byte[] requireOk(byte[] response,String label) {
        int sw=status(response);
        if(sw!=0x9000)throw new IllegalArgumentException(label+"：SW="+String.format("%04X",sw)+"（"+meaning(sw)+"），已停止，不重发");
        return Arrays.copyOf(response,response.length-2);
    }
    public static Info parseInfo(byte[] response) {
        byte[] data=requireOk(response,"D1 身份读取");
        Map<Integer,byte[]> fields=new LinkedHashMap<>();
        for(int i=0;i<data.length;) {
            if(i+2>data.length)throw new IllegalArgumentException("D1 TLV 头截断");
            int tag=data[i++]&255,n=data[i++]&255;
            if(fields.containsKey(tag)||i+n>data.length)throw new IllegalArgumentException("D1 TLV 重复或截断");
            fields.put(tag,Arrays.copyOfRange(data,i,i+n));i+=n;
        }
        StringBuilder mismatch=new StringBuilder();
        for(int[] f:new int[][]{{0xA0,7},{0xA1,7},{0xB1,1},{0xB2,1},{0xC0,4},{0xD1,7}}) {
            byte[] actual=fields.get(f[0]);
            if(actual==null||actual.length!=f[1])
                mismatch.append(String.format("\n%02X 长度：期望=%d，实际=%s",f[0],f[1],actual==null?"缺失":actual.length+" ("+toHex(actual)+")"));
        }
        int[] tags={0xA0,0xA1,0xC0,0xB1,0xD1};
        byte[][] expected={GEOMETRY,SCAN,hex("201EA001"),hex("02"),hex("01")};
        for(int i=0;i<tags.length;i++) {
            byte[] actual=fields.get(tags[i]);
            if(actual==null||actual.length==0)continue;
            boolean matches=tags[i]==0xD1?actual[0]==1:Arrays.equals(actual,expected[i]);
            if(tags[i]==0xA1&&Arrays.equals(actual,hex("01120030FFFFFF")))matches=true;
            // Reference device.py recognizes both registered FMSC APPIDs.
            if(tags[i]==0xC0&&Arrays.equals(actual,hex("00000001")))matches=true;
            if(!matches)mismatch.append(String.format("\n%02X%s：期望=%s，实际=%s",tags[i],tags[i]==0xD1?" 首字节":"",tags[i]==0xC0?"201EA001 或 00000001":toHex(expected[i]),toHex(actual)));
        }
        if(mismatch.length()>0)
            throw new IllegalArgumentException("目标配置与已验证的原厂扫描配置不匹配；不会改写 DB/DA"+mismatch);
        // UID is diagnostic only; any compatible screen may be used, with no ID allowlist.
        return new Info(fields);
    }
    /** Four vertical pixels per byte, right-to-left, bottom-to-top, MSB first. */
    public static byte[] packCodes(byte[] codes,int width,int height) {
        if(width<=0||height<=0||height%4!=0||codes.length!=width*height)throw new IllegalArgumentException("像素尺寸错误");
        byte[] packed=new byte[width*height/4];int out=0;
        for(int x=width-1;x>=0;x--)for(int y=height-1;y>=0;y-=4) {
            int value=0;
            for(int n=0;n<4;n++){int code=codes[(y-n)*width+x]&255;if(code>3)throw new IllegalArgumentException("颜色码错误");value|=code<<(6-2*n);}
            packed[out++]=(byte)value;
        }
        return packed;
    }
    /** Valid LZO1X literal-only stream. No native library and no padding of the final block. */
    public static byte[] literalLzo(byte[] raw) {
        if(raw.length<1||raw.length>2000)throw new IllegalArgumentException("LZO 块尺寸错误");
        ByteArrayOutputStream stream=new ByteArrayOutputStream(raw.length+16);
        if(raw.length<=238)stream.write(17+raw.length);
        else {stream.write(0);int rest=raw.length-18;while(rest>255){stream.write(0);rest-=255;}stream.write(rest);}
        stream.write(raw,0,raw.length);stream.write(0x11);stream.write(0);stream.write(0);return stream.toByteArray();
    }
    public static byte[] d3(byte[] compressed,int block,int sub) {
        if(block<0||block>255||sub<0||sub>255||sub*250>=compressed.length)throw new IllegalArgumentException("D3 编号错误");
        int offset=sub*250,count=Math.min(250,compressed.length-offset);
        byte[] packet=new byte[count+7];
        packet[0]=(byte)0xF0;packet[1]=(byte)0xD3;packet[2]=0;
        packet[3]=(byte)(offset+count==compressed.length?1:0);packet[4]=(byte)(count+2);
        packet[5]=(byte)block;packet[6]=(byte)sub;System.arraycopy(compressed,offset,packet,7,count);return packet;
    }
    /**
     * 启动刷新（D4）。
     *
     * <p>时间参数编码为 {@code 0x80 | (毫秒/100)}，所以 0x94 = 2000 ms。
     *
     * <p><b>第 4 个字节为什么是 0x80</b>：墨小佳官方发的是 {@code F0 D4 85 00 00}，
     * 我照着改成 0x00 之后，K90 上 D4 直接从「9000 启动成功」退化成「2.5 秒后 SW=68CA」，
     * 也就是屏连启动都不肯。改回 0x80 才能过 D4。所以这个字节不是随意值，
     * <b>别再按官方源码擅自改它</b>——官方那份是给它自己那批屏的。
     */
    public static byte[] d4(int powerMs) {
        if(powerMs<100||powerMs>12700||powerMs%100!=0)throw new IllegalArgumentException("上电等待参数错误");
        return new byte[]{(byte)0xF0,(byte)0xD4,(byte)(0x80|powerMs/100),(byte)0x80,0};
    }
    private final Transport transport;
    private final Control control;
    public PanelProtocol(Transport transport,Control control){this.transport=transport;this.control=control;}
    private byte[] exchange(byte[] cmd,String label,int timeout,boolean checkStatus) throws Exception {
        control.check();
        byte[] response=transport.exchange(cmd,timeout);
        int sw=status(response);
        // Never emit the authentication payload or image content into logs.
        if(cmd[1]!=(byte)0xD3&&cmd[1]!=(byte)0xDE)control.event("APDU",label+"：SW="+String.format("%04X",sw));
        if(checkStatus)requireOk(response,label);
        return response;
    }
    private void pause(long ms) throws Exception {
        long until=control.now()+ms;
        while(control.now()<until){control.check();control.sleep(Math.min(100,until-control.now()));}
        control.check();
    }
    public Info identify() throws Exception {
        control.event("STAGE","checking|读取并校验屏幕规格");pause(1000);
        Info info=parseInfo(exchange(D1,"D1",5000,true));
        control.event("IDENTITY",info.description());return info;
    }
    public boolean write(byte[] packed,int powerMs,int pollMs) throws Exception {
        if(packed.length!=IMAGE_BYTES)throw new IllegalArgumentException("图像必须为 105984 字节");
        if(pollMs<0||pollMs>500)throw new IllegalArgumentException("忙状态查询间隔必须为 0–500 ms");
        byte[] refresh=d4(powerMs); // Validate every option before any authentication/write.
        control.event("TIMING","忙状态查询间隔="+pollMs+" ms（每次应答后等待）");
        Info initial=identify();
        packed=PanelType.forScan(packed,initial.topDown());
        control.event("SCAN","采用板内扫描方向="+(initial.topDown()?"01（从上到下）":"00（从下到上）")+"；不改写驱动配置");
        control.event("WIRE_IMAGE","实际传输 SHA-256="+toHex(java.security.MessageDigest.getInstance("SHA-256").digest(packed)));
        control.event("STAGE","authenticating|检查图像区访问权限");
        byte[] access=exchange(D8,"D8 权限查询",5000,false);
        if(status(access)==0x6985) {
            exchange(PIN,"验证已公开的默认 PIN（仅一次）",5000,true);
            exchange(D8,"D8 再次检查",5000,true);
        } else requireOk(access,"D8 权限查询");
        // Check the same factory profile again; no DB/DA configuration writes exist here.
        Info rechecked=parseInfo(exchange(D1,"D1 写入前复核",5000,true));
        if(initial.topDown()!=rechecked.topDown())throw new IllegalArgumentException("扫描配置在会话中发生变化，未传图");
        control.event("STAGE","uploading|正在传输图片，请保持贴合");
        int confirmed=0,total=0;
        for(int offset=0;offset<packed.length;offset+=2000) {
            int n=literalLzo(Arrays.copyOfRange(packed,offset,Math.min(offset+2000,packed.length))).length;
            total+=(n+249)/250;
        }
        for(int block=0,offset=0;offset<packed.length;block++,offset+=2000) {
            byte[] compressed=literalLzo(Arrays.copyOfRange(packed,offset,Math.min(offset+2000,packed.length)));
            for(int sub=0;sub*250<compressed.length;sub++) {
                exchange(d3(compressed,block,sub),"D3 块 "+block+" / 包 "+sub,5000,true);
                confirmed++;
                if(confirmed%8==0||confirmed==total)control.event("PROGRESS",confirmed+"|"+total+"|图片传输");
            }
        }
        control.event("STAGE","refreshing|正在刷新屏幕，保持贴合别动（最多 30 秒）");
        control.check();
        /*
         * 刷新这一段照墨小佳官方 refreshScreen() 来，但比官方多留一手：
         *   - 官方只发一次 D4，收到 9000 就当命令被接受，然后屏自己刷十几秒，
         *     期间不再发任何查询（屏在跑刷新波形，根本不响应）；
         *   - 返回 6986 = 屏说「我正在刷」，官方会递归重发一次 D4，这里跟着做；
         *   - 返回 68CA = 屏暂时不接受命令（多半是场供能不足，屏连启动都不肯），
         *     这种就别再重发了，直接把真实原因报出去。
         *
         * 老版本错在 D4 之后每 500 ms 发一次 DE 查询，一看到 68CA 就当成致命错误中断——
         * 而那时屏其实正在刷，68CA 只是「忙着，别问我」。所以 DE 绝不能拿来当门控。
         */
        int attempt=0;
        int finalStatus=0;
        while(attempt<3) {
            attempt++;
            byte[] response=exchange(refresh,"D4 刷新（上电等待 "+powerMs+" ms）",30000,false);
            finalStatus=status(response);
            if(finalStatus==0x9000||finalStatus==0x68CA)break;
            if(finalStatus==0x6986) {
                control.event("BUSY","屏幕报告正在刷新，重发启动命令（第 "+(attempt+1)+" 次）");
                pause(1000);
                continue;
            }
            break;
        }
        if(finalStatus==0x9000) {
            /*
             * 收到 9000 只代表「刷新命令受理了」，**不代表刷完了**。
             *
             * 墨水屏刷新要屏内升压，这点电全靠 NFC 场现供；场一断刷新就停在半路，
             * e-ink 被打断会自己回滚到原画面——现象正是「App 提示成功、屏一点没变」。
             *
             * 所以 D4 之后按住 20 秒不撒手。这个 20 秒是实测能正常刷完的值，别乱动。
             *
             * ⚠️ 别再拿 DE 当「忙闲指示灯」（1.3.1 踩过的坑，已回退）：
             *    实机日志里 DE 从第 20 秒一路到第 90 秒、每次 6 毫秒就回话，
             *    70 秒内一次「空闲」都没报过。可见 DE 回的字节根本不是忙闲状态，
             *    按 payload[0]==1 判忙 = 把一个恒定值当忙信号，结果是白等 90 秒
             *    再报「无法确认」，而屏其实早就刷好了。假阴性比假阳性更坑人。
             */
            control.event("HOLD","刷新中，保持贴合别动（供能 20 秒）");
            long deadline=control.now()+HOLD_SECONDS*1000L;
            long nextTick=control.now()+1000L;
            int sent=0,lost=0;
            while(control.now()<deadline) {
                if(holdKeepAlive) {
                    /*
                     * 只为了「别让链路空着」，返回码一概不看：
                     *   - 9000  正常
                     *   - 68CA  屏正在升压刷新，暂时不应答 —— 不是错误，继续喂
                     *   - 6986  屏报告正在刷新 —— 同样继续喂
                     * 只要还能把包发出去，场就是活的，屏就能接着取电。
                     */
                    try {
                        transport.exchange(DE,5000);
                        sent++;
                        lost=0;
                    } catch(Exception e) {
                        // 掉一次不算事（可能瞬时抖动）；连续三次才说明真贴不住了。
                        if(++lost>=3) {
                            control.event("HOLD_LOST","保场中断：连续 3 次发不出去（"
                                    +e.getClass().getSimpleName()+"）。屏可能没刷完，请目视确认后再决定要不要重刷。");
                            break;
                        }
                    }
                } else {
                    control.sleep(DEFAULT_POLL_MS);
                }
                if(control.now()>=nextTick) {
                    long left=Math.max(0,(deadline-control.now()+999)/1000);
                    if(left>0)control.event("HOLD","还剩 "+left+" 秒 · 别松手别点停止");
                    nextTick+=1000L;
                }
                pause(DEFAULT_POLL_MS);
            }
            control.event("REFRESH_DONE","供能 " + HOLD_SECONDS + " 秒完成（保场查询 " + sent
                    + " 次）；图已全部传完，请目视确认画面");
            return true;
        }
        control.event("REFRESH_UNCONFIRMED","刷新命令没有启动：SW="+String.format("%04X",finalStatus)
                +"（"+meaning(finalStatus)+"）。图已全部传完，卡在最后上电这一步。");
        throw new IllegalArgumentException("D4 刷新：SW="+String.format("%04X",finalStatus)
                +"（"+meaning(finalStatus)+"），已停止，不重发");
    }
}
