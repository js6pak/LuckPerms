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

package me.lucko.luckperms.canyon;

import me.lucko.luckperms.canyon.calculator.CanyonCalculatorFactory;
import me.lucko.luckperms.canyon.context.CanyonContextManager;
import me.lucko.luckperms.canyon.context.WorldCalculator;
import me.lucko.luckperms.canyon.inject.permissible.LuckPermsPermissible;
import me.lucko.luckperms.canyon.inject.permissible.PermissibleInjector;
import me.lucko.luckperms.canyon.inject.permissible.PermissibleMonitoringInjector;
import me.lucko.luckperms.canyon.inject.server.InjectorDefaultsMap;
import me.lucko.luckperms.canyon.inject.server.InjectorPermissionMap;
import me.lucko.luckperms.canyon.inject.server.InjectorSubscriptionMap;
import me.lucko.luckperms.canyon.inject.server.LuckPermsDefaultsMap;
import me.lucko.luckperms.canyon.inject.server.LuckPermsPermissionMap;
import me.lucko.luckperms.canyon.inject.server.LuckPermsSubscriptionMap;
import me.lucko.luckperms.canyon.listeners.CanyonConnectionListener;
import me.lucko.luckperms.canyon.listeners.CanyonPlatformListener;
import me.lucko.luckperms.canyon.messaging.CanyonMessagingFactory;
import me.lucko.luckperms.canyon.vault.VaultHookManager;
import me.lucko.luckperms.common.api.LuckPermsApiProvider;
import me.lucko.luckperms.common.api.implementation.ApiUser;
import me.lucko.luckperms.common.calculator.CalculatorFactory;
import me.lucko.luckperms.common.command.access.CommandPermission;
import me.lucko.luckperms.common.config.ConfigKeys;
import me.lucko.luckperms.common.config.adapter.ConfigurationAdapter;
import me.lucko.luckperms.common.dependencies.Dependency;
import me.lucko.luckperms.common.dependencies.relocation.Relocation;
import me.lucko.luckperms.common.event.AbstractEventBus;
import me.lucko.luckperms.common.messaging.MessagingFactory;
import me.lucko.luckperms.common.model.User;
import me.lucko.luckperms.common.model.manager.group.StandardGroupManager;
import me.lucko.luckperms.common.model.manager.track.StandardTrackManager;
import me.lucko.luckperms.common.model.manager.user.StandardUserManager;
import me.lucko.luckperms.common.plugin.AbstractLuckPermsPlugin;
import me.lucko.luckperms.common.plugin.util.AbstractConnectionListener;
import me.lucko.luckperms.common.sender.Sender;
import me.lucko.luckperms.common.tasks.CacheHousekeepingTask;
import me.lucko.luckperms.common.tasks.ExpireTemporaryTask;

import net.luckperms.api.LuckPerms;
import net.luckperms.api.event.user.UserDataRecalculateEvent;
import net.luckperms.api.query.QueryOptions;

import org.bukkit.command.PluginCommand;
import org.bukkit.entity.Player;
import org.bukkit.permissions.Permission;
import org.bukkit.permissions.PermissionDefault;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.ServicePriority;

import java.io.*;
import java.util.Arrays;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * LuckPerms implementation for the Canyon (Bukkit b1.7.3) API.
 */
public class LPCanyonPlugin extends AbstractLuckPermsPlugin {
    private final LPCanyonBootstrap bootstrap;

    private CanyonSenderFactory senderFactory;
    private CanyonConnectionListener connectionListener;
    private CanyonCommandExecutor commandManager;
    private StandardUserManager userManager;
    private StandardGroupManager groupManager;
    private StandardTrackManager trackManager;
    private CanyonContextManager contextManager;
    private LuckPermsSubscriptionMap subscriptionMap;
    private LuckPermsPermissionMap permissionMap;
    private LuckPermsDefaultsMap defaultPermissionMap;
    private VaultHookManager vaultHookManager = null;
    
    public LPCanyonPlugin(LPCanyonBootstrap bootstrap) {
        this.bootstrap = bootstrap;
    }

    @Override
    public LPCanyonBootstrap getBootstrap() {
        return this.bootstrap;
    }

    @Override
    protected void setupSenderFactory() {
        this.senderFactory = new CanyonSenderFactory(this);
    }

    @Override
    protected Set<Dependency> getGlobalDependencies() {
        Set<Dependency> dependencies = super.getGlobalDependencies();
        dependencies.add(Dependency.TEXT_ADAPTER_BUKKIT);
        return dependencies;
    }

    @Override
    protected ConfigurationAdapter provideConfigurationAdapter() {
        return new CanyonConfigAdapter(this, resolveConfig());
    }

    @Override
    protected void registerPlatformListeners() {
        connectionListener = new CanyonConnectionListener(this, this.bootstrap);
        new CanyonPlatformListener(this, this.bootstrap);
    }

    @Override
    protected MessagingFactory<?> provideMessagingFactory() {
        return new CanyonMessagingFactory(this);
    }

