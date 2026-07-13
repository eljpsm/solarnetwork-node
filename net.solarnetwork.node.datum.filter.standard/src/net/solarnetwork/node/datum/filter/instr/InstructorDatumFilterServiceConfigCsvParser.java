/* ==================================================================
 * InstructorDatumFilterServiceConfigCsvParser.java - 13/07/2026 10:55:35 am
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

import static net.solarnetwork.util.ObjectUtils.requireNonNullArgument;
import java.io.UncheckedIOException;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.springframework.context.MessageSource;
import de.siegmar.fastcsv.reader.CommentStrategy;
import de.siegmar.fastcsv.reader.CsvReader;
import de.siegmar.fastcsv.reader.CsvRecord;
import net.solarnetwork.domain.datum.DatumSamplesType;
import net.solarnetwork.node.service.support.ExpressionConfig;
import net.solarnetwork.util.StringUtils;

/**
 * Parse CSV data into {@link InstructorDatumFilterServiceConfig} instances.
 *
 * @author matt
 * @version 1.0
 * @since 4.5
 */
public class InstructorDatumFilterServiceConfigCsvParser {

	/** The SpEL expression service ID. */
	public static final String EXPRESSION_SERVICE_UID = "net.solarnetwork.common.expr.spel.SpelExpressionService";

	private final List<InstructorDatumFilterServiceConfig> results;
	private final MessageSource messageSource;
	private final List<String> messages;

	/**
	 * Constructor.
	 *
	 * @param results
	 *        the list to add the parsed results to
	 * @param messageSource
	 *        the message source
	 * @param messages
	 *        the list of output messages to add messages to
	 */
	public InstructorDatumFilterServiceConfigCsvParser(List<InstructorDatumFilterServiceConfig> results,
			MessageSource messageSource, List<String> messages) {
		super();
		this.messageSource = requireNonNullArgument(messageSource, "messageSource");
		this.results = requireNonNullArgument(results, "results");
		this.messages = requireNonNullArgument(messages, "messages");
	}

