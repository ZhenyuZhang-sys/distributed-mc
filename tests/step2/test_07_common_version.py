"""11-10（distributed-mc-75 在演示服务器踩到）：bench、coordinator、worker、客户端各自嵌了一份 mcdonate-common（jar-in-jar），
原来版本号都是 0.1.0。两个 jar 来自不同提交时 Fabric 只加载其中一份 common，报 NoSuchMethodError Heartbeat.boost()、worker 不停断开。
修：common 的版本号带上构建它的提交（0.1.0-b<提交号>），每个 mod 要求的 common 写成 =这个版本。
来自不同提交的 jar 放在一起时，Fabric 启动时就报"依赖的版本不对"，不会跑到一半才出错。
"""
import io
import json
import re
import zipfile

from mcd import REPO_ROOT

MODS = {
    "coordinator": REPO_ROOT / "build" / "mods",
    "bench": REPO_ROOT / "build" / "mods",
    "worker": REPO_ROOT / "worker" / "build" / "libs",
}
VERSION = re.compile(r"^0\.1\.0-b([0-9a-f]{7,}(\.dirty)?|dev)$")


def mod_json(jar_bytes):
    with zipfile.ZipFile(io.BytesIO(jar_bytes)) as z:
        meta = json.loads(z.read("fabric.mod.json"))
        nested = {}
        for entry in meta.get("jars", []):
            inner = z.read(entry["file"])
            with zipfile.ZipFile(io.BytesIO(inner)) as zi:
                m = json.loads(zi.read("fabric.mod.json"))
                nested[m["id"]] = m["version"]
        return meta, nested


def jars():
    out = {}
    for module, d in MODS.items():
        (j,) = [p for p in d.glob(f"mcdonate-{module}-*.jar") if "sources" not in p.name]
        out[module] = j.read_bytes()
    (c,) = [p for p in (REPO_ROOT / "client" / "build" / "libs").glob("mcdonate-client-*.jar") if "sources" not in p.name]
    out["client"] = c.read_bytes()
    return out


def test_common_version_carries_build_identity_and_is_required_exactly():
    versions = set()
    for module, data in jars().items():
        meta, nested = mod_json(data)
        common = nested.get("mcdonate-common")
        assert common, f"{module} 里没有嵌 mcdonate-common：{nested}"
        assert VERSION.match(common), f"{module} 嵌的 common 版本不带提交号：{common}"
        assert meta["depends"]["mcdonate-common"] == "=" + common, f"{module} 要求的 common 不是精确版本：{meta['depends']}"
        versions.add(common)
    assert len(versions) == 1, f"同一次构建里各 jar 嵌的 common 版本应该一样：{versions}"
