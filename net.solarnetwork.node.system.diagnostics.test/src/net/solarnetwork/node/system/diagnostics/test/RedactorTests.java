/* ==================================================================
 * RedactorTests.java - 16/10/2026 9:00:00 am
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

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.BDDAssertions.then;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import org.junit.Before;
import org.junit.Test;
import net.solarnetwork.node.system.diagnostics.Redactor;

/**
 * Test cases for the {@link Redactor} class.
 *
 * @author elijah
 * @version 1.0
 */
public class RedactorTests {

	private static final int MIN_LEAK_LENGTH = 6;
	private static final long MAX_ADVERSARIAL_MS = 50;

	private static final class CorpusEntry {

		private final int line;
		private final List<String> secrets;
		private final String message;

		private CorpusEntry(int line, List<String> secrets, String message) {
			super();
			this.line = line;
			this.secrets = secrets;
			this.message = message;
		}

	}

	private List<CorpusEntry> corpus;

	@Before
	public void setup() throws IOException {
		corpus = loadCorpus();
	}

	private List<CorpusEntry> loadCorpus() throws IOException {
		List<CorpusEntry> result = new ArrayList<>();
		try (InputStream in = getClass().getResourceAsStream("redact-corpus.txt")) {
			then(in).as("Corpus resource available").isNotNull();
			BufferedReader r = new BufferedReader(new InputStreamReader(in, UTF_8));
			String l;
			int lineNum = 0;
			while ( (l = r.readLine()) != null ) {
				lineNum++;
				if ( l.isBlank() || l.startsWith("#") ) {
					continue;
				}
				int tab = l.indexOf('\t');
				then(tab).as("Corpus line %d has a tab", lineNum).isPositive();
				List<String> secrets = Arrays.asList(l.substring(0, tab).split(","));
				result.add(new CorpusEntry(lineNum, secrets, l.substring(tab + 1)));
			}
		}
		return result;
	}

	/**
	 * Find the first substring of a secret, of at least
	 * {@link #MIN_LEAK_LENGTH} characters, contained in some text.
	 *
	 * @param text
	 *        the text to search
	 * @param secret
	 *        the secret
	 * @return the leaked substring, or {@code null}
	 */
	private static String leak(String text, String secret) {
		for ( int i = 0; i + MIN_LEAK_LENGTH <= secret.length(); i++ ) {
			String sub = secret.substring(i, i + MIN_LEAK_LENGTH);
			if ( text.contains(sub) ) {
				return sub;
			}
		}
		return null;
	}

	@Test
	public void corpus_loaded() {
		then(corpus).as("Corpus has many entries").hasSizeGreaterThan(30);
		for ( CorpusEntry e : corpus ) {
			for ( String secret : e.secrets ) {
				// @formatter:off
				then(secret)
					.as("Corpus line %d secret is long enough to test", e.line)
					.hasSizeGreaterThanOrEqualTo(MIN_LEAK_LENGTH)
					;
				then(e.message)
					.as("Corpus line %d message contains its planted secret", e.line)
					.contains(secret)
					;
				then(leak(e.message.replace(secret, " "), secret))
					.as("Corpus line %d secret is distinct from the rest of its message", e.line)
					.isNull()
					;
				// @formatter:on
			}
		}
	}

	@Test
	public void corpus_noSecretsLeak() {
		// GIVEN
		List<String> failures = new ArrayList<>();

		// WHEN
		for ( CorpusEntry e : corpus ) {
			Redactor redactor = new Redactor();
			String out = redactor.redact(e.message);
			for ( String secret : e.secrets ) {
				String leak = leak(out, secret);
				if ( leak != null ) {
					failures.add("line " + e.line + " leaked [" + leak + "]: " + out);
				}
			}
			if ( redactor.count() < 1 ) {
				failures.add("line " + e.line + " not counted: " + out);
			}
		}

		// THEN
		then(failures).as("No planted secret leaked").isEmpty();
	}

