package mcdonate.bench;

/** 混入 ProtoChunk：标记这个半成品是从存档读出来的，不是这台服务器从头生成的。 */
public interface FromDisk {
    void mcdonate$markFromDisk();

    boolean mcdonate$isFromDisk();
}
