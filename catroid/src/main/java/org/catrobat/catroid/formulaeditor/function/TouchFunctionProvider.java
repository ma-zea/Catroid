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

package org.catrobat.catroid.formulaeditor.function;

import org.catrobat.catroid.formulaeditor.Functions;
import org.catrobat.catroid.utils.TouchUtil;

import java.util.Map;

public class TouchFunctionProvider implements FunctionProvider {
	@Override
	public void addFunctionsToMap(Map<Functions, FormulaFunction> formulaFunctions) {
		formulaFunctions.put(Functions.MULTI_FINGER_TOUCHED, new UnaryFunction(this::interpretFunctionFingerTouched));
		formulaFunctions.put(Functions.MULTI_FINGER_X, new UnaryFunction(this::interpretFunctionMultiFingerX));
		formulaFunctions.put(Functions.MULTI_FINGER_Y, new UnaryFunction(this::interpretFunctionMultiFingerY));
		formulaFunctions.put(Functions.INDEX_CURRENT_TOUCH, new UnaryFunction(this::interpretFunctionIndexCurrentTouch));
	}

	private double interpretFunctionMultiFingerY(double argument) {
		return TouchUtil.getY((int) argument);
	}

	private double interpretFunctionMultiFingerX(double argument) {
		return TouchUtil.getX((int) argument);
	}

	private double interpretFunctionFingerTouched(double argument) {
		return TouchUtil.isFingerTouching((int) argument) ? 1d : 0d;
	}

	private double interpretFunctionIndexCurrentTouch(double argument) {
		return TouchUtil.getIndexOfCurrentTouch((int) argument);
	}
}
