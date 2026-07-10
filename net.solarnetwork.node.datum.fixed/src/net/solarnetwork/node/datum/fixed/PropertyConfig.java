/* ==================================================================
 * PropertyConfig.java - 10/07/2026 3:14:00 pm
 *
 * Copyright 2026 SolarNetwork.net Dev Team
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License as
 * published by the Free Software Foundation; either version 2 of
 * the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA
 * 02111-1307 USA
 * ==================================================================
 */

package net.solarnetwork.node.datum.fixed;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import net.solarnetwork.domain.datum.DatumSamplePropertyConfig;
import net.solarnetwork.domain.datum.DatumSamplesType;
import net.solarnetwork.settings.SettingSpecifier;
import net.solarnetwork.settings.support.BasicMultiValueSettingSpecifier;
import net.solarnetwork.settings.support.BasicTextFieldSettingSpecifier;
import net.solarnetwork.util.NumberUtils;

/**
 * A datum property configuration.
 *
 * @author matt
 * @version 1.0
 */
public class PropertyConfig extends DatumSamplePropertyConfig<String> {

	/**
	 * Constructor.
	 */
	public PropertyConfig() {
		super();
	}

	/**
	 * Constructor.
	 *
	 * @param propertyKey
	 *        the sample property key to use
	 * @param propertyType
	 *        the sample property type to use
	 * @param config
	 *        the configuration to use
	 */
	public PropertyConfig(@Nullable String propertyKey, @Nullable DatumSamplesType propertyType,
			@Nullable String config) {
		super(propertyKey, propertyType, config);
	}

	/**
	 * Get settings suitable for configuring an instance of this class.
	 *
	 * @param prefix
	 *        a setting key prefix to use
	 * @return the settings, never {@code null}
	 */
	public static List<SettingSpecifier> settings(String prefix) {
		final List<SettingSpecifier> result = new ArrayList<>(3);

		result.add(new BasicTextFieldSettingSpecifier(prefix + "propertyKey", null));

		// drop-down menu for datumPropertyType
		BasicMultiValueSettingSpecifier propTypeSpec = new BasicMultiValueSettingSpecifier(
				prefix + "propertyTypeKey", "");
		Map<String, String> propTypeTitles = new LinkedHashMap<>(3);
		propTypeTitles.put("", "");
		for ( DatumSamplesType e : DatumSamplesType.values() ) {
			propTypeTitles.put(Character.toString(e.toKey()), e.toString());
		}
		propTypeSpec.setValueTitles(propTypeTitles);
		result.add(propTypeSpec);

		result.add(new BasicTextFieldSettingSpecifier(prefix + "config", null));

		return result;
	}

	/**
	 * Get the property value.
	 *
	 * <p>
	 * This will try to coerce the string value to a number for
	 * {@code Accumulating} and {@code Instantaneous} property types. Empty
	 * string values will be coersed to {@code null}.
	 * </p>
	 *
	 * @return the value
	 */
	public @Nullable Object value() {
		final DatumSamplesType propType = getPropertyType();
		final String val = getConfig();
		if ( propType == null || val == null ) {
			return val;
		}
		return switch (propType) {
			case Instantaneous, Accumulating -> NumberUtils.parseNumber(val.toString());
			default -> !val.isEmpty() ? val : null;
		};
	}

}
