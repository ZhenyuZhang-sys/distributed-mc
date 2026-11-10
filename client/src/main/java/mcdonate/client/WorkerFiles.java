package mcdonate.client;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

/**
 * 第 9 步 b：首次准备 worker 的目录 {@code .minecraft/mc-donate/worker/}（docs/step9-plan.md 1.2）。只在玩家同意以后调用。
 *
 * <ul>
 *   <li>server.jar：从 Mojang 的版本清单找 1.21.1 的服务器 jar，下载后校验大小和 sha1，不对就删掉报错（不自己分发）。
 *   <li>fabric-server-launch.jar：Fabric 官方 meta 的服务器启动器（Loader 0.16.14、启动器 1.1.2，和服务器一样），
 *       启动时它按 fabric-server-launcher.properties 用旁边的 server.jar，自己下载 Fabric 的库。
 *   <li>mods/：worker mod 和 Fabric API 打在客户端 mod 的 jar 里（mcdonate/worker-mods/，index.txt 列出文件名），解出来。
 *   <li>eula.txt：玩家已经在同意界面上本人同意了 Minecraft EULA，写 eula=true。
 * </ul>
 * 已经有、sha1 也对的文件不再下载。下载在后台线程上做（调用方负责），不卡游戏。
 */
public final class WorkerFiles {
    public static final String MC_VERSION = "1.21.1";
    public static final String FABRIC_LAUNCHER_URL =
            "https://meta.fabricmc.net/v2/versions/loader/1.21.1/0.16.14/1.1.2/server/jar";

    /** 下载一个地址的全部内容。 */
    public interface Downloader {
        byte[] get(String url) throws IOException;
    }

    /** 打在客户端 jar 里的 worker mod（按文件名取；没有返回 null）。 */
    public interface Bundled {
        InputStream open(String name) throws IOException;
    }

    private final Path root;

    public WorkerFiles(Path root) {
        this.root = root;
    }

    public Path root() {
        return root;
    }

    /** 准备好了没有（server.jar、启动器、mods、eula 都在）。 */
    public boolean prepared() {
        return Files.exists(root.resolve("server.jar")) && Files.exists(root.resolve("fabric-server-launch.jar"))
                && Files.exists(root.resolve("eula.txt")) && Files.isDirectory(root.resolve("mods"));
    }

    public void prepare(Downloader net, Bundled bundled) throws IOException {
        Files.createDirectories(root.resolve("mods"));
        // 1. 原版服务器 jar：清单 → 版本 json → 下载 → 校验。校验过的 sha1 记在 server.jar.sha1，以后文件没变就不联网
        Path server = root.resolve("server.jar");
        Path known = root.resolve("server.jar.sha1");
        if (!(Files.exists(server) && Files.exists(known)
                && ServerJar.sha1(server).equalsIgnoreCase(Files.readString(known).trim()))) {
            ServerJar.Download d = ServerJar.serverDownload(new String(net.get(ServerJar.versionJsonUrl(
                    new String(net.get(ServerJar.MANIFEST_URL), StandardCharsets.UTF_8), MC_VERSION)), StandardCharsets.UTF_8));
            write(server, net.get(d.url()));
            ServerJar.verifyOrDelete(server, d);
            Files.writeString(known, d.sha1() + "\n");
        }
        // 2. Fabric 服务器启动器
        Path launcher = root.resolve("fabric-server-launch.jar");
        if (!Files.exists(launcher)) {
            write(launcher, net.get(FABRIC_LAUNCHER_URL));
        }
        Files.writeString(root.resolve("fabric-server-launcher.properties"), "serverJar=server.jar\n");
        // 3. worker mod 和 Fabric API（客户端 jar 里带着）
        syncMods(bundled);
        // 4. 玩家已在同意界面上本人同意了 Minecraft EULA
        Files.writeString(root.resolve("eula.txt"),
                "# 玩家在 mc-donate 的同意界面上同意了 Minecraft EULA（https://aka.ms/MinecraftEULA）\neula=true\n");
    }

    /**
     * 按客户端 jar 里带的放 worker mod：同名的覆盖，mods/ 里不在清单上的 jar 删掉，别的文件不动。
     * 每次起 worker 前都调（11-09：原来只在第一次准备时放，客户端升级以后 worker 还用旧 mod）。
     */
    public void syncMods(Bundled bundled) throws IOException {
        Path mods = root.resolve("mods");
        Files.createDirectories(mods);
        List<String> names = bundledNames(bundled);
        for (String name : names) {
            try (InputStream in = bundled.open(name)) {
                if (in == null) {
                    throw new IOException("客户端 jar 里缺 " + name);
                }
                Files.copy(in, mods.resolve(name), StandardCopyOption.REPLACE_EXISTING);
            }
        }
        try (var files = Files.list(mods)) {
            for (Path p : files.toList()) {
                String n = p.getFileName().toString();
                if (n.endsWith(".jar") && !names.contains(n)) {
                    Files.delete(p);
                }
            }
        }
    }

    private static List<String> bundledNames(Bundled bundled) throws IOException {
        List<String> out = new ArrayList<>();
        try (InputStream in = bundled.open("index.txt")) {
            if (in == null) {
                throw new IOException("客户端 jar 里没有 worker mod 清单");
            }
            BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            String line;
            while ((line = r.readLine()) != null) {
                line = line.trim();
                if (!line.isEmpty() && line.matches("[A-Za-z0-9_.+-]+\\.jar")) {
                    out.add(line);
                }
            }
        }
        return out;
    }

    private static void write(Path file, byte[] data) throws IOException {
        Path tmp = file.resolveSibling(file.getFileName() + ".part");
        Files.write(tmp, data);
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    /**
     * worker 服务器的 server.properties：只听本机、离线、关看门狗（一个 tile 在一个 tick 里生成是设计如此）、
     * 视距小（没有玩家，生成走 worker mod 自己的路径）、不开 rcon 和 query。seed 由 worker mod 按邀请设（mcdonate.inviteSeed）。
     */
    public static String serverProperties(int port) {
        return String.join("\n",
                "server-ip=127.0.0.1",
                "server-port=" + port,
                "online-mode=false",
                "max-tick-time=-1",
                "view-distance=2",
                "simulation-distance=2",
                "max-players=1",
                "enable-rcon=false",
                "enable-query=false",
                "spawn-protection=0",
                "level-name=world",
                "motd=mc-donate worker") + "\n";
    }

    /** 写 server.properties（每次启动前写，端口每次挑一个空的）。 */
    public void writeServerProperties(int port) throws IOException {
        Files.writeString(root.resolve("server.properties"), serverProperties(port));
    }
}
