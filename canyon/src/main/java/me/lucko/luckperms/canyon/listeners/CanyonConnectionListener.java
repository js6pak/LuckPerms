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
import me.lucko.luckperms.canyon.inject.permissible.LuckPermsPermissible;
import me.lucko.luckperms.canyon.inject.permissible.PermissibleInjector;
import me.lucko.luckperms.common.config.ConfigKeys;
import me.lucko.luckperms.common.locale.message.Message;
import me.lucko.luckperms.common.model.User;
import me.lucko.luckperms.common.plugin.util.AbstractConnectionListener;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerListener;
import org.bukkit.event.player.PlayerLoginEvent;
import org.bukkit.event.player.PlayerPreLoginEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

public class CanyonConnectionListener extends AbstractConnectionListener implements Listener {
    private final Set<UUID> deniedAsyncLogin = Collections.synchronizedSet(new HashSet<>());
    private final Set<UUID> deniedLogin = Collections.synchronizedSet(new HashSet<>());

    public CanyonConnectionListener(LPCanyonPlugin plugin, LPCanyonBootstrap bootstrap) {
        super(plugin);

        bootstrap.getServer().getPluginManager().registerEvent(Event.Type.PLAYER_PRELOGIN, new PlayerListener() {
            @Override
            public void onPlayerPreLogin(PlayerPreLoginEvent e) {
            /* Called when the player first attempts a connection with the server.
               Listening on LOW priority to allow plugins to modify username / UUID data here. (auth plugins)
               Also, give other plugins a chance to cancel the event. */

            /* wait for the plugin to enable. because these events are fired async, they can be called before
               the plugin has enabled.  */
                try {
                    plugin.getBootstrap().getEnableLatch().await(60, TimeUnit.SECONDS);
                } catch (InterruptedException ex) {
                    ex.printStackTrace();
                }

                if (plugin.getConfiguration().get(ConfigKeys.DEBUG_LOGINS)) {
                    plugin.getLogger().info("Processing pre-login for " + e.getName());
                }

                if (e.getResult() != PlayerPreLoginEvent.Result.ALLOWED) {
                    // another plugin has disallowed the login.
                    plugin.getLogger().info("Another plugin has cancelled the connection for " + e.getUniqueId() + " - " + e.getName() + ". No permissions data will be loaded.");
                    deniedAsyncLogin.add(e.getUniqueId());
                    return;
                }

            /* Actually process the login for the connection.
               We do this here to delay the login until the data is ready.
               If the login gets cancelled later on, then this will be cleaned up.

               This includes:
               - loading uuid data
               - loading permissions
               - creating a user instance in the UserManager for this connection.
               - setting up cached data. */
                try {
                    User user = loadUser(e.getUniqueId(), e.getName());
                    recordConnection(e.getUniqueId());
                    plugin.getEventDispatcher().dispatchPlayerLoginProcess(e.getUniqueId(), e.getName(), user);
                } catch (Exception ex) {
                    plugin.getLogger().severe("Exception occurred whilst loading data for " + e.getUniqueId() + " - " + e.getName());
                    ex.printStackTrace();

                    // deny the connection
                    deniedAsyncLogin.add(e.getUniqueId());
                    e.disallow(PlayerPreLoginEvent.Result.KICK_OTHER, Message.LOADING_DATABASE_ERROR.asString(plugin.getLocaleManager()));
                    plugin.getEventDispatcher().dispatchPlayerLoginProcess(e.getUniqueId(), e.getName(), null);
                }
            }
        }, Event.Priority.Low, bootstrap);
        bootstrap.getServer().getPluginManager().registerEvent(Event.Type.PLAYER_LOGIN, new PlayerListener() {
            @Override
            public void onPlayerLogin(PlayerLoginEvent e) {
            /* Called when the player starts logging into the server.
           At this point, the users data should be present and loaded. */

                final Player player = e.getPlayer();

                if (plugin.getConfiguration().get(ConfigKeys.DEBUG_LOGINS)) {
                    plugin.getLogger().info("Processing login for " + player.getUniqueId() + " - " + player.getName());
                }

                User user = plugin.getUserManager().getIfLoaded(player.getUniqueId());

                /* User instance is null for whatever reason. Could be that it was unloaded between asyncpre and now. */
                if (user == null) {
                    if(bootstrap.getServer().getOnlineMode()) {
                        deniedLogin.add(player.getUniqueId());

                        if (!getUniqueConnections().contains(player.getUniqueId())) {

                            plugin.getLogger().warn("User " + player.getUniqueId() + " - " + player.getName() +
                                    " doesn't have data pre-loaded, they have never been processed during pre-login in this session." +
                                    " - denying login.");
                        } else {
                            plugin.getLogger().warn("User " + player.getUniqueId() + " - " + player.getName() +
                                    " doesn't currently have data pre-loaded, but they have been processed before in this session." +
                                    " - denying login.");
                        }

                        e.disallow(PlayerLoginEvent.Result.KICK_OTHER, Message.LOADING_STATE_ERROR.asString(plugin.getLocaleManager()));
                        return;
                    } else {
                        user = loadUser(player.getUniqueId(), player.getName());
                        recordConnection(player.getUniqueId());
                        plugin.getEventDispatcher().dispatchPlayerLoginProcess(player.getUniqueId(), player.getName(), user);
                    }
                }

                // User instance is there, now we can inject our custom Permissible into the player.
                // Care should be taken at this stage to ensure that async tasks which manipulate bukkit data check that the player is still online.
                try {
                    // Make a new permissible for the user
                    LuckPermsPermissible lpPermissible = new LuckPermsPermissible(player, user, plugin);

                    // Inject into the player
                    PermissibleInjector.inject(player, lpPermissible);

                } catch (Throwable t) {
                    plugin.getLogger().warn("Exception thrown when setting up permissions for " +
                            player.getUniqueId() + " - " + player.getName() + " - denying login.");
                    t.printStackTrace();

                    e.disallow(PlayerLoginEvent.Result.KICK_OTHER, Message.LOADING_SETUP_ERROR.asString(plugin.getLocaleManager()));
                    return;
                }

                plugin.refreshAutoOp(player, true);
            }
        }, Event.Priority.Normal, bootstrap);

        bootstrap.getServer().getPluginManager().registerEvent(Event.Type.PLAYER_PRELOGIN, new PlayerListener() {
            @Override
            public void onPlayerPreLogin(PlayerPreLoginEvent e) {
                /* Listen to see if the event was cancelled after we initially handled the connection
               If the connection was cancelled here, we need to do something to clean up the data that was loaded. */

                // Check to see if this connection was denied at LOW.
                if (deniedAsyncLogin.remove(e.getUniqueId())) {
                    // their data was never loaded at LOW priority, now check to see if they have been magically allowed since then.

                    // This is a problem, as they were denied at low priority, but are now being allowed.
                    if (e.getResult() == PlayerPreLoginEvent.Result.ALLOWED) {
                        plugin.getLogger().severe("Player connection was re-allowed for " + e.getUniqueId());
                        e.disallow(PlayerPreLoginEvent.Result.KICK_OTHER, "");
                    }
                }
            }
        }, Event.Priority.Monitor, bootstrap);
        bootstrap.getServer().getPluginManager().registerEvent(Event.Type.PLAYER_LOGIN, new PlayerListener() {
            @Override
            public void onPlayerLogin(PlayerLoginEvent e) {
            /* Listen to see if the event was cancelled after we initially handled the login
               If the connection was cancelled here, we need to do something to clean up the data that was loaded. */

                // Check to see if this connection was denied at LOW. Even if it was denied at LOW, their data will still be present.
                if (deniedLogin.remove(e.getPlayer().getUniqueId())) {
                    // This is a problem, as they were denied at low priority, but are now being allowed.
                    if (e.getResult() == PlayerLoginEvent.Result.ALLOWED) {
                        plugin.getLogger().severe("Player connection was re-allowed for " + e.getPlayer().getUniqueId());
                        e.disallow(PlayerLoginEvent.Result.KICK_OTHER, "");
                    }
                }
            }
        }, Event.Priority.Monitor, bootstrap);
        bootstrap.getServer().getPluginManager().registerEvent(Event.Type.PLAYER_QUIT, new PlayerListener() {
            // Wait until the last priority to unload, so plugins can still perform permission checks on this event
            @Override
            public void onPlayerQuit(PlayerQuitEvent e) {
                final Player player = e.getPlayer();
                handleDisconnect(player.getUniqueId());

                // perform unhooking from bukkit objects 1 tick later.
                // this allows plugins listening after us on MONITOR to still have intact permissions data
                plugin.getBootstrap().getServer().getScheduler().scheduleAsyncDelayedTask(plugin.getBootstrap(), () -> {
                    // Remove the custom permissible
                    try {
                        PermissibleInjector.uninject(player, true);
                    } catch (Exception ex) {
                        ex.printStackTrace();
                    }

                    // Handle auto op
                    if (plugin.getConfiguration().get(ConfigKeys.AUTO_OP)) {
                        player.setOp(false);
                    }

                    // remove their contexts cache
                    plugin.getContextManager().onPlayerQuit(player);
                }, 1L);
            }
        }, Event.Priority.Monitor, bootstrap);
    }
}
