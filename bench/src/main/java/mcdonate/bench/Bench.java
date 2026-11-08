package mcdonate.bench;

import mcdonate.common.ModIds;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
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
        // /bench stats：计数输出成一行 JSON；/bench reset：清零。只给 OP 和控制台（RCON）用。
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
                dispatcher.register(Commands.literal("bench")
                        .requires(source -> source.hasPermission(2))
                        .then(Commands.literal("stats").executes(ctx -> {
                            ctx.getSource().sendSuccess(() -> Component.literal(Counters.json()), false);
                            return 1;
                        }))
                        .then(Commands.literal("reset").executes(ctx -> {
                            Counters.reset();
                            ctx.getSource().sendSuccess(() -> Component.literal("bench counters reset"), false);
                            return 1;
                        }))));
        LOG.info("{} loaded", MOD_ID);
    }
}