	/**
	 * Parse CSV.
	 *
	 * @param csv
	 *        the CSV to parse; note that the comment strategy should be set to
	 *        {@link CommentStrategy#NONE} so comments can be handled as
	 *        parameters
	 * @throws UncheckedIOException
	 *         if any IO error occurs
	 */
	public void parse(CsvReader<CsvRecord> csv) throws UncheckedIOException {
		if ( csv == null ) {
			return;
		}
		csv.skipLines(1);
		final Map<String, String> configMeta = new HashMap<>(8);
		InstructorDatumFilterServiceConfig config = null;
		InstructorConfig instructorConfig = null;
		InstructionConfig instructionConfig = null;
		for ( CsvRecord row : csv ) {
			final int rowLen = row.getFieldCount();
			final long rowNum = row.getStartingLineNumber();
			final String key = rowKeyValue(row, config);
			if ( key == null ) {
				// either a comment line, or empty key but no active configuration
				continue;
			}
			if ( key.startsWith("#") ) {
				if ( "#param".equalsIgnoreCase(key) && rowLen >= 3 ) {
					String metaKey = row.getField(1);
					String metaVal = row.getField(2);
					if ( metaKey != null && !metaKey.isEmpty() && metaVal != null
							&& !metaVal.isEmpty() ) {
						configMeta.put(metaKey, metaVal);
					}
				}
				continue;
			}

			if ( config == null || (key != null && !key.equals(config.getKey())) ) {
				// starting new filter configuration
				config = new InstructorDatumFilterServiceConfig();
				results.add(config);
				config.setKey(key);
				config.setServiceName(parseStringValue(row, rowLen, rowNum,
						InstructorDatumFilterServiceCsvColumn.SERVICE_NAME.getCode()));
				config.setServiceGroup(parseStringValue(row, rowLen, rowNum,
						InstructorDatumFilterServiceCsvColumn.SERVICE_GROUP.getCode()));
				config.setSourceId(parseStringValue(row, rowLen, rowNum,
						InstructorDatumFilterServiceCsvColumn.SOURCE_ID.getCode()));
				config.setMode(parseStringValue(row, rowLen, rowNum,
						InstructorDatumFilterServiceCsvColumn.MODE.getCode()));
				config.setTag(parseStringValue(row, rowLen, rowNum,
						InstructorDatumFilterServiceCsvColumn.TAG.getCode()));
				config.getMeta().putAll(configMeta);
				configMeta.clear();
				instructorConfig = null;
				instructionConfig = null;
			}

			final String predicate = parseStringValue(row, rowLen, rowNum,
					InstructorDatumFilterServiceCsvColumn.PREDICATE.getCode());
			if ( instructorConfig == null || (predicate != null
					&& !predicate.equals(instructorConfig.getPredicate().getExpression())) ) {
				// starting a new instructor configuration
				instructorConfig = new InstructorConfig();
				instructorConfig.getPredicate().setExpression(predicate);
				instructorConfig.getPredicate().setExpressionServiceId(EXPRESSION_SERVICE_UID);
				config.getInstructorConfigs().add(instructorConfig);
				instructionConfig = null;
			}

			final String topic = parseStringValue(row, rowLen, rowNum,
					InstructorDatumFilterServiceCsvColumn.TOPIC.getCode());
			if ( instructionConfig == null || topic != null ) {
				instructionConfig = new InstructionConfig();
				instructionConfig.setTopic(topic);
				instructorConfig.addInstruction(instructionConfig);
			}

			final String paramName = parseStringValue(row, rowLen, rowNum,
					InstructorDatumFilterServiceCsvColumn.PARAM_NAME.getCode());
			if ( paramName != null ) {
				final String paramValue = parseStringValue(row, rowLen, rowNum,
						InstructorDatumFilterServiceCsvColumn.PARAM_VALUE.getCode());
				final boolean isExpr = parseBooleanValue(row, rowLen, rowNum,
						InstructorDatumFilterServiceCsvColumn.PARAM_IS_EXPRESSION.getCode());
				if ( paramValue != null ) {
					ExpressionConfig paramConfig = new ExpressionConfig();
					paramConfig.setName(paramName);
					paramConfig.setExpression(paramValue);
					if ( isExpr ) {
						paramConfig.setExpressionServiceId(EXPRESSION_SERVICE_UID);
					}
					instructionConfig.addParameter(paramConfig);
				}
			}

			final String resultExpr = parseStringValue(row, rowLen, rowNum,
					InstructorDatumFilterServiceCsvColumn.RESULT_EXPRESSION.getCode());
			if ( resultExpr != null ) {
				final String resultProp = parseStringValue(row, rowLen, rowNum,
						InstructorDatumFilterServiceCsvColumn.RESULT_PROPERTY.getCode());
				final DatumSamplesType resultPropType = parseDatumSamplesTypeValue(row, rowLen, rowNum,
						InstructorDatumFilterServiceCsvColumn.RESULT_PROPERTY_TYPE.getCode());
				ExpressionConfig resultConfig = new ExpressionConfig();
				resultConfig.setExpression(resultExpr);
				resultConfig.setExpressionServiceId(EXPRESSION_SERVICE_UID);
				resultConfig.setName(resultProp);
				resultConfig.setDatumPropertyType(resultPropType);
				instructionConfig.addResponse(resultConfig);
			}
		}
	}

	private @Nullable String rowKeyValue(CsvRecord row,
			@Nullable InstructorDatumFilterServiceConfig currentConfig) {
		String key = row.getField(0);
		if ( key != null ) {
			key = key.trim();
		}
		if ( key != null && !key.isEmpty() ) {
			if ( "-".equals(key) ) {
				return String.valueOf(results.size() + 1);
			}
			return key;
		}
		return (currentConfig != null ? currentConfig.getKey() : null);
	}

	private @Nullable String parseStringValue(CsvRecord row, int rowLen, long rowNum, int colNum) {
		if ( colNum < rowLen ) {
			String s = row.getField(colNum);
			if ( s != null ) {
				s = s.trim();
			}
			if ( s == null || s.isEmpty() ) {
				return null;
			}
			return s;
		}
		return null;
	}

	private boolean parseBooleanValue(CsvRecord row, int rowLen, long rowNum, int colNum) {
		String s = parseStringValue(row, rowLen, rowNum, colNum);
		return StringUtils.parseBoolean(s);
	}

	private @Nullable DatumSamplesType parseDatumSamplesTypeValue(CsvRecord row, int rowLen, long rowNum,
			int colNum) {
		String s = parseStringValue(row, rowLen, rowNum, colNum);
		if ( s == null ) {
			return null;
		}
		try {
			return DatumSamplesType.valueOf(Character.toLowerCase(s.charAt(0)));
		} catch ( IllegalArgumentException e ) {
			try {
				return DatumSamplesType.valueOf(s);
			} catch ( IllegalArgumentException e2 ) {
				messages.add(messageSource.getMessage("message.datumSamplesTypeFormatError",
						new Object[] { s, rowNum, colNum }, "Malformed result property type value.",
						Locale.getDefault()));
			}
		}
		return null;
	}

}