	@Test
	public void corpus_truncatedMessagesDoNotLeak() {
		// GIVEN
		List<String> failures = new ArrayList<>();

		// WHEN
		for ( CorpusEntry e : corpus ) {
			final String raw = e.message;
			for ( int k = 0; k <= raw.length(); k++ ) {
				if ( k > 0 && k < raw.length() && Character.isLowSurrogate(raw.charAt(k)) ) {
					continue;
				}
				String out = new Redactor().redact(raw.substring(0, k));
				for ( String secret : e.secrets ) {
					String leak = leak(out, secret);
					if ( leak != null ) {
						failures.add("line " + e.line + " cut " + k + " leaked [" + leak + "]: " + out);
					}
				}
			}
		}

		// THEN
		then(failures).as("No planted secret leaked from any truncated message").isEmpty();
	}

	@Test
	public void corpus_redactThenCutDoesNotLeak() {
		// GIVEN
		List<String> failures = new ArrayList<>();

		// WHEN
		for ( CorpusEntry e : corpus ) {
			String out = new Redactor().redact(e.message);
			for ( int k = 0; k <= out.length(); k++ ) {
				String cut = Redactor.cut(out, k);
				for ( String secret : e.secrets ) {
					if ( leak(cut, secret) != null ) {
						failures.add("line " + e.line + " cut " + k + ": " + cut);
					}
				}
			}
		}

		// THEN
		then(failures).as("Cutting redacted text never leaks").isEmpty();
	}

	@Test
	public void corpus_idempotent() {
		// GIVEN
		List<String> failures = new ArrayList<>();

		// WHEN
		for ( CorpusEntry e : corpus ) {
			final String raw = e.message;
			for ( int k = 0; k <= raw.length(); k++ ) {
				Redactor redactor = new Redactor();
				String once = redactor.redact(raw.substring(0, k));
				int count = redactor.count();
				String twice = redactor.redact(once);
				if ( !once.equals(twice) ) {
					failures.add("line " + e.line + " cut " + k + ": [" + once + "] != [" + twice + "]");
				} else if ( redactor.count() != count ) {
					failures.add("line " + e.line + " cut " + k + " re-counted: " + once);
				}
			}
		}

		// THEN
		then(failures).as("Redaction is idempotent").isEmpty();
	}

	@Test
	public void usefulDiagnosticsUnchanged() {
		// GIVEN
		// @formatter:off
		List<String> messages = Arrays.asList(
				"Communication problem reading source /SITE-C/INV/3 from Modbus device 192.168.20.33:502: No route to host",
				"Timeout acquiring lock on serial port /dev/ttyUSB0",
				"Error executing [sudo systemctl restart solarnode], exit status: 1",
				"Instruction 12345 [SetControlParameter] state changed to Completed",
				"Error handling MQTT message on topic node/7294/datum/0/inv",
				"Posted Instruction 81234567 [SystemConfiguration] acknowledgement status: Completed",
				"Service [Modbus Device] instruction [Signal] was not handled; parameters: {}",
				"Error parsing price date from columns [0, 1] value [2026-10-16 12:30]: Text could not be parsed",
				"Error communicating with eGauge inverter at http://egauge12345.local/cgi-bin/egauge: Connection refused",
				"Communication error with motion camera 1 at http://localhost:8080: java.net.ConnectException",
				"Error loading net.solarnetwork.node.datum.modbus.ModbusDatumDataSourceConfiguration settings",
				"Basic setup complete; token refresh scheduled",
				"Error searching for latest snapshot image file: java.nio.file.NoSuchFileException: /var/lib/solarnode/var/motion/snapshots"
				);
		// @formatter:on

		for ( String msg : messages ) {
			// WHEN
			Redactor redactor = new Redactor();
			String out = redactor.redact(msg);

			// THEN
			then(out).as("Diagnostic message unchanged").isEqualTo(msg);
			then(redactor.count()).as("Nothing counted for [%s]", msg).isZero();
		}
	}

