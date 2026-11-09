package mcdonate.client.overlay;

/** 三条客户端命令怎么改开关（纯逻辑）：/mcdonate show（两样一起）、/mcdonate overlay on|off、/mcdonate hud on|off。 */
public final class OverlayToggles {
    private OverlayToggles() {}

    /** 有一样关着就两样都开；两样都开着就两样都关。 */
    public static OverlaySettings show(OverlaySettings s) {
        boolean on = !(s.overlay() && s.hud());
        return new OverlaySettings(on, on);
    }

    public static OverlaySettings overlay(OverlaySettings s, boolean on) {
        return new OverlaySettings(on, s.hud());
    }

    public static OverlaySettings hud(OverlaySettings s, boolean on) {
        return new OverlaySettings(s.overlay(), on);
    }

    /** 回在聊天栏的一句。 */
    public static String describe(OverlaySettings s) {
        return "mc-donate：来源上色：" + (s.overlay() ? "开" : "关") + "，算力面板：" + (s.hud() ? "开" : "关");
    }
}
