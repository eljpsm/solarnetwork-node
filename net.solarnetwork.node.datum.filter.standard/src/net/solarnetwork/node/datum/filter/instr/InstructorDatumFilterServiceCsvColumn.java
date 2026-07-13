/* ==================================================================
 * InstructorDatumFilterServiceCsvColumn.java - 13/07/2026 7:16:52 am
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

import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;
import net.solarnetwork.domain.CodedValue;

/**
 * The defined column order for Instructor Datum Filter Service CSV.
 *
 * @author matt
 * @version 1.0
 * @since 4.5
 */
public enum InstructorDatumFilterServiceCsvColumn implements CodedValue {

	/** The component instance ID. */
	INSTANCE_ID(0, "Instance ID"),

	/** The service name (UID). */
	SERVICE_NAME(1, "Service Name"),

	/** The service group. */
	SERVICE_GROUP(2, "Service Group"),

	/** The source ID condition. */
	SOURCE_ID(3, "Source ID"),

	/** The mode condition. */
	MODE(4, "Mode"),

	/** The tag condition. */
	TAG(5, "Tag"),

	/** The predicate expression. */
	PREDICATE(6, "Predicate"),

	/** The instruction topic. */
	TOPIC(7, "Instruction Topic"),

	/** An instruction parameter name. */
	PARAM_NAME(8, "Parameter Name"),

	/** An instruction parameter value. */
	PARAM_VALUE(9, "Parameter Value"),

	/** The "parameter value is expression" mode. */
	PARAM_IS_EXPRESSION(10, "Parameter Expression"),

	/** A result expression. */
	RESULT_EXPRESSION(11, "Result Expression"),

	/** A result datum property name. */
	RESULT_PROPERTY(12, "Result Property"),

	/** A result datum property type. */
	RESULT_PROPERTY_TYPE(13, "Result Property Type"),

	;

	private final int idx;
	private final String name;

	private InstructorDatumFilterServiceCsvColumn(int idx, String name) {
		this.idx = idx;
		this.name = name;
	}

	/**
	 * The set of columns are specify filter-wide settings.
	 */
	public static final Set<InstructorDatumFilterServiceCsvColumn> FILTER_WIDE_COLUMNS = Collections
			.unmodifiableSet(EnumSet.of(INSTANCE_ID, SERVICE_NAME, SERVICE_GROUP, SOURCE_ID, MODE, TAG));

	@Override
	public int getCode() {
		return idx;
	}

	/**
	 * Get a friendly name for the column.
	 *
	 * @return the name
	 */
	public String getName() {
		return name;
	}

}
