package mcdonate.common.gen;

/** 混入 ProtoChunk：标记这个半成品是从存档读出来的，不是这台服务器从头生成的（原来在 bench，overlay 计划 2.1 挪进 common）。 */
public interface FromDisk {
    void mcdonate$markFromDisk();

    boolean mcdonate$isFromDisk();
}
