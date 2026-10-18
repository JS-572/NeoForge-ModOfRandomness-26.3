package net.js.random;

import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;

@Mod(RandomnessMod.MOD_ID)
public final class RandomnessMod {
    public static final String MOD_ID = "random";

    public RandomnessMod() {
        NeoForge.EVENT_BUS.addListener(RandomizationEvents::onBlockDrops);
        NeoForge.EVENT_BUS.addListener(RandomizationEvents::onLootTableLoad);
    }
}
