/* ==================================================================
 * InstructorConfig.java - 9/07/2026 12:06:48 pm
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

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import org.jspecify.annotations.Nullable;
import net.solarnetwork.node.service.support.ExpressionConfig;
import net.solarnetwork.service.ExpressionService;
import net.solarnetwork.service.support.ExpressionConfiguration;
import net.solarnetwork.settings.KeyedSettingSpecifier;
import net.solarnetwork.settings.SettingSpecifier;
import net.solarnetwork.settings.support.BasicGroupSettingSpecifier;
import net.solarnetwork.settings.support.BasicTextFieldSettingSpecifier;
import net.solarnetwork.settings.support.SettingUtils;
import net.solarnetwork.util.ArrayUtils;

/**
 * Configuration for a conditional instruction.
 *
 * @author matt
 * @version 1.0
 * @since 4.5
 */
public class InstructorConfig {

	private final ExpressionConfiguration precicate;
	private @Nullable String topic;
	private ExpressionConfig @Nullable [] parameters;
	private ExpressionConfig @Nullable [] responses;

	/**
	 * Constructor.
	 */
	public InstructorConfig() {
		super();
		this.precicate = new ExpressionConfiguration();
	}

	/**
	 * Create a "template" instance for settings.
	 *
	 * @return the template instance
	 */
	public static InstructorConfig template() {
		var result = new InstructorConfig();
		result.parameters = new ExpressionConfig[] { new ExpressionConfig() };
		result.responses = new ExpressionConfig[] { new ExpressionConfig() };
		return result;
	}

	/**
	 * Test if this configuration is valid for evaluation.
	 *
	 * @param expressionServices
	 *        the available expression services
	 * @return {@code true} if an instruction topic and a predicate expression
	 *         are available
	 */
	public boolean isValid(@Nullable Iterable<ExpressionService> expressionServices) {
		return (topic != null && !topic.isEmpty() && precicate.expression(expressionServices) != null);
	}

	/**
	 * Get settings suitable for configuring an instance of this class.
	 *
	 * @param template
	 *        {@code true} if template mode is desired
	 * @param prefix
	 *        a setting key prefix to use
	 * @param expressionServices
	 *        the available expression services
	 * @return the settings, never {@literal null}
	 */
	public List<SettingSpecifier> settings(final boolean template, final String prefix,
			final Iterable<ExpressionService> expressionServices) {
		List<SettingSpecifier> result = new ArrayList<>(8);

		result.addAll(ExpressionConfiguration.settings(InstructorConfig.class, prefix + "predicate.",
				expressionServices));

		result.add(new BasicTextFieldSettingSpecifier(prefix + "topic", null));

		// parameters list
		final ExpressionConfig[] params = getParameters();
		final List<ExpressionConfig> paramsList = (template ? List.of(new ExpressionConfig())
				: (params != null ? List.of(params) : List.of()));
		result.add(SettingUtils.dynamicListSettingSpecifier(prefix + "parameters", paramsList,
				new SettingUtils.KeyedListCallback<>() {

					@Override
					public Collection<SettingSpecifier> mapListSettingKey(
							@Nullable ExpressionConfig value, int index, String key) {
						// remove the Property Type setting from the expression config, which does not apply for
						// these instruction parameter expressions
						List<SettingSpecifier> exprSettings = ExpressionConfig
								.settings(InstructorConfig.class, key + ".", expressionServices).stream()
								.filter(s -> !(s instanceof KeyedSettingSpecifier<?> ks
										&& ks.getKey().endsWith(".datumPropertyTypeKey")))
								.toList();
						return List.of(new BasicGroupSettingSpecifier(exprSettings));
					}
				}));

		// responses list
		final ExpressionConfig[] resps = getResponses();
		final List<ExpressionConfig> respsList = (template ? List.of(new ExpressionConfig())
				: (resps != null ? List.of(resps) : List.of()));
		result.add(SettingUtils.dynamicListSettingSpecifier(prefix + "responses", respsList,
				new SettingUtils.KeyedListCallback<>() {

					@Override
					public Collection<SettingSpecifier> mapListSettingKey(
							@Nullable ExpressionConfig value, int index, String key) {
						SettingSpecifier configGroup = new BasicGroupSettingSpecifier(ExpressionConfig
								.settings(InstructorConfig.class, key + ".", expressionServices));
						return List.of(configGroup);
					}
				}));

		return result;
	}

	/**
	 * Get the predicate configuration.
	 *
	 * @return the predicate configuration
	 */
	public final ExpressionConfiguration getPrecicate() {
		return precicate;
	}

	/**
	 * Get the instruction topic.
	 *
	 * @return the topic
	 */
	public final @Nullable String getTopic() {
		return topic;
	}

	/**
	 * Set the instruction topic.
	 *
	 * @param topic
	 *        the topic to set
	 */
	public final void setTopic(@Nullable String topic) {
		this.topic = topic;
	}

	/**
	 * Get the instruction parameters.
	 *
	 * @return the parameters
	 */
	public final ExpressionConfig @Nullable [] getParameters() {
		return parameters;
	}

	/**
	 * Get the number of configured {@code parameters} elements.
	 *
	 * @return the number of {@code parameters} elements
	 */
	public final int getParametersCount() {
		final ExpressionConfig[] confs = this.parameters;
		return (confs == null ? 0 : confs.length);
	}

	/**
	 * Adjust the number of configured {@code parameters} elements.
	 *
	 * <p>
	 * Any newly added element values will be set to new
	 * {@link ExpressionConfig} instances.
	 * </p>
	 *
	 * @param count
	 *        The desired number of {@code parameters} elements.
	 */
	public final void setParametersCount(int count) {
		this.parameters = ArrayUtils.arrayWithLength(this.parameters, count, ExpressionConfig.class,
				null);
	}

	/**
	 * Set the instruction parameters.
	 *
	 * @param parameters
	 *        the parameters to set
	 */
	public final void setParameters(ExpressionConfig @Nullable [] parameters) {
		this.parameters = parameters;
	}

	/**
	 * Get the response expression configurations.
	 *
	 * @return the responses
	 */
	public final ExpressionConfig @Nullable [] getResponses() {
		return responses;
	}

	/**
	 * Set the response expression configurations.
	 *
	 * @param responses
	 *        the responses to set
	 */
	public final void setResponses(ExpressionConfig @Nullable [] responses) {
		this.responses = responses;
	}

	/**
	 * Get the number of configured {@code responses} elements.
	 *
	 * @return the number of {@code responses} elements
	 */
	public final int getResponsesCount() {
		final ExpressionConfig[] confs = this.responses;
		return (confs == null ? 0 : confs.length);
	}

	/**
	 * Adjust the number of configured {@code responses} elements.
	 *
	 * <p>
	 * Any newly added element values will be set to new
	 * {@link ExpressionConfig} instances.
	 * </p>
	 *
	 * @param count
	 *        The desired number of {@code responses} elements.
	 */
	public final void setResponsesCount(int count) {
		this.responses = ArrayUtils.arrayWithLength(this.responses, count, ExpressionConfig.class, null);
	}

}
