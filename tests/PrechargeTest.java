package com.qpoll.lite;

import java.util.ArrayList;
import java.util.List;

public final class PrechargeTest {
    private static final byte[] OK = PanelProtocol.hex("9000");
    private static final byte[] INFO = PanelProtocol.hex(
            "A007F0C72006000228A10700120030FFFFFFB10102B20114B30100C004201EA001C10401020304D107012000000000009000");
    private static final byte[] IMAGE = new byte[PanelProtocol.IMAGE_BYTES];

    private static final class Session implements PanelProtocol.Control, PanelProtocol.Transport {
        final PanelProtocol protocol = new PanelProtocol(this, this);
        final List<String> commands = new ArrayList<>();
        final List<String> events = new ArrayList<>();
        long time;
        long firstCommandAt = -1;
        long refreshAt = -1;
        long cancelAt = Long.MAX_VALUE;

        public long now() { return time; }
        public void sleep(long ms) { time += ms; }
        public void check() throws InterruptedException {
            if (time >= cancelAt) throw new InterruptedException("cancelled");
        }
        public void event(String kind, String message) { events.add(kind + "|" + message); }
        public byte[] exchange(byte[] command, int timeout) {
            if (firstCommandAt < 0) firstCommandAt = time;
            if ((command[1] & 255) == 0xD4) refreshAt = time;
            commands.add(PanelProtocol.toHex(command));
            return (command[1] & 255) == 0xD1 ? INFO : OK;
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    public static void main(String[] args) throws Exception {
        for (boolean officialCheck : new boolean[]{false, true}) {
            Session baseline = new Session();
            require(baseline.protocol.write(IMAGE, officialCheck), "baseline refresh");
            for (int chargeMs : new int[]{0, 1000, 15000, 30000}) {
                Session session = new Session();
                require(session.protocol.write(IMAGE, officialCheck, chargeMs), "refresh after charging");
                require(session.commands.equals(baseline.commands), "identical APDUs in both check modes");
                require(session.firstCommandAt == 1000 + chargeMs, "charging precedes the first D1");
                require(session.time == baseline.time + chargeMs, "only charge time is added");
                int start = session.events.indexOf("WAIT|charging|0|" + chargeMs);
                int done = session.events.indexOf("WAIT_DONE|charging|" + chargeMs);
                int checking = session.events.indexOf("STAGE|checking|读取并校验屏幕规格");
                require(chargeMs == 0 ? start == -1 && done == -1 : start >= 0 && start < done && done < checking,
                        "charging timer starts and ends before D1 and is skipped at zero");
            }
        }

        for (int invalid : new int[]{-1, 30001}) {
            Session session = new Session();
            try {
                session.protocol.write(IMAGE, false, invalid);
                throw new AssertionError("invalid charge accepted");
            } catch (IllegalArgumentException expected) {
                require(session.commands.isEmpty() && session.time == 0, "invalid charge stops before waiting or APDUs");
            }
        }

        Session cancelled = new Session();
        cancelled.cancelAt = 550;
        try {
            cancelled.protocol.write(IMAGE, false, 30000);
            throw new AssertionError("charging continued after cancellation");
        } catch (InterruptedException expected) {
            require(cancelled.commands.isEmpty() && cancelled.time == 600, "charge cancellation stops before APDUs");
            require(!cancelled.events.contains("WAIT_DONE|charging|30000"), "cancelled charge is not reported complete");
        }
        System.out.println("PASS: pre-charge boundaries, event order, unchanged APDUs and cancellation");

        for (boolean officialCheck : new boolean[]{false, true}) {
            Session baseline = new Session();
            baseline.protocol.write(IMAGE, officialCheck, 0);
            require(baseline.time - baseline.refreshAt == (officialCheck ? 0 : 20000), "default hold duration");
            for (int seconds : new int[]{0, 1, 20, 60}) {
                Session session = new Session();
                session.protocol.write(IMAGE, officialCheck, 3000, seconds);
                require(session.commands.equals(baseline.commands), "hold duration does not change APDUs");
                require(session.refreshAt == baseline.refreshAt + 3000, "hold is independent of pre-charge");
                require(session.time - session.refreshAt == (officialCheck ? 0 : seconds * 1000L), "selected hold duration");
                long ticks = session.events.stream().filter(event -> event.startsWith("HOLD|")).count();
                require(ticks == (officialCheck ? 0 : seconds), "hold countdown runs only in quick mode");
                require(session.events.stream().anyMatch(event -> event.startsWith("HOLD_DONE|")) == !officialCheck,
                        "quick mode ends with the visual confirmation prompt");
            }
        }

        for (int invalid : new int[]{-1, 61}) {
            Session session = new Session();
            try {
                session.protocol.write(IMAGE, false, 0, invalid);
                throw new AssertionError("invalid hold accepted");
            } catch (IllegalArgumentException expected) {
                require(session.commands.isEmpty() && session.time == 0, "invalid hold stops before APDUs");
            }
        }

        Session stoppedHold = new Session();
        stoppedHold.cancelAt = 1250;
        try {
            stoppedHold.protocol.write(IMAGE, false, 0, 60);
            throw new AssertionError("refresh hold continued after cancellation");
        } catch (InterruptedException expected) {
            require(expected.getMessage().contains("目视确认"), "cancellation reports unverified refresh");
            require(stoppedHold.refreshAt == 1000 && stoppedHold.time == 1300, "hold checks for cancellation every 100 ms");
            require(stoppedHold.commands.get(stoppedHold.commands.size() - 1).startsWith("F0D4"), "D4 remains the last APDU");
            require(stoppedHold.events.stream().noneMatch(event -> event.startsWith("HOLD_DONE|")), "cancelled hold is not complete");
        }
        System.out.println("PASS: selectable post-refresh hold, quick-mode scope and bounded cancellation");
    }
}
