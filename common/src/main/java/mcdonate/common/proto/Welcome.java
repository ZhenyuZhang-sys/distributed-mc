package mcdonate.common.proto;

/**
 * coordinator → worker，回 hello。workerId 为负表示拒绝，发完就断开：
 * {@link #BAD_VERSION}（-1）协议版本不一致；{@link #BAD_TOKEN}（-2，第 9 步加）token 不对。
 */
public record Welcome(int protocol, int workerId, long seed, int dataVersion) implements Message {
    public static final int BAD_VERSION = -1;
    public static final int BAD_TOKEN = -2;

    public boolean accepted() {
        return workerId >= 0;
    }
}
