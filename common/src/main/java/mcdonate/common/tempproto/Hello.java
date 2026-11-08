/*
 * 临时实现：kexu 的 mcdonate.common.proto 合进 main 之前，fish 先用它把网络接起来。
 * 接口和 docs/step5-plan.md 第 1 节一致；换成正式版时删掉整个包，改 import 即可。
 */
package mcdonate.common.tempproto;

public record Hello(int protocol, String name) implements Message {}