	@Test
	public void templates() {
		// GIVEN
		Map<String, String> cases = new LinkedHashMap<>();
		cases.put("Instruction 1 UpdateSetting received with parameters: {key=a, value=b}",
				"Instruction 1 UpdateSetting received with parameters: <redacted>");
		cases.put(
				"Unable to accept instruction JSON [{\"topic\":\"X\",\"parameters\":[]}]: "
						+ "java.lang.IllegalArgumentException: bad thing",
				"Unable to accept instruction JSON [<redacted>]: java.lang.IllegalArgumentException: "
						+ "<redacted>");
		cases.put(
				"Unable to accept instruction JSON [{\"topic\":\"X\"}]: java.lang.NullPointerException",
				"Unable to accept instruction JSON [<redacted>]: java.lang.NullPointerException");
		cases.put("Unable to accept instruction JSON [{\"topic\":\"X\",\"parame",
				"Unable to accept instruction JSON <redacted>");
		cases.put("Error posting instruction status BasicInstruction{topic=X} via MQTT @ x: y",
				"Error posting instruction status <redacted>");
		cases.put("status was BasicInstructionStatus{instructionId=1, resultParameters={a=b}}",
				"status was BasicInstructionStatus{…}");
		cases.put("SQL warning saving setting a.b to secretvalue",
				"SQL warning saving setting a.b to <redacted>");

		for ( Map.Entry<String, String> c : cases.entrySet() ) {
			// WHEN
			String out = new Redactor().redact(c.getKey());

			// THEN
			then(out).as("Template redacted").isEqualTo(c.getValue());
		}
	}

	@Test
	public void braces() {
		// GIVEN
		Redactor redactor = new Redactor();

		// WHEN
		String nested = redactor.redact("parameters: {a=1, b={c=2, d=\"}\"}} done {}");
		String unclosed = redactor.redact("parameters: {a=1, b={c=2} done");

		// THEN
		then(nested).as("Nested braces elided, empty braces kept").isEqualTo("parameters: {…} done {}");
		then(unclosed).as("Unclosed brace elided to end").isEqualTo("parameters: {…");
		then(redactor.count()).as("Two groups elided").isEqualTo(2);
	}

	@Test
	public void urls() {
		// GIVEN
		Map<String, String> cases = new LinkedHashMap<>();
		cases.put("at http://owner:pw123456@egauge1.local/cgi-bin/egauge?inst&tot: Connection refused",
				"at http://<redacted>@egauge1.local/cgi-bin/egauge?<redacted>: Connection refused");
		cases.put("URL [https://h.example.com/a?appid=abcdef123456#frag]: 401",
				"URL [https://h.example.com/a?<redacted>]: 401");
		cases.put("at http://admin:pass1", "at http://admin:<redacted>");
		cases.put("connected to mqtts://queue.solarnetwork.net:8883, ok",
				"connected to mqtts://queue.solarnetwork.net:8883, ok");
		cases.put("request uri=/a/b?x=1&y=two words: failed", "request uri=/a/b?<redacted>");
		cases.put("pairs a=1&b=2 here", "pairs a=1&b=<redacted> here");

		for ( Map.Entry<String, String> c : cases.entrySet() ) {
			// WHEN
			String out = new Redactor().redact(c.getKey());

			// THEN
			then(out).as("URL redacted").isEqualTo(c.getValue());
		}
	}

	@Test
	public void keys() {
		// GIVEN
		Map<String, String> cases = new LinkedHashMap<>();
		cases.put("password=abc, apiKey=def, user=bob",
				"password=<redacted>, apiKey=<redacted>, user=bob");
		cases.put("mqttPassword: abc def", "mqttPassword: <redacted>");
		cases.put("\"accessToken\": \"abc\", \"user\": \"bob\"",
				"\"accessToken\": \"<redacted>\", \"user\": \"bob\"");
		cases.put("client_secret='abc def' next", "client_secret='<redacted>' next");
		cases.put("type=mqttPassword value=abc", "type=mqttPassword value=<redacted>");
		cases.put("\"name\":\"api_key\",\"value\":\"xyz\"",
				"\"name\":\"api_key\",\"value\":\"<redacted>\"");
		cases.put("name=sourceId value=/INV/1", "name=sourceId value=/INV/1");
		cases.put("X-Amz-Date: 20261016T000000Z", "X-Amz-Date: <redacted>");
		cases.put("JSESSIONID=abc; session_id=xyz; path=/",
				"JSESSIONID=<redacted>; session_id=<redacted>; path=/");
		cases.put("-Dsolarnode.mqtt.password=abc -Dfoo=bar",
				"-Dsolarnode.mqtt.password=<redacted> -Dfoo=bar");

		for ( Map.Entry<String, String> c : cases.entrySet() ) {
			// WHEN
			String out = new Redactor().redact(c.getKey());

			// THEN
			then(out).as("Key value redacted").isEqualTo(c.getValue());
		}
	}