    @Override
    protected void registerCommands() {
        PluginCommand command = this.bootstrap.getCommand("luckperms");
        if (command == null) {
            getLogger().severe("Unable to register /luckperms command with the server");
            return;
        }

        this.commandManager = new CanyonCommandExecutor(this, command);
        this.commandManager.register();
    }

    @Override
    protected void setupManagers() {
        this.userManager = new StandardUserManager(this);
        this.groupManager = new StandardGroupManager(this);
        this.trackManager = new StandardTrackManager(this);
    }

    @Override
    protected CalculatorFactory provideCalculatorFactory() {
        return new CanyonCalculatorFactory(this);
    }

    @Override
    protected void setupContextManager() {
        this.contextManager = new CanyonContextManager(this);
        this.contextManager.registerCalculator(new WorldCalculator(this));
    }

    @Override
    protected void setupPlatformHooks() {
        // inject our own custom permission maps
        Runnable[] injectors = new Runnable[]{
                new InjectorSubscriptionMap(this),
                new InjectorPermissionMap(this),
                new InjectorDefaultsMap(this),
                new PermissibleMonitoringInjector(this, PermissibleMonitoringInjector.Mode.INJECT)
        };

        for (Runnable injector : injectors) {
            injector.run();

            // schedule another injection after all plugins have loaded
            // the entire pluginmanager instance is replaced by some plugins :(
            this.bootstrap.getServer().getScheduler().scheduleAsyncDelayedTask(this.bootstrap, injector, 1);
        }

        // Provide vault support
        tryVaultHook(false);
    }

    @Override
    protected AbstractEventBus<?> provideEventBus(LuckPermsApiProvider apiProvider) {
        return new CanyonEventBus(this, apiProvider);
    }

    public void tryVaultHook(boolean force) {
        if (this.vaultHookManager != null) {
            return; // already hooked
        }

        try {
            if (force || this.bootstrap.getServer().getPluginManager().isPluginEnabled("Vault")) {
                this.vaultHookManager = new VaultHookManager(this);
                this.vaultHookManager.hook();
                getLogger().info("Registered Vault permission & chat hook.");
            }
        } catch (Exception e) {
            this.vaultHookManager = null;
            getLogger().severe("Error occurred whilst hooking into Vault.");
            e.printStackTrace();
        }
    }

    @Override
    protected void registerApiOnPlatform(LuckPerms api) {
        this.bootstrap.getServer().getServicesManager().register(LuckPerms.class, api, this.bootstrap, ServicePriority.Normal);
    }

    @Override
    protected void registerHousekeepingTasks() {
        this.bootstrap.getScheduler().asyncRepeating(new ExpireTemporaryTask(this), 3, TimeUnit.SECONDS);
        this.bootstrap.getScheduler().asyncRepeating(new CacheHousekeepingTask(this), 2, TimeUnit.MINUTES);
    }

