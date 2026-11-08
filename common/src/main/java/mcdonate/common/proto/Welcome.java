package mcdonate.common.proto;

/** coordinator → worker，回 hello。版本不一致时 workerId = -1，发完就断开。 */
public record Welcome(int protocol, int workerId, long seed, int dataVersion) implements Message {}
