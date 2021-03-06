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
package de.luricos.bukkit.xAuth.command.tabcomplete.admin;

import de.luricos.bukkit.xAuth.command.tabcomplete.xAuthCustomCommandTabCompletion;
import de.luricos.bukkit.xAuth.command.xAuthPlayerCountType;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;

import java.util.ArrayList;
import java.util.List;

/**
 * @author lycano
 */
public class AdminCountCommandTabComplete extends xAuthCustomCommandTabCompletion {

    public AdminCountCommandTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        super(sender, command, alias, args);

        List<String> customArguments = new ArrayList<String>();
        for (xAuthPlayerCountType type: xAuthPlayerCountType.values()) {
            customArguments.add(type.getName());
        }

        this.setArguments(customArguments);
    }
}
