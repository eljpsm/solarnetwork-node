/* ==================================================================
 * FixedDatumDataSourceTests.java - 10/07/2026 3:33:19 pm
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

package net.solarnetwork.node.datum.fixed.test;

import static java.time.ZoneOffset.UTC;
import static java.util.Map.entry;
import static net.solarnetwork.domain.datum.DatumSamplesType.Accumulating;
import static net.solarnetwork.domain.datum.DatumSamplesType.Instantaneous;
import static net.solarnetwork.domain.datum.DatumSamplesType.Status;
import static org.assertj.core.api.BDDAssertions.from;
import static org.assertj.core.api.BDDAssertions.then;
import static org.assertj.core.api.InstanceOfAssertFactories.map;
import static org.easymock.EasyMock.expect;
import java.io.IOException;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import org.easymock.EasyMock;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import net.solarnetwork.node.datum.fixed.FixedDatumDataSource;
import net.solarnetwork.node.datum.fixed.PropertyConfig;
import net.solarnetwork.node.domain.datum.NodeDatum;
import net.solarnetwork.node.service.MetadataService;
import net.solarnetwork.service.StaticOptionalService;
import net.solarnetwork.test.CommonTestUtils;

/**
 * Test cases for the {@link FixedDatumDataSource} class.
 *
 * @author matt
 * @version 1.0
 */
public class FixedDatumDataSourceTests {

	private Clock clock;
	private String sourceId;
	private MetadataService metadataService;

	private FixedDatumDataSource ds;

	@Before
	public void setup() {
		clock = Clock.fixed(Instant.now().truncatedTo(ChronoUnit.MINUTES), UTC);
		sourceId = CommonTestUtils.randomString();
		metadataService = EasyMock.createMock(MetadataService.class);

		ds = new FixedDatumDataSource(clock);
		ds.setSourceId(sourceId);
		ds.setMetadataService(new StaticOptionalService<>(metadataService));
	}

	@After
	public void teardown() {
		EasyMock.verify(metadataService);
	}

	private void replayAll() {
		EasyMock.replay(metadataService);
	}

	@Test
	public void basic() {
		// GIVEN
		final var fooPropConfig = new PropertyConfig("foo", Accumulating, "123");
		final var barPropConfig = new PropertyConfig("bar", Instantaneous, "234");
		final var bamPropConfig = new PropertyConfig("bam", Status, "abc");

		ds.setPropertyConfigs(new PropertyConfig[] { fooPropConfig, barPropConfig, bamPropConfig });

		// WHEN
		replayAll();
		final NodeDatum result = ds.readCurrentDatum();

		// THEN
		// @formatter:off
		then(result)
			.as("Datum generated")
			.isNotNull()
			.as("Datum uses configured source ID")
			.returns(sourceId, from(NodeDatum::getSourceId))
			.as("Datum uses clock time")
			.returns(clock.instant(), from(NodeDatum::getTimestamp))
			.extracting(NodeDatum::asSampleOperations)
			.satisfies(s -> {
				then(s.getSampleData(Accumulating))
					.asInstanceOf(map(String.class, Number.class))
					.containsOnly(
						entry(fooPropConfig.getPropertyKey(), 123)
					)
					;
				then(s.getSampleData(Instantaneous))
					.asInstanceOf(map(String.class, Number.class))
					.containsOnly(
						entry(barPropConfig.getPropertyKey(), 234)
					)
					;
				then(s.getSampleData(Status))
					.asInstanceOf(map(String.class, String.class))
					.containsOnly(
						entry(bamPropConfig.getPropertyKey(), bamPropConfig.getConfig())
					)
					;
			})
			;
		// @formatter:on
	}

	@Test
	public void tou() throws IOException {
		// GIVEN
		final String[][] tou = new String[][] {
				new String[] { "Month", "Day Range", "Day of Week Range", "Hour of Day Range", "Foo",
						"Bar" },
				new String[] { "January-December", null, "Mon-Fri", "0-24", "100", "200" },
				new String[] { "January-December", null, "Sat-Sun", "0-24", "1000", "2000" } };

		final String metadataPath = "/pm/foo/" + CommonTestUtils.randomString();
		ds.setTouMetadataPath(metadataPath);

		expect(metadataService.metadataAtPath(metadataPath)).andReturn(tou);

		// WHEN
		replayAll();
		final NodeDatum result = ds.readCurrentDatum();

		// THEN
		// @formatter:off
		then(result)
			.as("Datum generated")
			.isNotNull()
			.as("Datum uses configured source ID")
			.returns(sourceId, from(NodeDatum::getSourceId))
			.as("Datum uses clock time")
			.returns(clock.instant(), from(NodeDatum::getTimestamp))
			.extracting(NodeDatum::asSampleOperations)
			.satisfies(s -> {
				then(s.getSampleData(Accumulating))
					.isNull()
					;

				Integer expectedFoo = 100;
				Integer expectedBar = 200;

				final ZonedDateTime resultDate = result.getTimestamp().atZone(ds.getTouZone());
				if (resultDate.getDayOfWeek() == DayOfWeek.SATURDAY || resultDate.getDayOfWeek() == DayOfWeek.SUNDAY) {
					expectedFoo = 1000;
					expectedBar = 2000;
				}

				then(s.getSampleData(Instantaneous))
					.asInstanceOf(map(String.class, Number.class))
					.containsOnly(
						entry("Foo", expectedFoo),
						entry("Bar", expectedBar)
					)
					;
				then(s.getSampleData(Status))
					.isNull()
					;
			})
			;
		// @formatter:on
	}

}
