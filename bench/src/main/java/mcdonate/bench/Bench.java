package mcdonate.bench;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import mcdonate.common.ModIds;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** 测量 mod：生成计数、阶段计时。 */
public final class Bench implements ModInitializer {
    public static final String MOD_ID = ModIds.of("bench");
    private static final Logger LOG = LoggerFactory.getLogger(MOD_ID);

    @Override
    public void onInitialize() {
        // 只给 OP 和控制台（RCON）用：
        //   /bench stats          计数，一行 JSON
        //   /bench stages         各阶段计时，一行 JSON
        //   /bench timing on|off  阶段计时开关，默认关
        //   /bench reset          计数和计时都清零
        //   /bench track on|off   按 chunk 记录 noise、features（测试用，默认关）
        //   /bench chunk <x> <z>  这个 chunk 跑过几次 noise、features
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
                dispatcher.register(Commands.literal("bench")
                        .requires(source -> source.hasPermission(2))
                        .then(Commands.literal("stats").executes(ctx -> {
                            ctx.getSource().sendSuccess(() -> Component.literal(Counters.json()), false);
                            return 1;
                        }))
                        .then(Commands.literal("reset").executes(ctx -> {
                            Counters.reset();
                            Stages.reset();
                            ctx.getSource().sendSuccess(() -> Component.literal("bench counters reset"), false);
                            return 1;
                        }))
                        .then(Commands.literal("stages").executes(ctx -> {
                            ctx.getSource().sendSuccess(() -> Component.literal(Stages.json()), false);
                            return 1;
                        }))
                        .then(Commands.literal("track")
                                .then(Commands.literal("on").executes(ctx -> setTracking(ctx.getSource(), true)))
                                .then(Commands.literal("off").executes(ctx -> setTracking(ctx.getSource(), false))))
                        .then(Commands.literal("chunk")
                                .then(Commands.argument("x", IntegerArgumentType.integer())
                                        .then(Commands.argument("z", IntegerArgumentType.integer())
                                                .executes(ctx -> {
                                                    String s = Counters.ran(IntegerArgumentType.getInteger(ctx, "x"),
                                                            IntegerArgumentType.getInteger(ctx, "z"));
                                                    ctx.getSource().sendSuccess(() -> Component.literal(s), false);
                                                    return 1;
                                                }))))
                        .then(Commands.literal("timing")
                                .then(Commands.literal("on").executes(ctx -> setTiming(ctx.getSource(), true)))
                                .then(Commands.literal("off").executes(ctx -> setTiming(ctx.getSource(), false))))));
        LOG.info("{} loaded", MOD_ID);
    }

    private static int setTracking(CommandSourceStack source, boolean on) {
        Counters.setTracking(on);
        source.sendSuccess(() -> Component.literal("bench track " + (on ? "on" : "off")), false);
        return 1;
    }

    private static int setTiming(CommandSourceStack source, boolean on) {
        Stages.setEnabled(on);
        source.sendSuccess(() -> Component.literal("bench timing " + (on ? "on" : "off")), false);
        return 1;
    }
}
