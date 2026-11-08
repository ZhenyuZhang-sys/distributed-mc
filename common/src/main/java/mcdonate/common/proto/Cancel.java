package mcdonate.common.proto;

/** coordinator → worker：这个任务别人已经做完了，没开始就别做，做完了也别发。 */
public record Cancel(long taskId) implements Message {}
