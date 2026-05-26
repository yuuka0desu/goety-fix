package com.example.goetyfix;

import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.Mod;

@Mod("goetyfix")
public class GoetyApostleFix {
    public GoetyApostleFix() {
        MinecraftForge.EVENT_BUS.register(PlayerLeakFixer.class);
    }
}
