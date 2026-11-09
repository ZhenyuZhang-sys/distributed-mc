package mcdonate.bench.flight;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 真实场景的飞行路线（调度 11-09：会转弯的鞘翅、来回飞、走路骑马混合加停顿），纯逻辑。
 *
 * <p>若干段，每段（朝向、速度、秒数），按墙钟时间排：第 k 段从前 k 段的秒数之和开始。朝向用 Minecraft 的 yaw：
 * 0 南（+z），90 西（-x），180 北（-z），-90 东（+x），方向 = (-sin yaw, cos yaw)。速度 0 = 停在原地（不算卡住）。
 * 文字格式 {@code yaw,速度,秒数;yaw,速度,秒数;...}（/bench fly route 的参数，Python 的 bench/scenarios.py 生成）。
 * 每段内照样像客户端一样前进（{@link Flight#advance}），被没就绪的 chunk 挡住就停在边缘，时间照走。
 */
public record Route(List<Segment> segments) {
    public static final int MAX_SEGMENTS = 64;
    public static final double MAX_SPEED = 1000;

    public record Segment(float yaw, double speed, double seconds, double dirX, double dirZ) {
        String spec() {
            return fmt(yaw) + "," + fmt(speed) + "," + fmt(seconds);
        }
    }

    /** 一段里的一小截：第几段、走多少秒。 */
    public record Piece(int index, double seconds) {}

    public Route {
        segments = List.copyOf(segments);
        if (segments.isEmpty() || segments.size() > MAX_SEGMENTS) {
            throw new IllegalArgumentException("路线要有 1～" + MAX_SEGMENTS + " 段");
        }
    }

    public static Route parse(String spec) {
        if (spec == null || spec.isBlank()) {
            throw new IllegalArgumentException("路线是空的");
        }
        String[] parts = spec.trim().split(";");
        if (parts.length > MAX_SEGMENTS) {
            throw new IllegalArgumentException("路线最多 " + MAX_SEGMENTS + " 段");
        }
        List<Segment> out = new ArrayList<>();
        for (String part : parts) {
            String[] f = part.trim().split(",");
            if (f.length != 3) {
                throw new IllegalArgumentException("每段是 yaw,速度,秒数：" + part);
            }
            double yaw, speed, seconds;
            try {
                yaw = Double.parseDouble(f[0]);
                speed = Double.parseDouble(f[1]);
                seconds = Double.parseDouble(f[2]);
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("读不懂：" + part);
            }
            if (!Double.isFinite(yaw) || !Double.isFinite(speed) || !Double.isFinite(seconds) || speed < 0 || speed > MAX_SPEED
                    || !(seconds > 0) || seconds > 3600) {
                throw new IllegalArgumentException("速度 0～" + MAX_SPEED + "、秒数 (0, 3600]：" + part);
            }
            double rad = Math.toRadians(((yaw % 360) + 360) % 360);
            double dx = -Math.sin(rad), dz = Math.cos(rad);
            // 消掉 -0.0 和 1e-16 这种，方向正好在轴上时干净
            dx = Math.abs(dx) < 1e-12 ? 0 : dx;
            dz = Math.abs(dz) < 1e-12 ? 0 : dz;
            out.add(new Segment((float) yaw, speed, seconds, dx, dz));
        }
        return new Route(out);
    }

    public double seconds() {
        return segments.stream().mapToDouble(Segment::seconds).sum();
    }

    public double idealDistance() {
        return segments.stream().mapToDouble(s -> s.speed() * s.seconds()).sum();
    }

    /** 起飞后 t 秒在第几段（结束以后算最后一段）。 */
    public int segmentAt(double t) {
        double end = 0;
        for (int i = 0; i < segments.size(); i++) {
            end += segments.get(i).seconds();
            if (t < end) return i;
        }
        return segments.size() - 1;
    }

    /** [from, to) 这段时间（起飞后的秒数）按段切开；到路线结束为止。 */
    public List<Piece> pieces(double from, double to) {
        List<Piece> out = new ArrayList<>();
        double start = 0;
        for (int i = 0; i < segments.size() && from < to; i++) {
            double end = start + segments.get(i).seconds();
            double a = Math.max(from, start), b = Math.min(to, end);
            if (b > a) {
                out.add(new Piece(i, b - a));
            }
            start = end;
        }
        return out;
    }

    public String spec() {
        StringBuilder sb = new StringBuilder();
        for (Segment s : segments) {
            if (sb.length() > 0) sb.append(';');
            sb.append(s.spec());
        }
        return sb.toString();
    }

    private static String fmt(double v) {
        return v == Math.rint(v) && Math.abs(v) < 1e15 ? Long.toString((long) v) : String.format(Locale.ROOT, "%s", v);
    }
}