	@Test
	public void auth() {
		// GIVEN
		Map<String, String> cases = new LinkedHashMap<>();
		cases.put("Authorization: Bearer abc.def; next", "Authorization: <redacted>; next");
		cases.put("with Bearer abc.def ok", "with Bearer <redacted> ok");
		cases.put("Basic dXNlcjpwYXNz failed", "Basic <redacted> failed");
		cases.put("header SNWS2 Credential=a,SignedHeaders=date,Signature=b end",
				"header SNWS2 <redacted> end");
		cases.put("-----BEGIN CERTIFICATE-----\nMIIB\n-----END CERTIFICATE-----",
				"-----BEGIN <redacted>");

		for ( Map.Entry<String, String> c : cases.entrySet() ) {
			// WHEN
			String out = new Redactor().redact(c.getKey());

			// THEN
			then(out).as("Auth redacted").isEqualTo(c.getValue());
		}
	}

	@Test
	public void bareKeywordsAndAwsKeys() {
		// GIVEN
		Map<String, String> cases = new LinkedHashMap<>();
		cases.put("Error closing S3Client for us-west-2/bkt token AKIAQ7WERTY4UIOPASD8",
				"Error closing S3Client for us-west-2/bkt token <redacted>");
		cases.put("closing token AKIAQWERTYUIOPASDFGH: done", "closing token <redacted>: done");
		cases.put("key ASIAZ3XCVB7NMLKJ5HGF in use", "key <redacted> in use");
		cases.put("cut at AKIAQ7WER", "cut at <redacted>");
		cases.put("using secret Zq9XwLmPq7RtYvB3 for peer", "using secret <redacted> for peer");
		cases.put("Password  abc123def456ghi", "Password  <redacted>");
		cases.put("cut password abc12", "cut password <redacted>");
		cases.put("token refresh scheduled in 30 minutes", "token refresh scheduled in 30 minutes");
		cases.put("token expired 2026-10-16", "token expired 2026-10-16");
		cases.put("signature mismatch for device12345678", "signature mismatch for device12345678");
		cases.put("Located in ASIA region", "Located in ASIA region");
		cases.put("AKIAQ7WERTY4UIOPASD8X too long", "AKIAQ7WERTY4UIOPASD8X too long");
		cases.put("akiaq7werty4uiopasd8 lower", "akiaq7werty4uiopasd8 lower");

		for ( Map.Entry<String, String> c : cases.entrySet() ) {
			// WHEN
			Redactor redactor = new Redactor();
			String out = redactor.redact(c.getKey());

			// THEN
			then(out).as("Redacted [%s]", c.getKey()).isEqualTo(c.getValue());
			then(redactor.redact(out)).as("Idempotent [%s]", c.getKey()).isEqualTo(out);
		}
	}

	@Test
	public void bracesNetworkEndpointKept() {
		// GIVEN
		Map<String, String> cases = new LinkedHashMap<>();
		cases.put("from Modbus device 1@NiftyTcpModbusNetwork{192.168.20.33:502}: No route to host",
				"from Modbus device 1@NiftyTcpModbusNetwork{192.168.20.33:502}: No route to host");
		cases.put("device 2@NiftyTcpModbusNetwork{meter-1.local:1502}: timeout",
				"device 2@NiftyTcpModbusNetwork{meter-1.local:1502}: timeout");
		cases.put("device 3@NiftyJscSerialModbusNetwork{/dev/ttyUSB0 readTimeout=1000}: timeout",
				"device 3@NiftyJscSerialModbusNetwork{/dev/ttyUSB0 readTimeout=1000}: timeout");
		cases.put("device 4@NiftyTcpModbusNetwork{localhost:502}: refused",
				"device 4@NiftyTcpModbusNetwork{localhost:502}: refused");
		cases.put("map {pin:123456} saved", "map {…} saved");
		cases.put("map {pin=1234, host=10.0.0.1:502} saved", "map {…} saved");
		cases.put("map {10.0.0.1:502, password=abc} saved", "map {…} saved");
		cases.put("cut device 1@Net{192.168.20.33:50", "cut device 1@Net{…");

		for ( Map.Entry<String, String> c : cases.entrySet() ) {
			// WHEN
			Redactor redactor = new Redactor();
			String out = redactor.redact(c.getKey());

			// THEN
			then(out).as("Braces [%s]", c.getKey()).isEqualTo(c.getValue());
			then(redactor.redact(out)).as("Idempotent [%s]", c.getKey()).isEqualTo(out);
		}
	}

