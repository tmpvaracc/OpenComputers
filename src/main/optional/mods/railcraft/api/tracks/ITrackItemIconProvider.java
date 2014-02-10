/*
 * Copyright (c) CovertJaguar, 2011 http://railcraft.info
 * 
 * This code is the property of CovertJaguar
 * and may only be used with explicit written
 * permission unless otherwise specified on the
 * license page at railcraft.wikispaces.com.
 */
package mods.railcraft.api.tracks;

import net.minecraft.util.Icon;

/**
 *
 * @author CovertJaguar <http://www.ModTMechworks.info>
 */
public interface ITrackItemIconProvider {

    Icon getTrackItemIcon(TrackSpec spec);
}
