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

package me.lucko.luckperms.canyon.context;

import me.lucko.luckperms.canyon.LPCanyonPlugin;
import me.lucko.luckperms.common.config.ConfigKeys;

import net.luckperms.api.context.ContextCalculator;
import net.luckperms.api.context.ContextConsumer;
import net.luckperms.api.context.ContextSet;
import net.luckperms.api.context.DefaultContextKeys;
import net.luckperms.api.context.ImmutableContextSet;

import org.bukkit.World;
import org.bukkit.entity.Player;
import org.checkerframework.checker.nullness.qual.NonNull;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class WorldCalculator implements ContextCalculator<Player> {
    private final LPCanyonPlugin plugin;

    public WorldCalculator(LPCanyonPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public void calculate(@NonNull Player subject, @NonNull ContextConsumer consumer) {
        Set<String> seen = new HashSet<>();
        String world = subject.getWorld().getName().toLowerCase();
        while (seen.add(world)) {
            consumer.accept(DefaultContextKeys.WORLD_KEY, world);
            world = this.plugin.getConfiguration().get(ConfigKeys.WORLD_REWRITES).getOrDefault(world, world).toLowerCase();
        }
    }

    @Override
    public ContextSet estimatePotentialContexts() {
        List<World> worlds = this.plugin.getBootstrap().getServer().getWorlds();
        ImmutableContextSet.Builder builder = ImmutableContextSet.builder();
        for (World world : worlds) {
            String name = world.getName().toLowerCase();
            if (!name.trim().isEmpty()) {
                builder.add(DefaultContextKeys.WORLD_KEY, name);
            }
        }
        return builder.build();
    }
}
