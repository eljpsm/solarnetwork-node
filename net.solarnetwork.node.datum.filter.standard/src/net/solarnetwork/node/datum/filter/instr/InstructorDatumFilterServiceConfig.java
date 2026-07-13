/* ==================================================================
 * InstructorDatumFilterServiceConfig.java - 13/07/2026 7:18:38 am
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

package net.solarnetwork.node.datum.filter.instr;

import static net.solarnetwork.node.settings.SettingValueBean.addSetting;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import net.solarnetwork.node.domain.Setting;
import net.solarnetwork.node.settings.SettingValueBean;

/**
 * Configuration for a {@link InstructorDatumFilterService}.
 *
 * @author matt
 * @version 1.0
 */
public class InstructorDatumFilterServiceConfig {

	private @Nullable String key;
	private @Nullable String serviceName;
	private @Nullable String serviceGroup;
	private @Nullable String sourceId;
	private @Nullable String mode;
	private @Nullable String tag;

	private final Map<String, String> meta = new LinkedHashMap<>(2);
	private final List<InstructorConfig> instructorConfigs = new ArrayList<>(2);

	/**
	 * Constructor.
	 */
	public InstructorDatumFilterServiceConfig() {
		super();
	}

	/**
	 * Generate a list of setting values from this instance.
	 *
	 * @param providerId
	 *        the setting provider key to use
	 * @return the list of setting values, never {@code null}
	 */
	public List<SettingValueBean> toSettingValues(String providerId) {
		List<SettingValueBean> settings = new ArrayList<>(16);
		addSetting(settings, providerId, key, "uid", serviceName);
		addSetting(settings, providerId, key, "groupUid", serviceGroup);
		addSetting(settings, providerId, key, "sourceId", sourceId);
		addSetting(settings, providerId, key, "mode", mode);
		addSetting(settings, providerId, key, "tag", tag);

		int i = 0;
		for ( InstructorConfig instructorConfig : instructorConfigs ) {
			settings.addAll(instructorConfig.toSettingValues(providerId, key, i++));
		}
		return settings;
	}

	/**
	 * Populate a setting as a configuration value, if possible.
	 *
	 * @param setting
	 *        the setting to try to handle
	 * @return {@code true} if the setting was handled as a configuration value
	 */
	public boolean populateFromSetting(Setting setting) {
		if ( InstructorConfig.populateFromSetting(this, setting) ) {
			return true;
		}
		String type = setting.getType();
		String val = setting.getValue();
		if ( val != null && !val.isEmpty() ) {
			switch (type) {
				case "uid":
					setServiceName(val);
					break;
				case "groupUid":
					setServiceGroup(val);
					break;
				case "sourceId":
					setSourceId(val);
					break;
				case "mode":
					setMode(val);
					break;
				case "tag":
					setTag(val);
					break;
				default:
					meta.put(type, val);
					break;
			}
			return true;
		}
		return false;
	}

	@Override
	public String toString() {
		StringBuilder builder = new StringBuilder();
		builder.append("InstructorDatumFilterServiceConfig{");
		if ( key != null ) {
			builder.append("key=");
			builder.append(key);
			builder.append(", ");
		}
		if ( serviceName != null ) {
			builder.append("serviceName=");
			builder.append(serviceName);
			builder.append(", ");
		}
		if ( serviceGroup != null ) {
			builder.append("serviceGroup=");
			builder.append(serviceGroup);
			builder.append(", ");
		}
		if ( sourceId != null ) {
			builder.append("sourceId=");
			builder.append(sourceId);
			builder.append(", ");
		}
		if ( mode != null ) {
			builder.append("mode=");
			builder.append(mode);
			builder.append(", ");
		}
		if ( tag != null ) {
			builder.append("tag=");
			builder.append(tag);
			builder.append(", ");
		}
		if ( instructorConfigs != null ) {
			builder.append("instructorConfigs=");
			builder.append(instructorConfigs);
		}
		builder.append("}");
		return builder.toString();
	}

	/**
	 * Get the key.
	 *
	 * @return the key
	 */
	public final @Nullable String getKey() {
		return key;
	}

	/**
	 * Set the key.
	 *
	 * @param key
	 *        the key to set
	 */
	public final void setKey(@Nullable String key) {
		this.key = key;
	}

	/**
	 * Get the service name.
	 *
	 * @return the service name
	 */
	public final @Nullable String getServiceName() {
		return serviceName;
	}

	/**
	 * Set the service name.
	 *
	 * @param serviceName
	 *        the service name to set
	 */
	public final void setServiceName(@Nullable String serviceName) {
		this.serviceName = serviceName;
	}

	/**
	 * Get the service group.
	 *
	 * @return the service group
	 */
	public final @Nullable String getServiceGroup() {
		return serviceGroup;
	}

	/**
	 * Set the service group.
	 *
	 * @param serviceGroup
	 *        the service group to set
	 */
	public final void setServiceGroup(@Nullable String serviceGroup) {
		this.serviceGroup = serviceGroup;
	}

	/**
	 * Get the source ID condition.
	 *
	 * @return the source ID
	 */
	public final @Nullable String getSourceId() {
		return sourceId;
	}

	/**
	 * Set the source ID condition.
	 *
	 * @param sourceId
	 *        the source ID to set
	 */
	public final void setSourceId(@Nullable String sourceId) {
		this.sourceId = sourceId;
	}

	/**
	 * Get the mode condition.
	 *
	 * @return the mode
	 */
	public final @Nullable String getMode() {
		return mode;
	}

	/**
	 * Set the mode condition.
	 *
	 * @param mode
	 *        the mode to set
	 */
	public final void setMode(@Nullable String mode) {
		this.mode = mode;
	}

	/**
	 * Get the tag condition.
	 *
	 * @return the tag
	 */
	public final @Nullable String getTag() {
		return tag;
	}

	/**
	 * Set the tag condition.
	 *
	 * @param tag
	 *        the tag to set
	 */
	public final void setTag(@Nullable String tag) {
		this.tag = tag;
	}

	/**
	 * Get the metadata.
	 *
	 * @return the metadata
	 */
	public final Map<String, String> getMeta() {
		return meta;
	}

	/**
	 * Get the instructor configurations.
	 *
	 * @return the instructor configurations
	 */
	public final List<InstructorConfig> getInstructorConfigs() {
		return instructorConfigs;
	}

}
