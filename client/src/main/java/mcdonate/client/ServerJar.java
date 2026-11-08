package mcdonate.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * 第 9 步：从 Mojang 官方版本清单找服务器 jar 的地址和 sha1，下载后校验。不自己分发服务器 jar。
 *
 * <p>清单：https://piston-meta.mojang.com/mc/game/version_manifest_v2.json
 * → versions[id == "1.21.1"].url → 这个版本的 json → downloads.server{url, sha1, size}。
 * 这里只做解析和校验（纯逻辑），下载本身由调用方做。
 */
public final class ServerJar {
    public static final String MANIFEST_URL = "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json";

    public record Download(String url, String sha1, long size) {}

    private ServerJar() {}

    /** 版本清单里某个版本的 json 地址；没有这个版本抛异常。 */
    public static String versionJsonUrl(String manifestJson, String version) {
        JsonArray versions = JsonParser.parseString(manifestJson).getAsJsonObject().getAsJsonArray("versions");
        for (JsonElement e : versions) {
            JsonObject v = e.getAsJsonObject();
            if (version.equals(v.get("id").getAsString())) {
                return v.get("url").getAsString();
            }
        }
        throw new IllegalArgumentException("版本清单里没有 " + version);
    }

    /** 版本 json 里服务器 jar 的下载信息。 */
    public static Download serverDownload(String versionJson) {
        JsonObject server = JsonParser.parseString(versionJson).getAsJsonObject()
                .getAsJsonObject("downloads").getAsJsonObject("server");
        if (server == null) {
            throw new IllegalArgumentException("版本 json 里没有服务器下载信息");
        }
        return new Download(server.get("url").getAsString(), server.get("sha1").getAsString(),
                server.get("size").getAsLong());
    }

    public static String sha1(Path file) throws IOException {
        try (InputStream in = Files.newInputStream(file)) {
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            byte[] buf = new byte[1 << 16];
            int n;
            while ((n = in.read(buf)) > 0) {
                md.update(buf, 0, n);
            }
            return HexFormat.of().formatHex(md.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** 校验下载的文件：大小和 sha1 都对才留下，不对就删掉并抛异常。 */
    public static void verifyOrDelete(Path file, Download expected) throws IOException {
        long size = Files.size(file);
        String actual = sha1(file);
        if (size != expected.size() || !actual.equalsIgnoreCase(expected.sha1())) {
            Files.deleteIfExists(file);
            throw new IOException("服务器 jar 校验失败：大小 " + size + "/" + expected.size()
                    + "，sha1 " + actual + "/" + expected.sha1() + "，已删除");
        }
    }
}
