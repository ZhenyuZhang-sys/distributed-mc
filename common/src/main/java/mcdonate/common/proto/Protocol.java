package mcdonate.common.proto;

/** 协议版本。hello 和 welcome 里带这个数，两边不一致就断开。 */
public final class Protocol {
    public static final int VERSION = 1;

    private Protocol() {}
}
