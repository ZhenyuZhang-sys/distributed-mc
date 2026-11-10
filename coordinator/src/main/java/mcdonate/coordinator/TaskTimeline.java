package mcdonate.coordinator;

/**
 * 一个任务从入队到导入完的分段耗时（毫秒），写进 /donor imports 的 seg。纯逻辑。
 * <ul>
 *   <li>queue：入队 → 派出（在 coordinator 队列里等）
 *   <li>to_worker：派出 → worker 收到
 *   <li>wait：worker 收到 → 开始生成（在 worker 上排队：手上有别的任务、主线程忙）
 *   <li>gen：生成
 *   <li>pack：生成完 → 打包成 NBT
 *   <li>back：打包完 → coordinator 收到（编码、网络、解码）
 *   <li>main_wait：coordinator 收到 → 服务器主线程开始导入
 *   <li>import：导入
 * </ul>
 * 不知道的段是 -1（入队时间不知道、旧 worker 不带时间点；后者把派出到收到合成一段 back）。
 */
final class TaskTimeline {
    static final String[] KEYS = {"queue", "to_worker", "wait", "gen", "pack", "back", "main_wait", "import"};

    private TaskTimeline() {}

    /** worker：{收到, 开始生成, 生成完, 打包完}，没有是 null。 */
    static String json(long enqueued, long leased, long[] worker, long got, long importStart, long importEnd) {
        long[] v = new long[KEYS.length];
        v[0] = enqueued < 0 || leased < 0 ? -1 : leased - enqueued;
        if (worker != null && leased >= 0) {
            v[1] = worker[0] - leased;
            v[2] = worker[1] - worker[0];
            v[3] = worker[2] - worker[1];
            v[4] = worker[3] - worker[2];
            v[5] = got - worker[3];
        } else {
            v[1] = v[2] = v[3] = v[4] = -1;
            v[5] = leased < 0 ? -1 : got - leased;
        }
        v[6] = importStart - got;
        v[7] = importEnd - importStart;
        StringBuilder sb = new StringBuilder("{");
        for (int i = 0; i < KEYS.length; i++) {
            sb.append(i == 0 ? "" : ",").append('"').append(KEYS[i]).append("\":").append(v[i]);
        }
        return sb.append('}').toString();
    }

    /** 测试用：按 KEYS 的顺序读回来。 */
    static long[] parse(String json) {
        long[] v = new long[KEYS.length];
        for (int i = 0; i < KEYS.length; i++) {
            int a = json.indexOf("\"" + KEYS[i] + "\":") + KEYS[i].length() + 3;
            int b = a;
            while (b < json.length() && (json.charAt(b) == '-' || Character.isDigit(json.charAt(b)))) {
                b++;
            }
            v[i] = Long.parseLong(json.substring(a, b));
        }
        return v;
    }
}
