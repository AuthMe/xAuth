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
package de.luricos.bukkit.xAuth.command.admin;

import de.luricos.bukkit.xAuth.command.xAuthAdminCommand;
import de.luricos.bukkit.xAuth.event.command.admin.xAuthCommandAdminVersionEvent;
import de.luricos.bukkit.xAuth.event.xAuthEventProperties;
import de.luricos.bukkit.xAuth.xAuth;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;

/**
 * @author lycano
 */
public class AdminVersionCommand extends xAuthAdminCommand {

    public AdminVersionCommand() {

    }

    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(this.isAllowedCommand(sender, "admin.permission", "xauth.version"))) {
            return true;
        }

        xAuthEventProperties properties = new xAuthEventProperties();
        properties.setProperty("issuedby", sender.getName());
        properties.setProperty("version", xAuth.getPlugin().getDescription().getVersion());
        properties.setProperty("action", xAuthCommandAdminVersionEvent.Action.SUCCESS_SHOW_VERSION);
        this.callEvent(new xAuthCommandAdminVersionEvent(properties));

        this.getMessageHandler().sendMessage(String.format(this.getMessageHandler().getNode("version.current-version"), xAuth.getPlugin().getDescription().getVersion()), sender);
        return true;
    }

}
