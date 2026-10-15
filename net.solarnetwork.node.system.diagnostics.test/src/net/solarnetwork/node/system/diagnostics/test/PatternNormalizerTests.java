/* ==================================================================
 * PatternNormalizerTests.java - 16/10/2026 9:00:00 am
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

package net.solarnetwork.node.system.diagnostics.test;

import static org.assertj.core.api.BDDAssertions.then;
import java.util.concurrent.TimeUnit;
import org.junit.Test;
import net.solarnetwork.node.system.diagnostics.PatternNormalizer;
import net.solarnetwork.node.system.diagnostics.PatternNormalizer.Normalized;
import net.solarnetwork.node.system.diagnostics.Redactor;

/**
 * Test cases for the {@link PatternNormalizer} class.
 *
 * @author elijah
 * @version 1.0
 */
public class PatternNormalizerTests {

	@Test
	public void modbusTemplate() {
		// WHEN
		Normalized n = PatternNormalizer.normalize("Communication problem reading source /SITE-C/INV/3 "
				+ "from Modbus device 192.168.20.33:502: No route to host");

		// THEN
		// @formatter:off
		then(n)
			.returns("/SITE-C/INV/3", Normalized::getSource)
			.returns("192.168.20.33:502", Normalized::getTarget)
			.returns("Communication problem reading source <source> from Modbus device <target>: "
					+ "No route to host", Normalized::getPattern)
			;
		// @formatter:on
	}

	@Test
	public void modbusTemplate_realNetworkNamesAfterRedaction() {
		// GIVEN
		Redactor redactor = new Redactor();

		// WHEN
		Normalized tcp = PatternNormalizer.normalize(redactor.redact("Communication problem reading "
				+ "source /SITE-C/INV/3 from Modbus device 1@NiftyTcpModbusNetwork{192.168.20.33:502}: "
				+ "No route to host"));
		Normalized serial = PatternNormalizer.normalize(redactor
				.redact("Communication problem " + "reading source /SITE-C/MTR/1 from Modbus device "
						+ "2@NiftyJscSerialModbusNetwork{/dev/ttyUSB0 readTimeout=1000}: Timeout"));

		// THEN
		// @formatter:off
		then(tcp)
			.returns("/SITE-C/INV/3", Normalized::getSource)
			.returns("192.168.20.33:502", Normalized::getTarget)
			.returns("Communication problem reading source <source> from Modbus device "
					+ "<n>@NiftyTcpModbusNetwork{<target>}: No route to host", Normalized::getPattern)
			;
		then(serial)
			.returns("/SITE-C/MTR/1", Normalized::getSource)
			.returns("/dev/ttyUSB0", Normalized::getTarget)
			.returns("Communication problem reading source <source> from Modbus device "
					+ "<n>@NiftyJscSerialModbusNetwork{<target> readTimeout=<n>}: Timeout",
					Normalized::getPattern)
			;
		then(redactor.count()).as("Nothing redacted").isZero();
		// @formatter:on
	}

	@Test
	public void modbusTemplate_grouping() {
		// WHEN
		Normalized n1 = PatternNormalizer.normalize("Communication problem reading source /SITE-C/INV/3 "
				+ "from Modbus device 1@NiftyTcpModbusNetwork{192.168.20.33:502}: timeout");
		Normalized n2 = PatternNormalizer.normalize("Communication problem reading source Meter_2 "
				+ "from Modbus device 12@NiftyTcpModbusNetwork{10.0.0.2:5020}: timeout");

		// THEN
		// @formatter:off
		then(n1)
			.returns("/SITE-C/INV/3", Normalized::getSource)
			.returns("192.168.20.33:502", Normalized::getTarget)
			.returns("Communication problem reading source <source> from Modbus device "
					+ "<n>@NiftyTcpModbusNetwork{<target>}: timeout", Normalized::getPattern)
			;
		then(n2)
			.returns("Meter_2", Normalized::getSource)
			.returns("10.0.0.2:5020", Normalized::getTarget)
			.as("Same pattern for different sources and targets")
			.returns(n1.getPattern(), Normalized::getPattern)
			;
		// @formatter:on
	}

	@Test
	public void otherSourceTemplates() {
		// @formatter:off
		then(PatternNormalizer.normalize("Communication problem reading source INV1 from SunSpec "
				+ "inverter 1@/dev/ttyUSB0: timeout"))
			.returns("INV1", Normalized::getSource)
			.returns("/dev/ttyUSB0", Normalized::getTarget)
			.returns("Communication problem reading source <source> from SunSpec inverter "
					+ "<n>@<target>: timeout", Normalized::getPattern)
			;
		then(PatternNormalizer.normalize("Error saving source Foo/Bar metadata: Network down"))
			.returns("Foo/Bar", Normalized::getSource)
			;
		then(PatternNormalizer.normalize("Error looking up location 123 for source [Weather.1]: "
				+ "timeout"))
			.returns("Weather.1", Normalized::getSource)
			.returns("Error looking up location <n> for source [<source>]: timeout",
					Normalized::getPattern)
			;
		then(PatternNormalizer.normalize("Communication error acquiring metadata for source "
				+ "/VM/1: nope"))
			.returns("/VM/1", Normalized::getSource)
			;
		then(PatternNormalizer.normalize("No DatumQueue available: cannot generate datum for "
				+ "source /RES/1"))
			.returns("/RES/1", Normalized::getSource)
			.returns("No DatumQueue available: cannot generate datum for source <source>",
					Normalized::getPattern)
			;
		// @formatter:on
	}