	@Test
	public void trimPartialToken() {
		then(Redactor.trimPartialToken("signature Zq9XwLmPq7RtYvB3nKcJdHf"))
				.as("Trailing token run removed").isEqualTo("signature ");
		then(Redactor.trimPartialToken("value [abc/def+g=")).as("Token characters removed")
				.isEqualTo("value [");
		then(Redactor.trimPartialToken("done. ")).as("Unchanged when no trailing run")
				.isEqualTo("done. ");
		then(Redactor.trimPartialToken("abcdef")).as("All token characters").isEmpty();
	}

	@Test
	public void count() {
		// GIVEN
		Redactor redactor = new Redactor();

		// WHEN
		String out = redactor.redact("password=abc, apiKey=def, user=bob");
		int first = redactor.count();
		String again = redactor.redact(out);
		redactor.redact("token=xyz");

		// THEN
		then(first).as("One count per masked value").isEqualTo(2);
		then(again).as("Idempotent").isEqualTo(out);
		then(redactor.count()).as("Re-redacting counts nothing; new value counted").isEqualTo(3);
	}

	@Test
	public void controlAndBidiStripped() {
		// GIVEN
		String msg = "line1\r\nline2\tcol\u0000\u0007\u001b[31m\u0085red\u202egnp.exe\u2066x\u2069\u200f!";

		// WHEN
		String out = new Redactor().redact(msg);

		// THEN
		then(out).as("Controls stripped, CR LF TAB to space")
				.isEqualTo("line1  line2 col[31mredgnp.exex!");
	}

	@Test
	public void longTokens() {
		// GIVEN
		Redactor redactor = new Redactor();

		// WHEN
		String camel = redactor.redact("ModbusDatumDataSourceConfiguration failed");
		String fqcn = redactor
				.redact("net.solarnetwork.node.datum.modbus.ModbusDatumDataSourceConfigurationService");
		String path = redactor.redact("missing /var/lib/solarnode/var/settings-bak/settings_20261016");
		String kv = redactor.redact("acknowledgedInstructionStateValue=Completed");
		int before = redactor.count();
		String b64 = redactor.redact("result H4sIAAAAAAAA/6VWW2/bNhT+K4Ke0Rk3Xq9Zp1L end");
		String hex = redactor.redact("key 0f1e2d3c4b5a69788796a5b4c3d2e1f0 end");
		String padded = redactor.redact("x YWJjZGVmZ2hpamtsbW5vcHFyc3R1dnd4eXo=");

		// THEN
		then(camel).as("CamelCase survives").isEqualTo("ModbusDatumDataSourceConfiguration failed");
		// @formatter:off
		then(fqcn).as("FQCN survives")
				.isEqualTo("net.solarnetwork.node.datum.modbus.ModbusDatumDataSourceConfigurationService");
		// @formatter:on
		then(path).as("Path segments survive")
				.isEqualTo("missing /var/lib/solarnode/var/settings-bak/settings_20261016");
		then(kv).as("Long key=value without digits survives")
				.isEqualTo("acknowledgedInstructionStateValue=Completed");
		then(before).as("Nothing counted").isZero();
		then(b64).as("Base64 masked").isEqualTo("result <tok> end");
		then(hex).as("Hex key masked").isEqualTo("key <tok> end");
		then(padded).as("Padded base64 masked").isEqualTo("x <tok>");
		then(redactor.count()).as("Tokens counted").isEqualTo(3);
	}

