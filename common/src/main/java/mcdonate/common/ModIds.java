package mcdonate.common;

/** 各模块的 mod id 统一为 mcdonate-<模块名>。 */
public final class ModIds {
    public static final String PREFIX = "mcdonate-";

    private ModIds() {}

    public static String of(String module) {
        if (!module.matches("[a-z][a-z0-9_]*")) {
            throw new IllegalArgumentException("模块名只能用小写字母、数字和 _：" + module);
        }
        return PREFIX + module;
    }
}