	@Test
	public void sourceOnlyFromKnownTemplates() {
		// @formatter:off
		then(PatternNormalizer.normalize("Error posting datum Datum{kind=Node,sourceId=/SITE-C/MTR/1}"
				+ " via MQTT"))
			.as("Source not extracted from arbitrary text")
			.returns(null, Normalized::getSource)
			;
		then(PatternNormalizer.normalize("Prefix: Communication problem reading source /A/1 from X"))
			.as("Templates matched from start only")
			.returns(null, Normalized::getSource)
			;
		then(PatternNormalizer.normalize("Communication problem reading source /A/1%x from X"))
			.as("Invalid source characters not extracted")
			.returns(null, Normalized::getSource)
			;
		then(PatternNormalizer.normalize("Communication problem reading source " + "a".repeat(65)
				+ " from X"))
			.as("Source too long not extracted")
			.returns(null, Normalized::getSource)
			;
		// @formatter:on
	}

	@Test
	public void hostPortTarget() {
		// WHEN
		Normalized n = PatternNormalizer.normalize("Error posting datum Datum{kind=Node,sourceId=/S/1,"
				+ "ts=2026-10-15T10:10:00Z} via MQTT @ mqtts://queue.solarnetwork.net:8883, falling "
				+ "back to batch mode: Connection lost");

		// THEN
		// @formatter:off
		then(n)
			.returns("queue.solarnetwork.net:8883", Normalized::getTarget)
			.returns(null, Normalized::getSource)
			.returns("Error posting datum Datum{kind=Node,sourceId=/S/<n>,ts=<n>-<n>-<n>T<n>:<n>:<n>Z} "
					+ "via MQTT @ mqtts://<target>, falling back to batch mode: Connection lost",
					Normalized::getPattern)
			;
		then(PatternNormalizer.normalize("Connect to localhost:8080 failed"))
			.returns("localhost:8080", Normalized::getTarget)
			;
		// @formatter:on
	}

	@Test
	public void notTargets() {
		// @formatter:off
		then(PatternNormalizer.normalize("Error at Foo.java:123 in thing")).returns(null,
				Normalized::getTarget);
		then(PatternNormalizer.normalize("Time 12:30:45 port")).returns(null, Normalized::getTarget);
		then(PatternNormalizer.normalize("Host foo:123 without dot")).returns(null,
				Normalized::getTarget);
		then(PatternNormalizer.normalize("Bad ip 300.1.1.1:502")).returns(null, Normalized::getTarget);
		then(PatternNormalizer.normalize("Bad port 1.2.3.4:70000")).returns(null,
				Normalized::getTarget);
		then(PatternNormalizer.normalize("Bad host -a.b:502")).returns(null, Normalized::getTarget);
		// @formatter:on
	}

	@Test
	public void targetPrecedence() {
		// WHEN
		Normalized n = PatternNormalizer
				.normalize("host.example.com:80 then /dev/ttyS0 then 10.1.2.3:502 again 10.1.2.3:502");

		// THEN
		// @formatter:off
		then(n)
			.returns("10.1.2.3:502", Normalized::getTarget)
			.as("All target occurrences replaced; other values normalised")
			.returns("host.example.com:<n> then /dev/ttyS<n> then <target> again <target>",
					Normalized::getPattern)
			;
		// @formatter:on
	}

	@Test
	public void placeholders() {
		// WHEN
		Normalized n = PatternNormalizer.normalize("Instruction 3f2504e0-4f89-11d3-9a0c-0305e82c3301 "
				+ "from 10.0.0.1 code 0x1F hash a1b2c3d4e5 word deadbeefcafe value \"some text\" "
				+ "took 12.5ms count 42  \t spaced");

		// THEN
		then(n.getPattern()).isEqualTo("Instruction <uuid> from <ip> code <hex> hash <hex> word "
				+ "deadbeefcafe value \"<s>\" took <n>ms count <n> spaced");
	}

	@Test
	public void cut() {
		// WHEN
		Normalized n = PatternNormalizer.normalize("x".repeat(300) + "😀");

		// THEN
		then(n.getPattern()).hasSize(PatternNormalizer.MAX_LENGTH);
	}

	@Test
	public void cut_codePoints() {
		// WHEN
		Normalized n = PatternNormalizer.normalize("😀".repeat(300));

		// THEN
		then(n.getPattern().codePointCount(0, n.getPattern().length()))
				.isEqualTo(PatternNormalizer.MAX_LENGTH);
	}

	@Test
	public void adversarial() {
		// GIVEN
		final int len = 8 * 1024;
		final String[] inputs = new String[] { "a".repeat(len), "a.".repeat(len / 2),
				"1.".repeat(len / 2), "1".repeat(len), "\"".repeat(len), "\"a".repeat(len / 2),
				"a:1".repeat(len / 3), "/dev/".repeat(len / 5), "/dev/a".repeat(len / 6),
				"0x".repeat(len / 2), "aaaaaaaa-".repeat(len / 9), "1.1.1.1:".repeat(len / 8),
				"Communication problem reading source " + "a".repeat(len),
				"Error saving metadata values " + "x ".repeat(len / 2),
				"Error saving metadata values " + " for source [".repeat(len / 13) };

		// WHEN
		final long start = System.nanoTime();
		for ( int i = 0; i < 10; i++ ) {
			for ( String s : inputs ) {
				PatternNormalizer.normalize(s);
			}
		}
		final long ms = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

		// THEN
		then(ms).as("Linear time").isLessThan(5000L);
	}

}
