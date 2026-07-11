package qouteall.imm_ptl.core.platform_specific;

import me.shedaniel.autoconfig.AutoConfig;
import me.shedaniel.autoconfig.ConfigHolder;
import me.shedaniel.autoconfig.ConfigManager;
import me.shedaniel.autoconfig.gui.ConfigScreenProvider;
import me.shedaniel.autoconfig.gui.DefaultGuiProviders;
import me.shedaniel.autoconfig.gui.registry.GuiRegistry;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.gui.screens.Screen;

@Environment(EnvType.CLIENT)
public class IPConfigGUI {
    public static Screen createClothConfigScreen(Screen parent) {
        ConfigHolder<IPConfig> holder = AutoConfig.getConfigHolder(IPConfig.class);
        ConfigManager<IPConfig> manager = (ConfigManager<IPConfig>) holder;
        return new ConfigScreenProvider<>(
            manager, DefaultGuiProviders.apply(new GuiRegistry()), parent
        ).get();
    }
}
