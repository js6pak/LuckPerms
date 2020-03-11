/*
 * This file is part of LuckPerms, licensed under the MIT License.
 *
 *  Copyright (c) lucko (Luck) <luck@lucko.me>
 *  Copyright (c) contributors
 *
 *  Permission is hereby granted, free of charge, to any person obtaining a copy
 *  of this software and associated documentation files (the "Software"), to deal
 *  in the Software without restriction, including without limitation the rights
 *  to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 *  copies of the Software, and to permit persons to whom the Software is
 *  furnished to do so, subject to the following conditions:
 *
 *  The above copyright notice and this permission notice shall be included in all
 *  copies or substantial portions of the Software.
 *
 *  THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 *  IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 *  FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 *  AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 *  LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 *  OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 *  SOFTWARE.
 */

package me.lucko.luckperms.canyon.listeners;

import me.lucko.luckperms.canyon.LPCanyonBootstrap;
import me.lucko.luckperms.canyon.LPCanyonPlugin;
import me.lucko.luckperms.common.config.ConfigKeys;
import me.lucko.luckperms.common.locale.message.Message;
import org.bukkit.command.CommandSender;
import org.bukkit.event.Event;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerListener;
import org.bukkit.event.server.PluginEnableEvent;
import org.bukkit.event.server.ServerCommandEvent;
import org.bukkit.event.server.ServerListener;

public class CanyonPlatformListener {
    private final LPCanyonPlugin plugin;

    public CanyonPlatformListener(LPCanyonPlugin plugin, LPCanyonBootstrap bootstrap) {
        this.plugin = plugin;

        bootstrap.getServer().getPluginManager().registerEvent(Event.Type.PLAYER_COMMAND_PREPROCESS, new PlayerListener() {
            @Override
            public void onPlayerCommandPreprocess(PlayerCommandPreprocessEvent event) {
                if(handleCommand(event.getPlayer(), event.getMessage().toLowerCase()))
                    event.setCancelled(true);
            }
        }, Event.Priority.Normal, bootstrap);
        bootstrap.getServer().getPluginManager().registerEvent(Event.Type.SERVER_COMMAND, new ServerListener() {
            @Override
            public void onServerCommand(ServerCommandEvent event) {
                if(handleCommand(event.getSender(), event.getCommand().toLowerCase()))
                    event.setCommand(null);
            }
        }, Event.Priority.Normal, bootstrap);
        bootstrap.getServer().getPluginManager().registerEvent(Event.Type.PLUGIN_ENABLE, new ServerListener() {
            @Override
            public void onPluginEnable(PluginEnableEvent event) {
                if (event.getPlugin().getDescription().getName().equalsIgnoreCase("Vault")) {
                    plugin.tryVaultHook(true);
                }
            }
        }, Event.Priority.Normal, bootstrap);
    }

    private boolean handleCommand(CommandSender sender, String s) {
        if (s.isEmpty()) {
            return false;
        }

        if (this.plugin.getConfiguration().get(ConfigKeys.OPS_ENABLED)) {
            return false;
        }

        if (s.charAt(0) == '/') {
            s = s.substring(1);
        }

        if (s.contains(":")) {
            s = s.substring(s.indexOf(':') + 1);
        }

        if (s.equals("op") || s.startsWith("op ") || s.equals("deop") || s.startsWith("deop ")) {
            sender.sendMessage(Message.OP_DISABLED.asString(this.plugin.getLocaleManager()));
            return true;
        }

        return false;
    }

//    TODO implement this thing
//    @EventHandler(priority = EventPriority.LOWEST)
//    public void onWorldChange(PlayerChangedWorldEvent e) {
//        this.plugin.getContextManager().invalidateCache(e.getPlayer());
//        this.plugin.refreshAutoOp(e.getPlayer(), true);
//    }
}
