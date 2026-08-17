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

package com.dbr.doom.client;

import java.util.Arrays;
import java.util.List;

import net.minecraft.command.CommandBase;
import net.minecraft.command.ICommandSender;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.EnumChatFormatting;

import com.dbr.doom.host.DoomHost;

/**
 * /DoomStop - ends the current Doom session.
 *
 * Normally F10 or a double-tap of ESC is enough. This exists for the case where
 * the session is wedged and the GUI is not responding to input.
 */
public class CommandDoomStop extends CommandBase {

    @Override
    public String getCommandName() {
        return "DoomStop";
    }

    @SuppressWarnings("rawtypes")
    @Override
    public List getCommandAliases() {
        return Arrays.asList("doomstop");
    }

    @Override
    public String getCommandUsage(ICommandSender sender) {
        return "/DoomStop - end the running Doom session.";
    }

    @Override
    public boolean canCommandSenderUseCommand(ICommandSender sender) {
        return true;
    }

    @Override
    public int getRequiredPermissionLevel() {
        return 0;
    }

    @Override
    public void processCommand(ICommandSender sender, String[] args) {
        final DoomHost host = DoomHost.getActive();

        if (host == null) {
            sender.addChatMessage(new ChatComponentText(
                EnumChatFormatting.RED + "Doom is not running."));
            return;
        }

        host.stop();
        sender.addChatMessage(new ChatComponentText(
            EnumChatFormatting.GRAY + "Doom session ended."));
    }
}
