package mcdonate.common.proto;

/** coordinator → worker：生成 tile (tx, tz)。 */
public record Task(long taskId, int tx, int tz, long leaseMs) implements Message {}
