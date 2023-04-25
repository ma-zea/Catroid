/*
 * Catroid: An on-device visual programming system for Android devices
 * Copyright (C) 2010-2022 The Catrobat Team
 * (<http://developer.catrobat.org/credits>)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * An additional term exception under section 7 of the GNU Affero
 * General Public License, version 3, is available at
 * http://developer.catrobat.org/license_additional_term
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.catrobat.catroid.test.physics;

import org.catrobat.catroid.physics.PhysicsDebugSettings;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

import static junit.framework.Assert.assertFalse;

@RunWith(JUnit4.class)
public class PhysicsDebugSettingsTest {

	/*
	 * Tests if all physics debug settings are configured correctly for the release.
	 * Therefore there is no problem if it fails during programming or debugging.
	 */
	@Test
	public void testDefaultSettingsForRelease() {
		assertFalse(PhysicsDebugSettings.Render.RENDER_COLLISION_FRAMES);
		assertFalse(PhysicsDebugSettings.Render.RENDER_BODIES);
		assertFalse(PhysicsDebugSettings.Render.RENDER_JOINTS);
		assertFalse(PhysicsDebugSettings.Render.RENDER_AABB);
		assertFalse(PhysicsDebugSettings.Render.RENDER_INACTIVE_BODIES);
		assertFalse(PhysicsDebugSettings.Render.RENDER_VELOCITIES);
		assertFalse(PhysicsDebugSettings.Render.RENDER_PHYSIC_OBJECT_LABELING);
	}
}
