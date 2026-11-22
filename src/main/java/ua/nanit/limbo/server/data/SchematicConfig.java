/*
 * Copyright (C) 2020 Nan1t
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
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package ua.nanit.limbo.server.data;

import lombok.Data;

@Data
public class SchematicConfig {

    private boolean enabled;
    private String file = "spawn.schem";

    private int offsetX = 0;
    private int offsetY = 0;
    private int offsetZ = 0;

    private double spawnX = 20.5;
    private double spawnY = 17.0;
    private double spawnZ = 22.5;
    private float spawnYaw = -90.0F;
    private float spawnPitch = 0.0F;

    private int viewDistance = 6;
    private boolean allowFlight = false;
}
