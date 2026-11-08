package mcdonate.common.proto;

/** 帧或消息不合规。接收方遇到它就断开连接。 */
public final class ProtocolException extends RuntimeException {
    public ProtocolException(String message) {
        super(message);
    }

    public ProtocolException(String message, Throwable cause) {
        super(message, cause);
    }
}
