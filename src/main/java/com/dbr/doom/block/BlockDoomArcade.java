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

package com.dbr.doom.block;

import net.minecraft.block.BlockContainer;
import net.minecraft.block.material.Material;
import net.minecraft.client.renderer.texture.IIconRegister;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.item.ItemStack;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.EnumChatFormatting;
import net.minecraft.util.IIcon;
import net.minecraft.util.MathHelper;
import net.minecraft.world.World;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;

import com.dbr.doom.DbrDoomMod;
import com.dbr.doom.network.DoomNetwork;
import com.dbr.doom.network.PacketOpenArcade;

/**
 * A cabinet that plays Doom on one face.
 *
 * Right clicking claims it. The claim is granted by the server, which then
 * tells that one client to start its own engine, so two players cannot end up
 * both believing they have the machine.
 */
public class BlockDoomArcade extends BlockContainer {

    public static final String NAME = "doom_arcade";

    /*
     * Facing lives in the metadata, the way a furnace does it: 2 north, 3 south,
     * 4 west, 5 east. The screen is on that face.
     */
    /** Eight blocks, comfortably past vanilla's own reach of about five. */
    private static final double MAX_REACH_SQUARED = 64.0D;

    private static final int FACING_NORTH = 2;
    private static final int FACING_SOUTH = 3;
    private static final int FACING_WEST = 4;
    private static final int FACING_EAST = 5;

    @SideOnly(Side.CLIENT)
    private IIcon iconFront;
    @SideOnly(Side.CLIENT)
    private IIcon iconSide;
    @SideOnly(Side.CLIENT)
    private IIcon iconTop;
    @SideOnly(Side.CLIENT)
    private IIcon iconBottom;

    public BlockDoomArcade() {
        super(Material.iron);
        setBlockName(DbrDoomMod.MODID + "." + NAME);
        setHardness(3.5F);
        setResistance(10.0F);
        setStepSound(soundTypeMetal);
        setHarvestLevel("pickaxe", 1);
        setCreativeTab(net.minecraft.creativetab.CreativeTabs.tabRedstone);
    }

    @Override
    public TileEntity createNewTileEntity(World world, int meta) {
        return new TileEntityDoomArcade();
    }

    @Override
    public boolean onBlockActivated(World world, int x, int y, int z,
            EntityPlayer player, int side, float hitX, float hitY, float hitZ) {

        /*
         * The client does nothing here on purpose. It waits for the server to
         * grant the claim and send PacketOpenArcade back, so the decision is
         * made in exactly one place.
         */
        if (world.isRemote) {
            return true;
        }

        /*
         * Vanilla checks reach before it ever calls this, but only for a client
         * that plays fair. A cabinet is a shared resource and claiming one from
         * across the map is worth denying on its own terms.
         */
        if (player.getDistanceSq(x + 0.5D, y + 0.5D, z + 0.5D) > MAX_REACH_SQUARED) {
            return true;
        }

        final TileEntity te = world.getTileEntity(x, y, z);
        if (!(te instanceof TileEntityDoomArcade)) {
            return true;
        }

        final TileEntityDoomArcade arcade = (TileEntityDoomArcade) te;

        if (!ArcadeClaims.grant(player, arcade)) {
            player.addChatMessage(new ChatComponentText(
                EnumChatFormatting.RED + arcade.getOccupant() + " is using this machine."));
            return true;
        }

        DoomNetwork.toClient(new PacketOpenArcade(x, y, z), (EntityPlayerMP) player);
        return true;
    }

    /** Faces the player who placed it, so the screen is the side they see. */
    @Override
    public void onBlockPlacedBy(World world, int x, int y, int z,
            EntityLivingBase placer, ItemStack stack) {

        final int quadrant = MathHelper.floor_double(
            (placer.rotationYaw * 4.0F / 360.0F) + 0.5D) & 3;

        final int facing;
        switch (quadrant) {
            case 0:
                facing = FACING_NORTH;
                break;
            case 1:
                facing = FACING_EAST;
                break;
            case 2:
                facing = FACING_SOUTH;
                break;
            default:
                facing = FACING_WEST;
                break;
        }

        world.setBlockMetadataWithNotify(x, y, z, facing, 2);
    }

    /** Frees the claim so a broken and replaced cabinet is not stuck forever. */
    @Override
    public void breakBlock(World world, int x, int y, int z,
            net.minecraft.block.Block block, int meta) {

        final TileEntity te = world.getTileEntity(x, y, z);
        if (te instanceof TileEntityDoomArcade) {
            ((TileEntityDoomArcade) te).forceRelease();
        }
        super.breakBlock(world, x, y, z, block, meta);
    }

    @SideOnly(Side.CLIENT)
    @Override
    public void registerBlockIcons(IIconRegister register) {
        final String prefix = DbrDoomMod.MODID + ":doom_arcade_";
        iconFront = register.registerIcon(prefix + "front");
        iconSide = register.registerIcon(prefix + "side");
        iconTop = register.registerIcon(prefix + "top");
        iconBottom = register.registerIcon(prefix + "bottom");
    }

    @SideOnly(Side.CLIENT)
    @Override
    public IIcon getIcon(int side, int meta) {
        if (side == 0) {
            return iconBottom;
        }
        if (side == 1) {
            return iconTop;
        }

        /*
         * Freshly placed items have no facing yet, so show the screen on what
         * the inventory model treats as the front rather than a blank side.
         */
        if (meta < FACING_NORTH) {
            return side == FACING_SOUTH ? iconFront : iconSide;
        }

        return side == meta ? iconFront : iconSide;
    }
}
