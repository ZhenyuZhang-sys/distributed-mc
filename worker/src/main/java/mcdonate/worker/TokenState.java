package mcdonate.worker;

/**
 * 第 9 步：worker 手上的一次性 token（客户端起 worker 时从环境变量 MCDONATE_TOKEN 给，之后经控制通道 {@code TOKEN <t>} 给新的）。
 *
 * <p>一个 token 只换一次连接：连上过（coordinator 已经把它用掉）或者被拒（-2）以后，重连前要客户端再要一个
 * （worker 在标准输出打 {@code MCDONATE NEED_TOKEN}，客户端发 mcdonate:invite_request，拿到新邀请后转给 worker）。
 * 没有 token 的（Docker 里的本地 worker，coordinator 开着 allowTokenless）一直免 token。线程安全。
 */
final class TokenState {
    private final boolean player;
    private String token;
    private boolean spent;

    TokenState(String initial) {
        this.token = initial == null ? "" : initial;
        this.player = !this.token.isEmpty();
    }

    /** 握手用的 token（没有就是空串）。 */
    synchronized String forHello() {
        return token;
    }

    /** 这次握手被接受了：token 已经用掉。 */
    synchronized void connected() {
        if (player) {
            spent = true;
        }
    }

    /** 被拒（token 不对或过期）。 */
    synchronized void rejected() {
        if (player) {
            spent = true;
        }
    }

    /** 重连前要不要新 token。 */
    synchronized boolean needsNew() {
        return player && spent;
    }

    /** 客户端给了一个新 token；空的或者就是刚用过的那个不算。返回收没收下。 */
    synchronized boolean offer(String fresh) {
        if (fresh == null || fresh.isEmpty() || fresh.equals(token)) {
            return false;
        }
        token = fresh;
        spent = false;
        return true;
    }
}
