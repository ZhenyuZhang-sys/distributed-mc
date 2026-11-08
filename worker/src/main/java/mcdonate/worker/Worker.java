package mcdonate.worker;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import mcdonate.common.ModIds;
import mcdonate.common.TilePos;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** donor mod：领任务、生成、导出。 */
public final class Worker implements ModInitializer {
    public static final String MOD_ID = ModIds.of("worker");
    private static final Logger LOG = LoggerFactory.getLogger(MOD_ID);

    @Override
    public void onInitialize() {
        // /donor export <tx> <tz>：调试命令，只给 OP 和控制台（RCON）用
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
                dispatcher.register(Commands.literal("donor")
                        .requires(source -> source.hasPermission(2))
                        .then(Commands.literal("export")
                                .then(Commands.argument("tx", IntegerArgumentType.integer())
                                        .then(Commands.argument("tz", IntegerArgumentType.integer())
                                                .executes(ctx -> export(ctx.getSource(),
                                                        new TilePos(IntegerArgumentType.getInteger(ctx, "tx"),
                                                                IntegerArgumentType.getInteger(ctx, "tz")))))))));
        LOG.info("{} loaded", MOD_ID);
    }

    private static int export(CommandSourceStack source, TilePos tile) {
        try {
            TileExporter.Result r = TileExporter.export(source.getServer().overworld(), tile);
            LOG.info("导出 tile {}：{} 个 chunk，{} ms，{}", tile, r.chunks(), r.millis(), r.file());
            String json = r.json();
            source.sendSuccess(() -> Component.literal(json), false);
            return 1;
        } catch (TileExporter.Refused e) {
            LOG.warn("导出 tile {} 被拒绝：{}", tile, e.getMessage());
            source.sendFailure(Component.literal(e.getMessage()));
            return 0;
        } catch (Exception e) {
            LOG.error("导出 tile {} 失败", tile, e);
            source.sendFailure(Component.literal("export failed: " + e));
            return 0;
        }
    }
}
