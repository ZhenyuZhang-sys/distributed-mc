package mcdonate.common.proto;

import java.util.Objects;

/**
 * worker → coordinator，连上后第一条。
 * token（第 9 步加）：没有就是空串。旧版本发的消息没有这个字段，解码时按空串处理。
 */
public record Hello(int protocol, String name, String token) implements Message {
    public Hello {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(token, "token");
    }

    /** 不带 token。 */
    public Hello(int protocol, String name) {
        this(protocol, name, "");
    }

    /** toString 不带 token 原文，避免写进日志。 */
    @Override
    public String toString() {
        return "Hello[protocol=" + protocol + ", name=" + name + ", token=" + (token.isEmpty() ? "" : "***") + "]";
    }
}
