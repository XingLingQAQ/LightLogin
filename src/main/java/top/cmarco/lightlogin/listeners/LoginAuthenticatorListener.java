/*
 * LightLogin - Optimised and Safe SpigotMC Software for Authentication
 *     Copyright © 2024  CMarco
 *
 *     This program is free software: you can redistribute it and/or modify
 *     it under the terms of the GNU General Public License as published by
 *     the Free Software Foundation, either version 3 of the License, or
 *     (at your option) any later version.
 *
 *     This program is distributed in the hope that it will be useful,
 *     but WITHOUT ANY WARRANTY; without even the implied warranty of
 *     MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *     GNU General Public License for more details.
 *
 *     You should have received a copy of the GNU General Public License
 *     along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package top.cmarco.lightlogin.listeners;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.jetbrains.annotations.NotNull;
import top.cmarco.lightlogin.LightLoginPlugin;
import top.cmarco.lightlogin.api.AuthenticationCause;
import top.cmarco.lightlogin.api.PlayerAuthenticateEvent;
import top.cmarco.lightlogin.api.PlayerUnauthenticateEvent;
import top.cmarco.lightlogin.command.LightLoginCommand;
import top.cmarco.lightlogin.command.verify.VerificationManager;
import top.cmarco.lightlogin.configuration.LightConfiguration;
import top.cmarco.lightlogin.data.AuthenticationManager;
import top.cmarco.lightlogin.data.AutoKickManager;
import top.cmarco.lightlogin.data.StartupLoginsManager;
import top.cmarco.lightlogin.data.VoidLoginManager;
import top.cmarco.lightlogin.database.LightLoginColumn;
import top.cmarco.lightlogin.database.PluginDatabase;
import top.cmarco.lightlogin.network.NetworkUtilities;

import java.net.InetAddress;
import java.util.Objects;

import static top.cmarco.lightlogin.api.LoginUtils.whenOnline;
import static top.cmarco.lightlogin.api.LoginUtils.whenOnlineOrElse;

public final class LoginAuthenticatorListener extends NamedListener {

    private final LightLoginPlugin plugin;
    private final StartupLoginsManager startupLoginsManager;
    private final AuthenticationManager authManager;
    private final PluginDatabase database;
    private final LightConfiguration configuration;

    public LoginAuthenticatorListener(@NotNull final LightLoginPlugin plugin) {
        super("login_authenticator_listener");
        this.plugin = plugin;
        this.startupLoginsManager = plugin.getStartupLoginsManager();
        this.authManager = this.plugin.getAuthenticationManager();
        this.database = this.plugin.getDatabase();
        this.configuration = this.plugin.getLightConfiguration();
    }

    private void teleportLoginLoc(Player player) {
        World targetWorld;
        String loginWorldName = this.configuration.getLoginTeleportWorld();
        for (World world : plugin.getServer().getWorlds()) {
            if (world == null || !world.getName().equals(loginWorldName)) continue;
            player.teleport(new Location(world, this.configuration.getLoginTeleportX(), this.configuration.getLoginTeleportY(), this.configuration.getLoginTeleportZ()));
            break;
        }
    }

    private void teleportPostLoginLoc(Player player, Location lastLoginLocation) {
        World targetWorld;
        String loginWorldName = this.configuration.getPostLoginTeleportWorld();

        if (loginWorldName.equalsIgnoreCase("latest")) {
            player.teleport(lastLoginLocation);
            return;
        }

        for (World world : plugin.getServer().getWorlds()) {
            if (world == null || !world.getName().equals(loginWorldName)) continue;
            player.teleport(new Location(world, this.configuration.getPostLoginTeleportX(), this.configuration.getPostLoginTeleportY(), this.configuration.getPostLoginTeleportZ()));
            break;
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void preJoin(final AsyncPlayerPreLoginEvent event) {
        final InetAddress inetAddress = event.getAddress();
        int inetMatchResult = 0x00;
        for (final Player player : this.plugin.getServer().getOnlinePlayers()) {
            final InetAddress tempAddr = Objects.requireNonNull(player.getAddress(), "Address was null!").getAddress();
            if (tempAddr.equals(inetAddress)) {
                ++inetMatchResult;
            }
        }

        final LightConfiguration lightConfiguration = this.plugin.getLightConfiguration();

        if (inetMatchResult < lightConfiguration.getPlayersSameIp()) {
            VerificationManager verificationManager = plugin.getVerificationManager();
            verificationManager.addData(event.getUniqueId());
            return;
        }

        event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER,
                LightLoginCommand.colorAndReplace(lightConfiguration.getMessagePlayersSameIp(), plugin));
    }

    @EventHandler(priority = EventPriority.LOW)
    public void onJoin(final PlayerJoinEvent event) {
        final AutoKickManager autoKickManager = this.plugin.getAutoKickManager();
        final VoidLoginManager voidLoginManager = this.plugin.getVoidLoginManager();
        final Player player = event.getPlayer();

        if (voidLoginManager != null) {
            voidLoginManager.sendLoginToVoid(player);
            // return;
        }

        final Location originalLocation = player.getLocation();

        autoKickManager.addEntered(player);

        plugin.getServer().getOnlinePlayers().stream()
                .filter(p -> !p.equals(player))
                .forEach(p -> p.hidePlayer(plugin, player));

        final boolean requireLogin = !startupLoginsManager.contains(player);

        this.database.searchRowFromPK(player.getUniqueId().toString())
                .handle((row, throwable) -> {
                    if (throwable != null) {
                        this.plugin.getLogger().warning(throwable.getLocalizedMessage());
                        return null;
                    }
                    return row;
                })
                .thenAcceptAsync(row -> {
                    if (row == null) {
                        whenOnline(player, p -> {
                            authManager.addUnregistered(p);
                            runSync(plugin, () -> {
                                giveBlindness(p, plugin);
                                if (configuration.isLoginTeleportEnabled()) {
                                    teleportLoginLoc(player);
                                }
                            });
                        });
                        return;
                    }

                    whenOnlineOrElse(player, p -> {
                        if (!player.hasPermission("lightlogin.autologin")) {
                            authManager.addUnloginned(player);
                            runSync(plugin, () -> {
                                giveBlindness(player, plugin);
                                if (configuration.isLoginTeleportEnabled()) {
                                    teleportLoginLoc(player);
                                }
                            });
                            return;
                        }

                        final long lastLogin = row.getLastLogin();
                        final long lastIpv4 = row.getLastIpv4();
                        final long currentIpv4 = NetworkUtilities.convertInetSocketAddressToLong(player.getAddress());

                        if (requireLogin || currentIpv4 != lastIpv4) {

                            plugin.getServer().getScheduler().runTask(plugin, () -> {
                                PlayerUnauthenticateEvent playerUnauthenticateEvent = new PlayerUnauthenticateEvent(player, AuthenticationCause.AUTOMATIC);
                                this.plugin.getServer().getPluginManager().callEvent(playerUnauthenticateEvent);
                            });

                            authManager.unauthenticate(player);
                            authManager.addUnloginned(player);
                            runSync(plugin, () -> {
                                giveBlindness(player, plugin);
                                if (configuration.isLoginTeleportEnabled()) {
                                    teleportLoginLoc(player);
                                }
                            });
                            return;
                        }

                        final long timeNow = System.currentTimeMillis();

                        if ((timeNow - lastLogin) / 1E3 <= this.configuration.getSessionExpire()) {

                            plugin.getServer().getScheduler().runTask(plugin, () -> {
                                PlayerAuthenticateEvent playerAuthenticateEvent = new PlayerAuthenticateEvent(player, AuthenticationCause.AUTOMATIC);
                                this.plugin.getServer().getPluginManager().callEvent(playerAuthenticateEvent);

                                if (configuration.isLoginTeleportEnabled())
                                    teleportPostLoginLoc(player, originalLocation);

                            });

                            this.authManager.authenticate(player);
                            LightLoginCommand.sendColorPrefixMessages(player, this.configuration.getLoginAuto(), this.plugin);

                        } else {
                            authManager.addUnloginned(player);
                            runSync(plugin, () -> {
                                giveBlindness(player, plugin);
                                if (configuration.isLoginTeleportEnabled()) {
                                    teleportLoginLoc(player);
                                }
                            });
                        }

                    }, authManager::addUnloginned);
                })
                .exceptionally(throwable -> {
                    this.plugin.getLogger().warning(throwable.getLocalizedMessage());
                    return null;
                });
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onLeave(final PlayerQuitEvent event) {
        final Player player = event.getPlayer();

        if (!authManager.isAuthenticated(player)) {
            return;
        }

        this.database.updateRow(player.getUniqueId().toString(), LightLoginColumn.LAST_LOGIN, System.currentTimeMillis());
        this.database.updateRow(player.getUniqueId().toString(), LightLoginColumn.LAST_IPV4, NetworkUtilities.convertInetSocketAddressToLong(player.getAddress()));

        PlayerUnauthenticateEvent playerAuthenticateEvent = new PlayerUnauthenticateEvent(player, AuthenticationCause.AUTOMATIC);
        this.plugin.getServer().getPluginManager().callEvent(playerAuthenticateEvent);

        authManager.unauthenticate(player);
    }
}
