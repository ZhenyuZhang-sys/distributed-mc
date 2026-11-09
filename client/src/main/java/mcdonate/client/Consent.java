package mcdonate.client;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 第 9 步（docs/step9-plan.md 1.1）：玩家同不同意当 donor。存在 {@code .minecraft/config/mc-donate.json}：
 * {@code {"consent": true, "eulaAcceptedAt": "..."}}。
 *
 * <p>默认没同意。只有 consent 是布尔 true、而且有 EULA 同意时间才算同意；文件坏了、缺字段都按没同意。
 * 没同意之前 mod 什么都不做：不下载、不起进程、不连 coordinator。
 * 只有同意界面上玩家点"同意"时调 {@link #accept}；没有任何自动同意的代码或系统属性（测试用单独的测试小 mod 代替点击）。
 */
public record Consent(boolean given, Instant eulaAcceptedAt) {
    private static final Pattern CONSENT = Pattern.compile("\"consent\"\\s*:\\s*true\\b");
    private static final Pattern EULA_AT = Pattern.compile("\"eulaAcceptedAt\"\\s*:\\s*\"([^\"]+)\"");

    static final Consent NO = new Consent(false, null);

    /** 读同意记录。读不到、读不懂都按没同意，不创建文件。 */
    public static Consent load(Path file) {
        String text;
        try {
            text = Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return NO;
        }
        Matcher at = EULA_AT.matcher(text);
        if (!CONSENT.matcher(text).find() || !at.find()) {
            return NO;
        }
        try {
            return new Consent(true, Instant.parse(at.group(1)));
        } catch (DateTimeParseException e) {
            return NO;
        }
    }

    /** 玩家在同意界面上点了"同意"（含 Minecraft EULA）。 */
    public static void accept(Path file, Instant now) throws IOException {
        write(file, "{\n  \"consent\": true,\n  \"eulaAcceptedAt\": \"" + now + "\"\n}\n");
    }

    /** 玩家在菜单里关掉。 */
    public static void revoke(Path file) throws IOException {
        write(file, "{\n  \"consent\": false\n}\n");
    }

    private static void write(Path file, String json) throws IOException {
        Path dir = file.toAbsolutePath().getParent();
        Files.createDirectories(dir);
        Path tmp = dir.resolve(file.getFileName() + ".tmp");
        Files.writeString(tmp, json, StandardCharsets.UTF_8);
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }
}
