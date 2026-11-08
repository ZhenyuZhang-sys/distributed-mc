package mcdonate.common.proto;

/** 六种消息，字段和方向见 docs/step5-plan.md 1.2。 */
public sealed interface Message permits Hello, Welcome, Task, Result, Cancel, Heartbeat {}
