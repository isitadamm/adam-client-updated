package dev.adam.client.module.blocks;

import dev.adam.client.module.Category;
import dev.adam.client.module.Module;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;

/** Adam Blocks: highlight chosen blocks through walls, with grouped counts on hover. */
public class BlocksModule extends Module {

    public static BlocksModule INSTANCE;

    private final IntSetting range = addSetting("range", "Range", 16, 128, 48);
    private final IntSetting group = addSetting("group", "Group distance", 0, 3, 1);

    public BlocksModule() {
        super("blocks", "Adam Blocks",
                "Highlight the blocks you choose through walls. Hover one to see names and grouped counts.",
                Category.BLOCKS, -1);
        INSTANCE = this;
    }

    public static boolean isActive() {
        return INSTANCE != null && INSTANCE.isEnabled();
    }

    public IntSetting rangeSetting() {
        return range;
    }

    public IntSetting groupSetting() {
        return group;
    }

    public int range() {
        return range.get();
    }

    public int groupDistance() {
        return group.get();
    }

    @Override
    protected boolean persistsEnabled() {
        return true;
    }

    @Override
    protected void onEnable(Minecraft mc) {
        Tracker.dirty = true;
    }

    @Override
    protected void onDisable(Minecraft mc) {
        Tracker.clear();
    }

    @Override
    public void onEndTick(Minecraft mc) {
        Tracker.tick(mc);
    }

    @Override
    public void onSettingChanged(IntSetting setting) {
        Tracker.dirty = true;
    }

    @Override
    public boolean hasConfigScreen() {
        return true;
    }

    @Override
    public Screen createConfigScreen(Screen parent) {
        return new BlocksScreen(parent);
    }
}
