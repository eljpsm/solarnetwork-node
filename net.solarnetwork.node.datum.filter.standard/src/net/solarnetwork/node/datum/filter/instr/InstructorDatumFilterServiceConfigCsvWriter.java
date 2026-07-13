/* ==================================================================
 * InstructorDatumFilterServiceConfigCsvWriter.java - 13/07/2026 10:55:14 am
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

import static java.util.Arrays.fill;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.Map.Entry;
import org.jspecify.annotations.Nullable;
import de.siegmar.fastcsv.writer.CsvWriter;
import net.solarnetwork.node.domain.Setting;
import net.solarnetwork.node.service.support.ExpressionConfig;
import net.solarnetwork.util.ObjectUtils;

/**
 * Generate Instructor Filter configuration CSV from settings.
 *
 * @author matt
 * @version 1.0
 * @since 4.5
 */
public class InstructorDatumFilterServiceConfigCsvWriter {

	private final CsvWriter writer;
	private final int rowLen;

	/**
	 * Constructor.
	 *
	 * @param writer
	 *        the writer; note the comment character should be set to something
	 *        <b>other</b> than {@code #} so comments can be generated manually
	 * @throws IllegalArgumentException
	 *         if any argument is {@code null}
	 * @throws UncheckedIOException
	 *         if any IO error occurs
	 */
	public InstructorDatumFilterServiceConfigCsvWriter(CsvWriter writer) throws UncheckedIOException {
		super();
		this.writer = ObjectUtils.requireNonNullArgument(writer, "writer");
		rowLen = InstructorDatumFilterServiceCsvColumn.values().length;
		String[] row = new String[rowLen];
		for ( InstructorDatumFilterServiceCsvColumn col : InstructorDatumFilterServiceCsvColumn
				.values() ) {
			row[col.getCode()] = col.getName();
		}
		writer.writeRecord(row);
	}

	/**
	 * Generate Modbus Server CSV from settings.
	 *
	 * @param factoryId
	 *        the Modbus Device factory ID
	 * @param instanceId
	 *        the instance ID
	 * @param settings
	 *        the settings to generate CSV for
	 * @throws UncheckedIOException
	 *         if any IO error occurs
	 */
	public void generateCsv(String factoryId, String instanceId, List<Setting> settings)
			throws UncheckedIOException {
		if ( settings == null || settings.isEmpty() ) {
			return;
		}

		final InstructorDatumFilterServiceConfig config = new InstructorDatumFilterServiceConfig();
		config.setKey(instanceId);
		for ( Setting s : settings ) {
			config.populateFromSetting(s);
		}
		@Nullable
		String[] row = new String[rowLen];
		row[InstructorDatumFilterServiceCsvColumn.INSTANCE_ID.getCode()] = config.getKey();
		row[InstructorDatumFilterServiceCsvColumn.SERVICE_NAME.getCode()] = config.getServiceName();
		row[InstructorDatumFilterServiceCsvColumn.SERVICE_GROUP.getCode()] = config.getServiceGroup();
		row[InstructorDatumFilterServiceCsvColumn.SOURCE_ID.getCode()] = config.getSourceId();
		row[InstructorDatumFilterServiceCsvColumn.MODE.getCode()] = config.getMode();
		row[InstructorDatumFilterServiceCsvColumn.TAG.getCode()] = config.getTag();

		// generate #param rows for unhandled settings, like uid etc
		for ( Entry<String, String> e : config.getMeta().entrySet() ) {
			writer.writeRecord(new String[] { "#param", e.getKey(), e.getValue() });
		}

		for ( InstructorConfig instructorConfig : config.getInstructorConfigs() ) {
			row[InstructorDatumFilterServiceCsvColumn.PREDICATE.getCode()] = instructorConfig
					.getPredicate().getExpression();

			final InstructionConfig[] instrConfigs = instructorConfig.getInstructions();
			if ( instrConfigs == null ) {
				writer.writeRecord(row);
				fill(row, null);
				continue;
			}
			for ( int instrIdx = 0; instrIdx < instrConfigs.length; instrIdx++ ) {
				final InstructionConfig instrConfig = instrConfigs[instrIdx];
				row[InstructorDatumFilterServiceCsvColumn.TOPIC.getCode()] = instrConfig.getTopic();

				final ExpressionConfig[] paramConfigs = instrConfig.getParameters();
				final ExpressionConfig[] responseConfigs = instrConfig.getResponses();

				// handle first parameter inline this row
				if ( paramConfigs != null && paramConfigs.length > 0 ) {
					populateParameterColumns(paramConfigs[0], row);
				}

				// handle first response inline this row
				if ( responseConfigs != null && responseConfigs.length > 0 ) {
					populateResultColumns(responseConfigs[0], row);
				}

				writer.writeRecord(row);
				fill(row, null);

				for ( int exprIdx = 1, len = Math.max(paramConfigs != null ? paramConfigs.length : 0,
						responseConfigs != null ? responseConfigs.length
								: 0); exprIdx < len; exprIdx++ ) {
					row = new String[rowLen];
					if ( paramConfigs != null && paramConfigs.length > exprIdx ) {
						populateParameterColumns(paramConfigs[exprIdx], row);
					}
					if ( responseConfigs != null && responseConfigs.length > exprIdx ) {
						populateResultColumns(responseConfigs[exprIdx], row);
					}
					writer.writeRecord(row);
					fill(row, null);
				}
			}
		}
	}

	private void populateParameterColumns(ExpressionConfig config, @Nullable String[] row) {
		row[InstructorDatumFilterServiceCsvColumn.PARAM_NAME.getCode()] = config.getName();
		row[InstructorDatumFilterServiceCsvColumn.PARAM_VALUE.getCode()] = config.getExpression();
		row[InstructorDatumFilterServiceCsvColumn.PARAM_IS_EXPRESSION.getCode()] = String.valueOf(
				config.getExpressionServiceId() != null && !config.getExpressionServiceId().isEmpty());
	}

	private void populateResultColumns(ExpressionConfig config, @Nullable String[] row) {
		row[InstructorDatumFilterServiceCsvColumn.RESULT_EXPRESSION.getCode()] = config.getExpression();
		row[InstructorDatumFilterServiceCsvColumn.RESULT_PROPERTY.getCode()] = config.getName();
		row[InstructorDatumFilterServiceCsvColumn.RESULT_PROPERTY_TYPE
				.getCode()] = (config.getDatumPropertyType() != null
						? config.getDatumPropertyType().name()
						: null);
	}

}