    @Override
    protected void performFinalSetup() {
        // register permissions
        try {
            PluginManager pm = this.bootstrap.getServer().getPluginManager();
            PermissionDefault permDefault = getConfiguration().get(ConfigKeys.COMMANDS_ALLOW_OP) ? PermissionDefault.OP : PermissionDefault.FALSE;

            for (CommandPermission p : CommandPermission.values()) {
                pm.addPermission(new Permission(p.getPermission(), permDefault));
            }
        } catch (Exception e) {
            // this throws an exception if the plugin is /reloaded, grr
        }

        // remove all operators on startup if they're disabled
//        if (!getConfiguration().get(ConfigKeys.OPS_ENABLED)) {
//            this.bootstrap.getServer().getScheduler().scheduleAsyncDelayedTask(this.bootstrap, () -> {
//                for (OfflinePlayer player : this.bootstrap.getServer().getOperators()) {
//                    player.setOp(false);
//                }
//            });
//        }

        // register autoop listener
        if (getConfiguration().get(ConfigKeys.AUTO_OP)) {
            getApiProvider().getEventBus().subscribe(UserDataRecalculateEvent.class, event -> {
                User user = ApiUser.cast(event.getUser());
                Optional<Player> player = getBootstrap().getPlayer(user.getUniqueId());
                player.ifPresent(p -> refreshAutoOp(p, false));
            });
        }

        // Load any online users (in the case of a reload)
        for (Player player : this.bootstrap.getServer().getOnlinePlayers()) {
            this.bootstrap.getScheduler().executeAsync(() -> {
                try {
                    User user = this.connectionListener.loadUser(player.getUniqueId(), player.getName());
                    if (user != null) {
                        this.bootstrap.getScheduler().executeSync(() -> {
                            try {
                                LuckPermsPermissible lpPermissible = new LuckPermsPermissible(player, user, this);
                                PermissibleInjector.inject(player, lpPermissible);
                            } catch (Throwable t) {
                                t.printStackTrace();
                            }
                        });
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                }
            });
        }
    }

    @Override
    protected void removePlatformHooks() {
        // uninject from players
        for (Player player : this.bootstrap.getServer().getOnlinePlayers()) {
            try {
                PermissibleInjector.uninject(player, false);
            } catch (Exception e) {
                e.printStackTrace();
            }

            if (getConfiguration().get(ConfigKeys.AUTO_OP)) {
                player.setOp(false);
            }

            final User user = getUserManager().getIfLoaded(player.getUniqueId());
            if (user != null) {
                user.getCachedData().invalidate();
                getUserManager().unload(user.getUniqueId());
            }
        }

        // uninject custom maps
        InjectorSubscriptionMap.uninject();
        InjectorPermissionMap.uninject();
        InjectorDefaultsMap.uninject();
        new PermissibleMonitoringInjector(this, PermissibleMonitoringInjector.Mode.UNINJECT).run();

        // unhook vault
        if (this.vaultHookManager != null) {
            this.vaultHookManager.unhook();
        }
    }

    public void refreshAutoOp(Player player, boolean callerIsSync) {
        if (!getConfiguration().get(ConfigKeys.AUTO_OP)) {
            return;
        }

        if (!callerIsSync && this.bootstrap.isServerStopping()) {
            return;
        }

        User user = getUserManager().getIfLoaded(player.getUniqueId());
        boolean value;

        if (user != null) {
            Map<String, Boolean> permData = user.getCachedData().getPermissionData(this.contextManager.getQueryOptions(player)).getPermissionMap();
            value = permData.getOrDefault("luckperms.autoop", false);
        } else {
            value = false;
        }

        if (callerIsSync) {
            player.setOp(value);
        } else {
            this.bootstrap.getScheduler().executeSync(() -> player.setOp(value));
        }
    }

    public void saveResource(String resourcePath) {
        resourcePath = resourcePath.replace('\\', '/');
        InputStream in = getClass().getResourceAsStream("/" + resourcePath);

        File outFile = new File(this.bootstrap.getDataFolder(), resourcePath);
        int lastIndex = resourcePath.lastIndexOf('/');
        File outDir = new File(this.bootstrap.getDataFolder(), resourcePath.substring(0, Math.max(lastIndex, 0)));

        if (!outDir.exists()) {
            outDir.mkdirs();
        }

        try {
            if (!outFile.exists()) {
                OutputStream out = new FileOutputStream(outFile);
                byte[] buf = new byte[1024];
                int len;
                while ((len = in.read(buf)) > 0) {
                    out.write(buf, 0, len);
                }
                out.close();
                in.close();
            } else {
                getLogger().warn("Could not save " + outFile.getName() + " to " + outFile + " because " + outFile.getName() + " already exists.");
            }
        } catch (IOException ex) {
            getLogger().severe("Could not save " + outFile.getName() + " to " + outFile + "\n" + ex);
        }
    }

    private File resolveConfig() {
        File configFile = new File(this.bootstrap.getDataFolder(), "config.yml");
        if (!configFile.exists()) {
            this.bootstrap.getDataFolder().mkdirs();
            saveResource("config.yml");
        }
        return configFile;
    }

    @Override
    public Optional<QueryOptions> getQueryOptionsForUser(User user) {
        return this.bootstrap.getPlayer(user.getUniqueId()).map(player -> this.contextManager.getQueryOptions(player));
    }

    @Override
    public Stream<Sender> getOnlineSenders() {
        return Stream.concat(
                Stream.of(getConsoleSender()),
                Arrays.stream(this.bootstrap.getServer().getOnlinePlayers()).map(p -> getSenderFactory().wrap(p))
        );
    }

    @Override
    public Sender getConsoleSender() {
        return getSenderFactory().wrap(this.bootstrap.getConsole());
    }

    public CanyonSenderFactory getSenderFactory() {
        return this.senderFactory;
    }

    @Override
    public AbstractConnectionListener getConnectionListener() {
        return this.connectionListener;
    }

    @Override
    public CanyonCommandExecutor getCommandManager() {
        return this.commandManager;
    }

    @Override
    public StandardUserManager getUserManager() {
        return this.userManager;
    }

    @Override
    public StandardGroupManager getGroupManager() {
        return this.groupManager;
    }

    @Override
    public StandardTrackManager getTrackManager() {
        return this.trackManager;
    }

    @Override
    public CanyonContextManager getContextManager() {
        return this.contextManager;
    }

    public LuckPermsSubscriptionMap getSubscriptionMap() {
        return this.subscriptionMap;
    }

    public void setSubscriptionMap(LuckPermsSubscriptionMap subscriptionMap) {
        this.subscriptionMap = subscriptionMap;
    }

    public LuckPermsPermissionMap getPermissionMap() {
        return this.permissionMap;
    }

    public void setPermissionMap(LuckPermsPermissionMap permissionMap) {
        this.permissionMap = permissionMap;
    }

    public LuckPermsDefaultsMap getDefaultPermissionMap() {
        return this.defaultPermissionMap;
    }

    public void setDefaultPermissionMap(LuckPermsDefaultsMap defaultPermissionMap) {
        this.defaultPermissionMap = defaultPermissionMap;
    }

}
