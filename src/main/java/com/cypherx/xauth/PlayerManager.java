/*
 * xAuth for Bukkit
 * Copyright (C) 2012 Lycano <https://github.com/lycano/xAuth/>
 *
 * Copyright (C) 2011 CypherX <https://github.com/CypherX/xAuth/>
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
package com.cypherx.xauth;

import com.cypherx.xauth.database.Table;
import com.cypherx.xauth.exceptions.xAuthPlayerUnprotectException;
import com.cypherx.xauth.utils.xAuthLog;
import com.cypherx.xauth.utils.xAuthUtils;
import com.cypherx.xauth.xAuthPlayer.Status;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.entity.EntityDamageEvent.DamageCause;

import java.sql.*;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class PlayerManager {
    private final xAuth plugin;
    private final Map<String, xAuthPlayer> players = new HashMap<String, xAuthPlayer>();
    private Map<Integer, String> playerIds = new HashMap<Integer, String>();

    public PlayerManager(final xAuth plugin) {
        this.plugin = plugin;
    }

    public xAuthPlayer getPlayer(Player player) {
        return getPlayer(player.getName(), false);
    }

    public xAuthPlayer getPlayer(Player player, boolean reload) {
        return getPlayer(player.getName(), reload);
    }

    public xAuthPlayer getPlayer(String playerName) {
        return getPlayer(playerName, false);
    }

    private xAuthPlayer getPlayer(String playerName, boolean reload) {
        String lowPlayerName = playerName.toLowerCase();

        if (players.containsKey(lowPlayerName) && !reload) {
            return players.get(lowPlayerName);
        }

        xAuthPlayer player = loadPlayer(playerName);

        if (player == null) {
            player = new xAuthPlayer(playerName);
        }

        players.put(lowPlayerName, player);
        return player;
    }

    private void addPlayerId(int id, String playerName) {
        if (!hasAccountId(id))
            playerIds.put(id, playerName);
    }

    public xAuthPlayer getPlayerById(int id) {
        if (hasAccountId(id))
            return getPlayer(playerIds.get(id));

        return null;
    }

    public List<xAuthPlayer> getPlayers(List<String> playerNames) {
        List<xAuthPlayer> xPlayers = new ArrayList<xAuthPlayer>();
        for (String playerName: playerNames) {
            xPlayers.add(getPlayer(playerName));
        }

        return xPlayers;
    }

    public List<xAuthPlayer> getPlayersByIds(List<Integer> accountIds) {
        List<xAuthPlayer> xPlayers = new ArrayList<xAuthPlayer>();
        for (int accountId: accountIds) {
            xPlayers.add(getPlayerById(accountId));
        }

        return xPlayers;
    }

    public boolean hasAccountId(int id) {
        return playerIds.containsKey(id);
    }

    private xAuthPlayer loadPlayer(String playerName) {
        Connection conn = plugin.getDatabaseController().getConnection();
        PreparedStatement ps = null;
        ResultSet rs = null;

        try {
            String sql = String.format("SELECT `id`,`active` FROM `%s` WHERE `playername` = ?",
                    plugin.getDatabaseController().getTable(Table.ACCOUNT));
            ps = conn.prepareStatement(sql);
            ps.setString(1, playerName);
            rs = ps.executeQuery();
            if (!rs.next())
                return null;

            addPlayerId(rs.getInt("id"), playerName);

            return new xAuthPlayer(playerName, rs.getInt("id"), !rs.getBoolean("active"), Status.Registered);
        } catch (SQLException e) {
            xAuthLog.severe(String.format("Failed to load player: %s", playerName), e);
            return null;
        } finally {
            plugin.getDatabaseController().close(conn, ps, rs);
        }
    }

    public void reload() {
        players.clear();
        playerIds.clear();

        Player[] players = Bukkit.getServer().getOnlinePlayers();
        if (players.length > 0)
            this.handleReload(players);
    }

    public void releasePlayer(String playerName) {
        xAuthPlayer xp = getPlayer(playerName);
        playerIds.remove(xp.getAccountId());
        players.remove(playerName);
    }

    public void handleReload(Player[] players) {
        for (Player p : players) {
            xAuthPlayer xp = getPlayer(p.getName());
            boolean mustLogin = false;

            if (xp.isRegistered()) {
                if (!checkSession(xp)) {
                    mustLogin = true;
                    plugin.getAuthClass(xp).offline(p.getName());
                } else {
                    xp.setStatus(Status.Authenticated);
                    xp.setGameMode(p.getGameMode());
                    plugin.getAuthClass(xp).online(p.getName());
                }
            } else if (mustRegister(p)) {
                mustLogin = true;
                plugin.getAuthClass(xp).offline(p.getName());
            }

            if (mustLogin) {
                protect(xp);
                plugin.getMessageHandler().sendMessage("misc.reloaded", p);
            }
        }
    }

    public boolean mustRegister(Player player) {
        if (plugin.getConfig().getBoolean("authurl.enabled"))
            return plugin.getConfig().getBoolean("authurl.registration");

        return plugin.getConfig().getBoolean("registration.forced") || xAuth.getPermissionManager().has(player, "xauth.register");
    }

    public boolean checkSession(xAuthPlayer player) {
        if (!plugin.getDatabaseController().isTableActive(Table.SESSION))
            return false;

        Connection conn = plugin.getDatabaseController().getConnection();
        PreparedStatement ps = null;
        ResultSet rs = null;

        try {
            String sql = String.format("SELECT `ipaddress`, `logintime` FROM `%s` WHERE `accountid` = ?",
                    plugin.getDatabaseController().getTable(Table.SESSION));
            ps = conn.prepareStatement(sql);
            ps.setInt(1, player.getAccountId());
            rs = ps.executeQuery();
            if (!rs.next())
                return false;

            String ipAddress = rs.getString("ipaddress");
            Timestamp loginTime = rs.getTimestamp("logintime");

            boolean valid = isSessionValid(player, ipAddress, loginTime);
            if (valid)
                return true;

            deleteSession(player.getAccountId());
            return false;
        } catch (SQLException e) {
            xAuthLog.severe(String.format("Failed to load session for account: %d", player.getAccountId()), e);
            return false;
        } finally {
            plugin.getDatabaseController().close(conn, ps, rs);
        }
    }

    private boolean isSessionValid(xAuthPlayer xp, String ipAddress, Timestamp loginTime) {
        if (plugin.getConfig().getBoolean("session.verifyip") && !ipAddress.equals(xp.getIPAddress()))
            return false;

        Timestamp expireTime = new Timestamp(loginTime.getTime() + (plugin.getConfig().getInt("session.length") * 1000));
        return expireTime.compareTo(new Timestamp(System.currentTimeMillis())) > 0;
    }

    public void protect(xAuthPlayer xp) {
        Player p = xp.getPlayer();
        if (p == null)
            return;

        plugin.getPlayerDataHandler().storeData(xp, p);

        // set GameMode for xAuthPlayer when protecting player to whatever it is currently if its unequal to the xAuthPlayer GameMode
        if (!p.getGameMode().equals(xp.getGameMode()))
            xp.setGameMode(p.getGameMode());

        xp.setLastNotifyTime(new Timestamp(System.currentTimeMillis()));

        int timeout = plugin.getConfig().getInt("guest.timeout");
        if (timeout > 0 && xp.isRegistered())
            xp.setTimeoutTaskId(scheduleTimeoutTask(p, timeout));

        xp.setProtected(true);
    }

    private int scheduleTimeoutTask(final Player player, final int timeout) {
        return Bukkit.getServer().getScheduler().scheduleSyncDelayedTask(plugin, new Runnable() {
            public void run() {
                if (player.isOnline())
                    player.kickPlayer(plugin.getMessageHandler().getNode("misc.timeout"));
            }
        }, plugin.getConfig().getInt("guest.timeout") * 20);
    }

    public void unprotect(xAuthPlayer xp) {
        //@TODO redesign
        // order is getPlayer(), restoreData, setCreativeMode when needed, cancelTask, setProtected(false)
        Player p = xp.getPlayer();
        try {
            if (p == null)
                throw new xAuthPlayerUnprotectException("Could not unprotect Player during fetch Player object from xAuthPlayer.");
        } catch (xAuthPlayerUnprotectException e) {
            xAuthLog.severe(e.getMessage());
            return;
        }

        plugin.getPlayerDataHandler().restoreData(xp, p.getName());

        // only set player GameMode when the xAuthPlayer has another state.
        if (!xp.getGameMode().equals(p.getGameMode()))
            p.setGameMode(xp.getGameMode());

        // guest protection cancel task. See @PlayerManager.protect(xAuthPlayer p)
        int timeoutTaskId = xp.getTimeoutTaskId();
        if (timeoutTaskId > -1) {
            Bukkit.getScheduler().cancelTask(timeoutTaskId);
            xp.setTimeoutTaskId(-1);
        }

        xp.setProtected(false);
    }

    public boolean isRestricted(xAuthPlayer player, Event event) {
        if (!player.isProtected())
            return false;

        boolean restrict = true;
        PlayerRestrictionNode playerRestrictionNode = new PlayerRestrictionNode(event);

        if (plugin.getConfig().contains("guest." + playerRestrictionNode.getRestrictionNode())) {
            if (!plugin.getConfig().getBoolean("guest." + playerRestrictionNode.getRestrictionNode()))
                restrict = false;

            if (xAuth.getPermissionManager().has(player.getPlayer(), "xauth." + playerRestrictionNode.getRestrictionNode()))
                restrict = true;
            else if (xAuth.getPermissionManager().has(player.getPlayer(), "xauth." + playerRestrictionNode.getAllowNode()))
                restrict = false;
        }

        return restrict;
    }

    public void sendNotice(xAuthPlayer player) {
        if (canNotify(player)) {
            plugin.getMessageHandler().sendMessage("misc.illegal", player.getPlayer());
            player.setLastNotifyTime(new Timestamp(System.currentTimeMillis()));
        }
    }

    private boolean canNotify(xAuthPlayer player) {
        Timestamp lastNotifyTime = player.getLastNotifyTime();
        if (lastNotifyTime == null)
            return true;

        Timestamp nextNotifyTime = new Timestamp(lastNotifyTime.getTime() + (plugin.getConfig().getInt("guest.notify-cooldown") * 1000));
        return nextNotifyTime.compareTo(new Timestamp(System.currentTimeMillis())) < 0;
    }

    public boolean hasGodmode(xAuthPlayer player, DamageCause cause) {
        int godmodeLength = plugin.getConfig().getInt("session.godmode-length");
        Timestamp loginTime = player.getLoginTime();
        if (godmodeLength < 1 || loginTime == null || cause == DamageCause.FIRE_TICK || cause == DamageCause.DROWNING)
            return false;

        Timestamp expireTime = new Timestamp(loginTime.getTime() + (godmodeLength * 1000));
        return expireTime.compareTo(new Timestamp(System.currentTimeMillis())) > 0;
    }

    public boolean isActive(int id) {
        Connection conn = plugin.getDatabaseController().getConnection();
        PreparedStatement ps = null;
        ResultSet rs = null;

        try {
            String sql = String.format("SELECT `active` FROM `%s` WHERE `id` = ?",
                    plugin.getDatabaseController().getTable(Table.ACCOUNT));
            ps = conn.prepareStatement(sql);
            ps.setInt(1, id);
            rs = ps.executeQuery();
            return rs.next() && rs.getBoolean("active");
        } catch (SQLException e) {
            xAuthLog.severe("Failed to check active status of account: " + id, e);
            return false;
        } finally {
            plugin.getDatabaseController().close(conn, ps, rs);
        }
    }

    public boolean activateAcc(int id) {
        return setActiveState(id, true);
    }

    public boolean lockAcc(int id) {
        return setActiveState(id, false);
    }

    private boolean setActiveState(int id, boolean active) {
        Connection conn = plugin.getDatabaseController().getConnection();
        PreparedStatement ps = null;

        try {
            String sql = String.format("UPDATE `%s` SET `active` = %d WHERE `id` = ?",
                    plugin.getDatabaseController().getTable(Table.ACCOUNT), ((active) ? 1 : 0));
            ps = conn.prepareStatement(sql);
            ps.setInt(1, id);
            ps.executeUpdate();

            getPlayerById(id).setIsLocked(!active);

            return true;
        } catch (SQLException e) {
            xAuthLog.severe("Failed to " + ((active) ? "activate" : "lock") + " account: " + id, e);
            return false;
        } finally {
            plugin.getDatabaseController().close(conn, ps);
        }
    }

    public boolean activateAll() {
        return setAllActiveStates(true, null);
    }

    public boolean lockAll() {
        return setAllActiveStates(false, null);
    }

    public boolean setAllActiveStates(boolean state, Integer[] excludeIds) {
        Connection conn = plugin.getDatabaseController().getConnection();
        PreparedStatement ps = null;

        try {
            String query = "UPDATE `%s` SET `active` = %d";
            if ((excludeIds != null) && (excludeIds.length > 0))
                query = "UPDATE `%s` SET `active` = %d WHERE `id` NOT IN (" + xAuthUtils.join(excludeIds) + ")";

            String sql = String.format(query, plugin.getDatabaseController().getTable(Table.ACCOUNT), ((state) ? 1 : 0));
            ps = conn.prepareStatement(sql);
            ps.executeUpdate();

            // clear cache
            reload();

            return true;
        } catch (SQLException e) {
            xAuthLog.severe("Failed to " + ((state) ? "activate" : "lock") + " accounts", e);
            return false;
        } finally {
            plugin.getDatabaseController().close(conn, ps);
        }
    }

    public Integer countAll() {
        return getActiveStatesCount(false, true);
    }

    public Integer countActive() {
        return getActiveStatesCount(true, false);
    }

    public Integer countLocked() {
        return getActiveStatesCount(false, false);
    }

    private Integer getActiveStatesCount(boolean state, boolean bypassState) {
        Connection conn = plugin.getDatabaseController().getConnection();
        PreparedStatement ps = null;
        ResultSet rs = null;

        try {
            String query = "SELECT COUNT(*) AS `state` FROM `%s` WHERE `active` = %d";
            if (bypassState)
                query = "SELECT COUNT(*) AS `state` FROM `%s`";

            String sql = String.format(query, plugin.getDatabaseController().getTable(Table.ACCOUNT), ((state) ? 1 : 0));
            ps = conn.prepareStatement(sql);
            rs = ps.executeQuery();
            rs.next();

            return rs.getInt("state");
        } catch (SQLException e) {
            xAuthLog.severe("Failed to check " + ((state) ? "active" : "lock") + " state", e);
            return null;
        } finally {
            plugin.getDatabaseController().close(conn, ps);
        }
    }

    public boolean doLogin(xAuthPlayer xp) {
        int accountId = xp.getAccountId();
        String ipAddress = xp.getIPAddress();
        Timestamp currentTime = new Timestamp(System.currentTimeMillis());

        try {
            // create account if one does not exist (for AuthURL only)
            if (plugin.getConfig().getBoolean("authurl.enabled") && accountId < 1) {
                accountId = createAccount(xp.getPlayerName(), "authURL", null, ipAddress);
                xp.setAccountId(accountId);
                xp.setStatus(Status.Registered);
            }

            if (plugin.getConfig().getBoolean("account.track-last-login"))
                updateLastLogin(accountId, ipAddress, currentTime);

            // insert session if session.length > 0
            if (plugin.getDatabaseController().isTableActive(Table.SESSION))
                createSession(accountId, ipAddress);

            // clear strikes
            plugin.getStrikeManager().getRecord(ipAddress).clearStrikes(xp.getPlayerName());

            unprotect(xp);
            xp.setLoginTime(currentTime);
            xp.setStatus(Status.Authenticated);
            return true;
        } catch (SQLException e) {
            xAuthLog.severe("Something went wrong while logging in player: " + xp.getPlayerName(), e);
            return false;
        }
    }

    public int createAccount(String user, String pass, String email, String ipaddress) throws SQLException {
        Connection conn = plugin.getDatabaseController().getConnection();
        PreparedStatement ps = null;
        ResultSet rs = null;
        int id = -1;

        try {
            String sql = String.format("INSERT INTO `%s` (`playername`, `password`, `email`, `registerdate`, `registerip`) VALUES (?, ?, ?, ?, ?)",
                    plugin.getDatabaseController().getTable(Table.ACCOUNT));
            ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, user);
            ps.setString(2, plugin.getPasswordHandler().hash(pass));
            ps.setString(3, email);
            ps.setTimestamp(4, new Timestamp(System.currentTimeMillis()));
            ps.setString(5, ipaddress);
            ps.executeUpdate();
            rs = ps.getGeneratedKeys();

            // set lastId
            id = rs.next() ? rs.getInt(1) : -1;

            // add the user to id/player keyring
            playerIds.put(id, user);

            // activate user if registration.activation is set to false in config
            if ((id > 0) && (!plugin.getConfig().getBoolean("registration.activation"))) {
                activateAcc(id);
            }

            return id;
        } finally {
            plugin.getDatabaseController().close(conn, ps, rs);
        }
    }

    public boolean updateLastLogin(int accountId, String ipAddress, Timestamp currentTime) throws SQLException {
        Connection conn = plugin.getDatabaseController().getConnection();
        PreparedStatement ps = null;

        try {
            String sql = String.format("UPDATE `%s` SET `lastlogindate` = ?, `lastloginip` = ? WHERE `id` = ?",
                    plugin.getDatabaseController().getTable(Table.ACCOUNT));
            ps = conn.prepareStatement(sql);
            ps.setTimestamp(1, currentTime);
            ps.setString(2, ipAddress);
            ps.setInt(3, accountId);
            ps.executeUpdate();
            return true;
        } finally {
            plugin.getDatabaseController().close(conn, ps);
        }
    }

    public boolean deleteAccount(int accountId) {
        Connection conn = plugin.getDatabaseController().getConnection();
        PreparedStatement ps = null;

        try {
            String sql = String.format("DELETE FROM `%s` WHERE `id` = ?",
                    plugin.getDatabaseController().getTable(Table.ACCOUNT));
            ps = conn.prepareStatement(sql);
            ps.setInt(1, accountId);
            ps.executeUpdate();
            return true;
        } catch (SQLException e) {
            xAuthLog.severe("Something went wrong while deleting account: " + accountId, e);
            return false;
        } finally {
            plugin.getDatabaseController().close(conn, ps);
        }
    }

    public void initAccount(int accountId) {
        if (players.remove(playerIds.get(accountId)) != null)
            playerIds.remove(accountId);
    }

    public boolean createSession(int accountId, String ipAddress) throws SQLException {
        Connection conn = plugin.getDatabaseController().getConnection();
        PreparedStatement ps = null;

        try {
            String sql = String.format("INSERT INTO `%s` VALUES (?, ?, ?)",
                    plugin.getDatabaseController().getTable(Table.SESSION));
            ps = conn.prepareStatement(sql);
            ps.setInt(1, accountId);
            ps.setString(2, ipAddress);
            ps.setTimestamp(3, new Timestamp(System.currentTimeMillis()));
            ps.executeUpdate();
            return true;
        } finally {
            plugin.getDatabaseController().close(conn, ps);
        }
    }

    public boolean deleteSession(int accountId) {
        if (!plugin.getDatabaseController().isTableActive(Table.SESSION))
            return true;

        Connection conn = plugin.getDatabaseController().getConnection();
        PreparedStatement ps = null;

        try {
            String sql = String.format("DELETE FROM `%s` WHERE `accountid` = ?",
                    plugin.getDatabaseController().getTable(Table.SESSION));
            ps = conn.prepareStatement(sql);
            ps.setInt(1, accountId);
            ps.executeUpdate();
            return true;
        } catch (SQLException e) {
            xAuthLog.severe("Something went wrong while deleting session for account: " + accountId, e);
            return false;
        } finally {
            plugin.getDatabaseController().close(conn, ps);
        }
    }
}