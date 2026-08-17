/*
 * DbrDoomMod - play Doom inside Minecraft 1.7.10.
 * Copyright (C) 2026  DbrDoomMod contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package com.dbr.doom;

import java.io.File;

import net.minecraft.init.Blocks;
import net.minecraft.init.Items;
import net.minecraft.item.ItemStack;

import cpw.mods.fml.common.Mod;
import cpw.mods.fml.common.SidedProxy;
import cpw.mods.fml.common.event.FMLInitializationEvent;
import cpw.mods.fml.common.event.FMLPreInitializationEvent;
import cpw.mods.fml.common.registry.GameRegistry;

import org.apache.logging.log4j.Logger;

import com.dbr.doom.block.ArcadeClaims;
import com.dbr.doom.block.BlockDoomArcade;
import com.dbr.doom.block.TileEntityDoomArcade;
import com.dbr.doom.network.DoomNetwork;

/**
 * Entry point.
 *
 * The Doom engine only ever runs on a client, but the arcade block has to exist
 * on both sides, so this is a normal two-sided mod and the server needs it
 * installed too. Everything that can only work on a client lives behind
 * {@link CommonProxy}.
 */
@Mod(
    modid = DbrDoomMod.MODID,
    name = DbrDoomMod.NAME,
    version = DbrDoomMod.VERSION,
    acceptedMinecraftVersions = "[1.7.10]"
)
public class DbrDoomMod {

    public static final String MODID = "dbrdoom";
    public static final String NAME = "DbrDoomMod";
    public static final String VERSION = "0.3.1";

    @Mod.Instance(MODID)
    public static DbrDoomMod instance;

    @SidedProxy(
        clientSide = "com.dbr.doom.client.ClientProxy",
        serverSide = "com.dbr.doom.CommonProxy"
    )
    public static CommonProxy proxy;

    public static BlockDoomArcade arcadeBlock;

    private static Logger logger;

    /** config/dbrdoom/ - holds wads, saves and the engine config files. */
    private static File baseDir;

    @Mod.EventHandler
    public void preInit(FMLPreInitializationEvent event) {
        logger = event.getModLog();

        baseDir = new File(event.getModConfigurationDirectory(), MODID);
        mkdirs(baseDir);
        mkdirs(new File(baseDir, "wads"));
        mkdirs(new File(baseDir, "saves"));

        logger.info("Base directory: {}", baseDir.getAbsolutePath());

        arcadeBlock = new BlockDoomArcade();
        GameRegistry.registerBlock(arcadeBlock, BlockDoomArcade.NAME);
        GameRegistry.registerTileEntity(TileEntityDoomArcade.class, MODID + "_arcade");

        DoomNetwork.init();

        /*
         * Packet handlers run on a netty thread and cannot touch the world from
         * there. 1.7.10's MinecraftServer has no task queue of its own, so this
         * is where the handovers land.
         */
        ServerTaskQueue.register();
        ArcadeClaims.register();

        proxy.preInit(event.getSuggestedConfigurationFile(), baseDir);
    }

    @Mod.EventHandler
    public void init(FMLInitializationEvent event) {
        /*
         * Iron frame, glass screen, redstone wiring, and an ender eye to keep it
         * out of reach early on so servers do not fill up with cabinets.
         */
        GameRegistry.addRecipe(new ItemStack(arcadeBlock),
            "IGI",
            "RER",
            "IRI",
            'I', Items.iron_ingot,
            'G', Blocks.glass,
            'R', Items.redstone,
            'E', Items.ender_eye);

        proxy.init();
    }

    public static CommonProxy proxy() {
        return proxy;
    }

    public static Logger logger() {
        return logger;
    }

    public static File baseDir() {
        return baseDir;
    }

    public static File wadDir() {
        return new File(baseDir, "wads");
    }

    private static void mkdirs(File dir) {
        if (!dir.isDirectory() && !dir.mkdirs()) {
            logger.error("Could not create directory: {}", dir.getAbsolutePath());
        }
    }
}