	@Test
	public void adversarialInputsAreFast() {
		// GIVEN
		// @formatter:off
		List<String> inputs = Arrays.asList(
				"a=".repeat(4000),
				"{".repeat(8000),
				"?".repeat(8000),
				"a".repeat(8000),
				"[".repeat(8000),
				"\"".repeat(8000),
				"=".repeat(8000),
				"&a=".repeat(2700),
				"{\"".repeat(4000),
				"password=".repeat(900),
				"password=a, ".repeat(700),
				"name=name=".repeat(800),
				"\"password\":".repeat(700),
				"http://".repeat(1150),
				"a:b@".repeat(2000),
				"://a:".repeat(1600),
				"Bearer ".repeat(1150),
				"Basic ".repeat(1350),
				"Authorization:".repeat(580),
				"-----BEGIN".repeat(800),
				"/a1".repeat(2700),
				"aB3+".repeat(2000),
				"a ".repeat(4000),
				"x, ".repeat(2700),
				"token ".repeat(1350),
				"token a1".repeat(1000),
				"AKIA".repeat(2000),
				"{1.2.3.4:5}".repeat(700),
				"{/dev/tty".repeat(900),
				"{a.b.c.d.e.f.g.h.i.j:".repeat(400)
				);
		// @formatter:on

		for ( String input : inputs ) {
			// warm up
			for ( int i = 0; i < 3; i++ ) {
				new Redactor().redact(input);
			}

			// WHEN
			long best = Long.MAX_VALUE;
			String out = null;
			for ( int i = 0; i < 3; i++ ) {
				long start = System.nanoTime();
				out = new Redactor().redact(input);
				best = Math.min(best, System.nanoTime() - start);
			}

			// THEN
			then(best / 1_000_000L).as("Redact %s... fast", input.substring(0, 12))
					.isLessThan(MAX_ADVERSARIAL_MS);
			then(new Redactor().redact(out)).as("Idempotent for %s...", input.substring(0, 12))
					.isEqualTo(out);
		}
	}

	@Test
	public void randomFragments_idempotentAndNoSecretLeak() {
		// GIVEN
		final String secret = "Fuzz-S3cret-Val";
		// @formatter:off
		final String[] fragments = new String[] {
				"password=", "token: ", "\"apiKey\":", "name=psk ", "value=", "http://", "u:", "@",
				"host:502", "/path", "?", "&", "a=", "{", "}", "[", "]", "\"", "'", ", ", "; ", " ",
				"Bearer ", "Basic ", "SNWS2 ", "Authorization: ", "-----BEGIN", "resultParameters=",
				"received with parameters:", "Unable to accept instruction JSON", "]: ",
				"java.lang.IllegalStateException", ": ", "<redacted>", "<tok>", "{…}", "…",
				"H4sIAAAAAAAA/6VWW2/bNhT+K4Ke0Rk3Xq9", "Zp1L", "ModbusDatumDataSourceConfiguration",
				"X-Amz-Signature=", "\n", "\t", "\u202e", "😀", "=", ":", "/", "#", "a", "1",
				};
		// @formatter:on
		Random rng = new Random(20261016L);
		List<String> failures = new ArrayList<>();

		// WHEN
		for ( int iter = 0; iter < 5000; iter++ ) {
			StringBuilder buf = new StringBuilder();
			int len = 1 + rng.nextInt(12);
			for ( int i = 0; i < len; i++ ) {
				buf.append(fragments[rng.nextInt(fragments.length)]);
			}
			String input = buf.toString();
			String out = new Redactor().redact(input);
			if ( !new Redactor().redact(out).equals(out) ) {
				failures.add("not idempotent: [" + input + "] -> [" + out + "]");
			}
			String withSecret = new Redactor().redact("password=" + secret + " " + input);
			if ( leak(withSecret, secret) != null ) {
				failures.add("leaked: [" + input + "] -> [" + withSecret + "]");
			}
		}

		// THEN
		then(failures).as("Random inputs").isEmpty();
	}

	@Test
	public void cut_codePoints() {
		// GIVEN
		String text = "ab😀cd";

		// WHEN
		String c2 = Redactor.cut(text, 2);
		String c3 = Redactor.cut(text, 3);
		String c5 = Redactor.cut(text, 5);
		String c9 = Redactor.cut(text, 9);
		String c0 = Redactor.cut(text, 0);

		// THEN
		then(c2).as("Cut before emoji").isEqualTo("ab");
		then(c3).as("Cut keeps whole emoji").isEqualTo("ab😀");
		then(c5).as("Exact length unchanged").isEqualTo(text);
		then(c9).as("Short text unchanged").isSameAs(text);
		then(c0).as("Zero length").isEmpty();
	}

}
