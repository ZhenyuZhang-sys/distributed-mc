package mcdonate.common.proto;

import java.util.Objects;

/** worker → coordinator，连上后第一条。 */
public record Hello(int protocol, String name) implements Message {
    public Hello {
        Objects.requireNonNull(name, "name");
    }
}
