/* ==================================================================
 * DiagnosticsServiceTests.java - 16/10/2026 9:00:00 am
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
import static net.solarnetwork.node.reactor.InstructionHandler.PARAM_MESSAGE;
import static net.solarnetwork.node.reactor.InstructionHandler.PARAM_SERVICE;
import static net.solarnetwork.node.reactor.InstructionHandler.PARAM_SERVICE_RESULT;
import static net.solarnetwork.node.reactor.InstructionHandler.TOPIC_SYSTEM_CONFIGURATION;
import static org.assertj.core.api.BDDAssertions.catchThrowable;
import static org.assertj.core.api.BDDAssertions.then;
import static org.assertj.core.api.InstanceOfAssertFactories.LIST;
import static org.assertj.core.api.InstanceOfAssertFactories.LONG;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.zip.GZIPInputStream;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import net.solarnetwork.codec.JsonUtils;
import net.solarnetwork.domain.InstructionStatus.InstructionState;
import net.solarnetwork.node.reactor.BasicInstruction;
import net.solarnetwork.node.reactor.InstructionStatus;
import net.solarnetwork.node.system.diagnostics.DiagnosticOp;
import net.solarnetwork.node.system.diagnostics.DiagnosticResult;
import net.solarnetwork.node.system.diagnostics.DiagnosticsService;
import net.solarnetwork.node.system.diagnostics.OpContext;

/**
 * Test cases for the {@link DiagnosticsService} class.
 *
 * @author elijah
 * @version 1.0
 */
public class DiagnosticsServiceTests {

	private static final Instant NOW = Instant.parse("2026-10-16T01:02:03.456Z");
	private static final ZoneId ZONE = ZoneId.of("Pacific/Auckland");
	private static final String SECRET = "hunter2";

	/**
	 * The error code result parameter set by
	 * {@code createErrorResultParameters()}.
	 */
	private static final String ERROR_CODE_PARAM = "code";

	/** The body of a stub operation. */
	@FunctionalInterface
	private interface Body {

		DiagnosticResult run(OpContext ctx) throws Exception;

	}

	/** A configurable stub operation. */
	private static final class StubOp implements DiagnosticOp {

		private final String name;
		private final int version;
		private final RateClass rateClass;
		private final Set<String> paramNames;
		private final AtomicInteger runs = new AtomicInteger();
		private final AtomicInteger validations = new AtomicInteger();
		private final AtomicReference<OpContext> lastContext = new AtomicReference<>();
		private Body body = ctx -> new DiagnosticResult().put("ok", true);
		private Consumer<Map<String, String>> validator = p -> {
		};
		private final AtomicReference<Instant> validatedNow = new AtomicReference<>();
		private Map<String, Object> capabilities;
		private RuntimeException capabilitiesException;
		private Runnable capabilitiesHook;
		private RuntimeException rateClassException;

		private StubOp(String name, int version, RateClass rateClass, String... paramNames) {
			super();
			this.name = name;
			this.version = version;
			this.rateClass = rateClass;
			this.paramNames = Set.of(paramNames);
		}

		@Override
		public String name() {
			return name;
		}

		@Override
		public int version() {
			return version;
		}

		@Override
		public RateClass rateClass() {
			if ( rateClassException != null ) {
				throw rateClassException;
			}
			return rateClass;
		}

		@Override
		public Set<String> parameterNames() {
			return paramNames;
		}

		@Override
		public void validate(Map<String, String> params) {
			validations.incrementAndGet();
			validator.accept(params);
		}

		@Override
		public void validate(Map<String, String> params, Instant now) {
			validatedNow.set(now);
			DiagnosticOp.super.validate(params, now);
		}

		@Override
		public DiagnosticResult run(OpContext ctx) throws Exception {
			runs.incrementAndGet();
			lastContext.set(ctx);
			return body.run(ctx);
		}

		@Override
		public Map<String, Object> capabilities(OpContext ctx) {
			if ( capabilitiesHook != null ) {
				capabilitiesHook.run();
			}
			if ( capabilitiesException != null ) {
				throw capabilitiesException;
			}
			return capabilities;
		}

	}

	private AtomicLong nanos;
	private CountDownLatch release;
	private StubOp systemOp;
	private StubOp logsOp;
	private StubOp storageOp;
	private DiagnosticsService service;

	@Before
	public void setup() {
		nanos = new AtomicLong(1_000_000_000L);
		release = new CountDownLatch(1);
		systemOp = new StubOp("system", 1, DiagnosticOp.RateClass.LIGHT);
		logsOp = new StubOp("logs", 2, DiagnosticOp.RateClass.SCAN, "limit", "source");
		logsOp.validator = p -> {
			String limit = p.get("limit");
			if ( limit != null && !limit.matches("[0-9]{1,2}") ) {
				throw new IllegalArgumentException("Invalid limit parameter.");
			}
		};
		storageOp = new StubOp("storage", 3, DiagnosticOp.RateClass.LIGHT);
		service = new DiagnosticsService(Arrays.asList(systemOp, logsOp, storageOp),
				Clock.fixed(NOW, ZoneOffset.UTC), nanos::get, ZONE);
	}

	@After
	public void teardown() {
		release.countDown();
		service.shutdown();
	}

	private static BasicInstruction instruction(Instant date, String... params) {
		BasicInstruction instr = new BasicInstruction(123L, TOPIC_SYSTEM_CONFIGURATION, date, "test",
				null);
		instr.addParameter(PARAM_SERVICE, DiagnosticsService.DIAGNOSTICS_SERVICE_UID);
		for ( int i = 0; i + 1 < params.length; i += 2 ) {
			instr.addParameter(params[i], params[i + 1]);
		}
		return instr;
	}

	private static BasicInstruction instruction(String... params) {
		return instruction(NOW.minusSeconds(5), params);
	}

	private static Map<String, Object> decode(String encoded) throws IOException {
		byte[] gz = Base64.getDecoder().decode(encoded);
		try (InputStream in = new GZIPInputStream(new ByteArrayInputStream(gz))) {
			return JsonUtils.getStringMap(new String(in.readAllBytes(), UTF_8));
		}
	}

	private static void thenDeclined(InstructionStatus status, String code) {
		// @formatter:off
		then(status)
			.as("Status returned %s", status == null ? null : status.getResultParameters())
			.isNotNull()
			.returns(InstructionState.Declined, InstructionStatus::getInstructionState)
			;
		then(params(status))
			.as("Error code")
			.containsEntry(ERROR_CODE_PARAM, code)
			;
		// @formatter:on
	}

	@SuppressWarnings("unchecked")
	private static Map<String, Object> params(InstructionStatus status) {
		return (Map<String, Object>) status.getResultParameters();
	}

	private static String message(InstructionStatus status) {
		return String.valueOf(status.getResultParameters().get(PARAM_MESSAGE));
	}

	private static Map<String, Object> thenCompleted(InstructionStatus status) throws IOException {
		// @formatter:off
		then(status)
			.as("Status returned %s", status == null ? null : status.getResultParameters())
			.isNotNull()
			.returns(InstructionState.Completed, InstructionStatus::getInstructionState)
			;
		then(status.getResultParameters())
			.as("Only result parameter")
			.containsOnlyKeys(PARAM_SERVICE_RESULT)
			;
		// @formatter:on
		Object result = status.getResultParameters().get(PARAM_SERVICE_RESULT);
		then(result).isInstanceOf(String.class);
		then(((String) result).length()).as("Within budget")
				.isLessThanOrEqualTo(DiagnosticsService.RESULT_BUDGET);
		return decode((String) result);
	}

	@SuppressWarnings("unchecked")
	private static Map<String, Object> map(Object o) {
		then(o).isInstanceOf(Map.class);
		return (Map<String, Object>) o;
	}

	@Test
	public void handlesTopic() {
		then(service.handlesTopic(TOPIC_SYSTEM_CONFIGURATION)).isTrue();
		then(service.handlesTopic("SystemConfigure")).isFalse();
		then(service.handlesTopic("SetControlParameter")).isFalse();
		then(service.handlesTopic(null)).isFalse();
	}

	@Test
	public void nullInstruction() {
		then(service.processInstruction(null)).isNull();
	}

	@Test
	public void topicMismatch() {
		// GIVEN
		BasicInstruction instr = new BasicInstruction(1L, "SetControlParameter", NOW, "test", null);
		instr.addParameter(PARAM_SERVICE, DiagnosticsService.DIAGNOSTICS_SERVICE_UID);
		instr.addParameter("op", "system");

		// WHEN
		InstructionStatus status = service.processInstruction(instr);

		// THEN
		then(status).isNull();
		then(systemOp.runs).hasValue(0);
		then(systemOp.validations).hasValue(0);
	}

	@Test
	public void serviceMismatch() {
		// GIVEN
		BasicInstruction instr = new BasicInstruction(1L, TOPIC_SYSTEM_CONFIGURATION, NOW, "test", null);
		instr.addParameter(PARAM_SERVICE, "net.solarnetwork.node.packages");
		instr.addParameter("op", "system");
		BasicInstruction noService = new BasicInstruction(1L, TOPIC_SYSTEM_CONFIGURATION, NOW, "test",
				null);
		noService.addParameter("op", "system");

		// WHEN
		InstructionStatus status = service.processInstruction(instr);
		InstructionStatus status2 = service.processInstruction(noService);

		// THEN
		then(status).isNull();
		then(status2).isNull();
		then(systemOp.runs).hasValue(0);
	}

	@Test
	public void success() throws Exception {
		// GIVEN
		systemOp.body = ctx -> new DiagnosticResult().put("kernel", "6.1.0").put("cpus", 4)
				.notAvailable("memory", DiagnosticResult.Reason.ABSENT);

		// WHEN
		InstructionStatus status = service.processInstruction(instruction("op", "system", "v", "1"));

		// THEN
		Map<String, Object> env = thenCompleted(status);
		// @formatter:off
		then(env)
			.as("Envelope")
			.containsEntry("v", 1)
			.containsEntry("op", "system")
			.containsEntry("opV", 1)
			.containsEntry("plugin", "dev")
			.containsKeys("tookMs", "truncated", "redacted")
			;
		then(map(env.get("node")))
			.as("Node info")
			.containsEntry("nowMs", NOW.toEpochMilli())
			.containsEntry("zone", ZONE.getId())
			;
		then(map(env.get("data")))
			.as("Data")
			.containsEntry("kernel", "6.1.0")
			.containsEntry("cpus", 4)
			;
		then(env.get("notAvailable"))
			.asInstanceOf(LIST)
			.hasSize(1)
			;
		// @formatter:on
		then(systemOp.runs).hasValue(1);
	}

	@Test
	public void success_context() throws Exception {
		// GIVEN
		service.setMaxScanBytes(1234);

		// WHEN
		InstructionStatus status = service
				.processInstruction(instruction("op", "logs", "limit", "5", "compress", "true"));
		InstructionStatus status2 = service.processInstruction(instruction("op", "system"));

		// THEN
		thenCompleted(status);
		thenCompleted(status2);
		OpContext ctx = logsOp.lastContext.get();
		// @formatter:off
		then(ctx.getParams())
			.as("Only op params passed to op")
			.containsOnly(Map.entry("limit", "5"))
			;
		// @formatter:on
		then(ctx.getNow()).isEqualTo(NOW);
		then(ctx.getZone()).isEqualTo(ZONE);
		then(ctx.getMaxScanBytes()).isEqualTo(1234L);
		then(ctx.getDeadline().remainingMs()).as("SCAN soft deadline is logs hard - 500ms")
				.isEqualTo(DiagnosticsService.DEFAULT_LOGS_DEADLINE_MS - 500);
		then(systemOp.lastContext.get().getDeadline().remainingMs())
				.as("LIGHT soft deadline is op hard - 500ms")
				.isEqualTo(DiagnosticsService.DEFAULT_OP_DEADLINE_MS - 500);
		then(ctx.getRedactor()).as("New redactor per instruction")
				.isNotSameAs(systemOp.lastContext.get().getRedactor());
	}

	@Test
	public void success_largeResult() throws Exception {
		// GIVEN
		systemOp.body = ctx -> {
			List<Object> items = new ArrayList<>();
			java.util.Random rng = new java.util.Random(1);
			for ( int i = 0; i < 3000; i++ ) {
				items.add(Long.toString(rng.nextLong(), 36) + Long.toString(rng.nextLong(), 36));
			}
			return new DiagnosticResult().put("items", items);
		};

		// WHEN
		InstructionStatus status = service.processInstruction(instruction("op", "system"));

		// THEN
		Map<String, Object> env = thenCompleted(status);
		then(env.get("truncated")).asInstanceOf(LIST).as("Truncation recorded").isNotEmpty();
	}

	@Test
	public void unknownOp() {
		// WHEN
		InstructionStatus status = service.processInstruction(instruction("op", "shell"));

		// THEN
		thenDeclined(status, DiagnosticsService.ERROR_UNKNOWN_OP);
		then(message(status)).doesNotContain("shell");
	}

	@Test
	public void missingOp() {
		// WHEN
		InstructionStatus status = service.processInstruction(instruction());

		// THEN
		thenDeclined(status, DiagnosticsService.ERROR_UNKNOWN_OP);
	}

	@Test
	public void unsupportedVersion() {
		// WHEN
		InstructionStatus status = service.processInstruction(instruction("op", "system", "v", "2"));

		// THEN
		thenDeclined(status, DiagnosticsService.ERROR_UNKNOWN_OP);
		then(systemOp.runs).hasValue(0);
	}

	@Test
	public void unknownParameters() {
		// WHEN
		InstructionStatus status = service.processInstruction(
				instruction("op", "system", "foo", "1", "bar", SECRET, "a b\u0000c", "2"));

		// THEN
		thenDeclined(status, DiagnosticsService.ERROR_INVALID_PARAMETER);
		then(message(status)).isEqualTo("Unknown parameter(s): a_b_c, bar, foo");
		then(systemOp.validations).hasValue(0);
		then(systemOp.runs).hasValue(0);
	}

	@Test
	public void unknownParameters_capped() {
		// GIVEN
		String longName = "x".repeat(100);
		BasicInstruction instr = instruction("op", "system", longName, "1");
		for ( int i = 0; i < 10; i++ ) {
			instr.addParameter("p" + i, "1");
		}

		// WHEN
		InstructionStatus status = service.processInstruction(instr);

		// THEN
		thenDeclined(status, DiagnosticsService.ERROR_INVALID_PARAMETER);
		then(message(status)).isEqualTo("Unknown parameter(s): p0, p1, p2, p3, p4, ...");
	}

	@Test
	public void unknownParameters_cutName() {
		// GIVEN
		String longName = "x".repeat(100);

		// WHEN
		InstructionStatus status = service
				.processInstruction(instruction("op", "system", longName, "1"));

		// THEN
		thenDeclined(status, DiagnosticsService.ERROR_INVALID_PARAMETER);
		then(message(status)).isEqualTo("Unknown parameter(s): " + "x".repeat(32));
	}

	@Test
	public void opParameterNotAllowedForOtherOp() {
		// WHEN
		InstructionStatus status = service.processInstruction(instruction("op", "system", "limit", "5"));

		// THEN
		thenDeclined(status, DiagnosticsService.ERROR_INVALID_PARAMETER);
		then(message(status)).isEqualTo("Unknown parameter(s): limit");
	}

	@Test
	public void multiValuedParameter() {
		// GIVEN
		BasicInstruction instr = instruction("op", "logs", "limit", "5");
		instr.addParameter("limit", "6");

		// WHEN
		InstructionStatus status = service.processInstruction(instr);

		// THEN
		thenDeclined(status, DiagnosticsService.ERROR_INVALID_PARAMETER);
		then(message(status)).contains("limit").doesNotContain("5").doesNotContain("6");
		then(logsOp.runs).hasValue(0);
	}

	@Test
	public void multiValuedCommonParameter() {
		// GIVEN
		BasicInstruction instr = instruction("op", "system");
		instr.addParameter("op", "system");

		// WHEN
		InstructionStatus status = service.processInstruction(instr);

		// THEN
		thenDeclined(status, DiagnosticsService.ERROR_INVALID_PARAMETER);
	}

	@Test
	public void controlCharacterValue() {
		// WHEN
		InstructionStatus status = service
				.processInstruction(instruction("op", "logs", "source", "a\nb"));
		InstructionStatus status2 = service
				.processInstruction(instruction("op", "logs", "source", "café"));

		// THEN
		thenDeclined(status, DiagnosticsService.ERROR_INVALID_PARAMETER);
		then(message(status)).isEqualTo("Invalid value for parameter source.");
		thenDeclined(status2, DiagnosticsService.ERROR_INVALID_PARAMETER);
		then(logsOp.validations).hasValue(0);
	}

	@Test
	public void tooLongValue() {
		// WHEN
		InstructionStatus status = service
				.processInstruction(instruction("op", "logs", "source", "a".repeat(257)));
		InstructionStatus ok = service
				.processInstruction(instruction("op", "logs", "source", "a".repeat(256)));

		// THEN
		thenDeclined(status, DiagnosticsService.ERROR_INVALID_PARAMETER);
		then(ok.getInstructionState()).isEqualTo(InstructionState.Completed);
	}

	@Test
	public void validationFailure_noRateToken() throws Exception {
		// GIVEN
		for ( int i = 0; i < 10; i++ ) {
			InstructionStatus status = service
					.processInstruction(instruction("op", "logs", "limit", "abc"));
			thenDeclined(status, DiagnosticsService.ERROR_INVALID_PARAMETER);
			then(message(status)).isEqualTo("Invalid limit parameter.");
		}

		// WHEN
		InstructionStatus ok1 = service.processInstruction(instruction("op", "logs"));
		InstructionStatus ok2 = service.processInstruction(instruction("op", "logs"));
		InstructionStatus limited = service.processInstruction(instruction("op", "logs"));

		// THEN
		thenCompleted(ok1);
		thenCompleted(ok2);
		thenDeclined(limited, DiagnosticsService.ERROR_RATE_LIMITED);
		then(logsOp.runs).hasValue(2);
	}

	@Test
	public void validation_currentTime() throws Exception {
		// WHEN
		InstructionStatus status = service.processInstruction(instruction("op", "logs"));

		// THEN
		thenCompleted(status);
		then(logsOp.validatedNow).as("Validated with the current time").hasValue(NOW);
		then(logsOp.lastContext.get().getNow()).as("Same time provided to run").isEqualTo(NOW);
	}

	@Test
	public void runInvalidParameter_rateTokenReturned() throws Exception {
		// GIVEN
		logsOp.body = ctx -> {
			if ( ctx.getParams().containsKey("source") ) {
				throw new IllegalArgumentException("Invalid parameter: source");
			}
			return new DiagnosticResult().put("ok", true);
		};
		for ( int i = 0; i < 5; i++ ) {
			InstructionStatus status = service
					.processInstruction(instruction("op", "logs", "source", "x"));
			thenDeclined(status, DiagnosticsService.ERROR_INVALID_PARAMETER);
			then(message(status)).isEqualTo("Invalid parameter: source.");
		}

		// WHEN
		InstructionStatus ok1 = service.processInstruction(instruction("op", "logs"));
		InstructionStatus ok2 = service.processInstruction(instruction("op", "logs"));
		InstructionStatus limited = service.processInstruction(instruction("op", "logs"));

		// THEN
		thenCompleted(ok1);
		thenCompleted(ok2);
		thenDeclined(limited, DiagnosticsService.ERROR_RATE_LIMITED);
	}

	@Test
	public void validationFailure_messageSanitised() {
		// GIVEN
		logsOp.validator = p -> {
			throw new IllegalArgumentException("Bad\u0000 limit\u202e " + "z".repeat(200));
		};

		// WHEN
		InstructionStatus status = service.processInstruction(instruction("op", "logs", "limit", "1"));

		// THEN
		thenDeclined(status, DiagnosticsService.ERROR_INVALID_PARAMETER);
		then(message(status)).startsWith("Bad limit z").hasSize(100);
	}

	@Test
	public void rateLimited() {
		// GIVEN
		for ( int i = 0; i < 5; i++ ) {
			then(service.processInstruction(instruction("op", "system")).getInstructionState())
					.isEqualTo(InstructionState.Completed);
		}

		// WHEN
		InstructionStatus status = service.processInstruction(instruction("op", "system"));

		// THEN
		thenDeclined(status, DiagnosticsService.ERROR_RATE_LIMITED);
		// @formatter:off
		then(status.getResultParameters().get(DiagnosticsService.PARAM_RETRY_AFTER))
			.as("Retry after seconds")
			.isInstanceOf(Long.class)
			.asInstanceOf(LONG)
			.isBetween(59L, 61L)
			;
		// @formatter:on
		then(systemOp.runs).hasValue(5);
	}

	@Test
	public void wallTimeCharged() {
		// GIVEN
		systemOp.body = ctx -> {
			nanos.addAndGet(TimeUnit.SECONDS.toNanos(61));
			return new DiagnosticResult();
		};
		then(service.processInstruction(instruction("op", "system")).getInstructionState())
				.isEqualTo(InstructionState.Completed);

		// WHEN
		InstructionStatus status = service.processInstruction(instruction("op", "storage"));

		// THEN
		thenDeclined(status, DiagnosticsService.ERROR_RATE_LIMITED);
		then(storageOp.runs).hasValue(0);
	}

	@Test
	public void deadlineExceeded() {
		// GIVEN
		service.setOpDeadlineMs(200);
		systemOp.body = ctx -> {
			Thread.sleep(5000);
			return new DiagnosticResult();
		};

		// WHEN
		long start = System.nanoTime();
		InstructionStatus status = service.processInstruction(instruction("op", "system"));
		long tookMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

		// THEN
		thenDeclined(status, DiagnosticsService.ERROR_DEADLINE_EXCEEDED);
		then(tookMs).as("Returned near hard deadline").isLessThan(600L);
	}

	@Test
	public void opDisabled() throws Exception {
		// GIVEN
		service.setDisabledOps("system, logs");

		// WHEN
		InstructionStatus status = service.processInstruction(instruction("op", "system"));
		InstructionStatus status2 = service.processInstruction(instruction("op", "logs"));
		InstructionStatus storage = service.processInstruction(instruction("op", "storage"));

		// THEN
		thenDeclined(status, DiagnosticsService.ERROR_OP_DISABLED);
		thenDeclined(status2, DiagnosticsService.ERROR_OP_DISABLED);
		thenCompleted(storage);
		then(systemOp.runs).hasValue(0);
		then(service.getDisabledOps()).isEqualTo("logs,system");

		// WHEN
		service.setDisabledOps(null);

		// THEN
		thenCompleted(service.processInstruction(instruction("op", "system")));
	}

	@Test
	public void staleInstruction() throws Exception {
		// GIVEN
		Instant old = NOW.minus(Duration.ofMinutes(16));

		// WHEN
		InstructionStatus status = service.processInstruction(instruction(old, "op", "system"));
		InstructionStatus ok = service
				.processInstruction(instruction(NOW.minus(Duration.ofMinutes(14)), "op", "system"));

		// THEN
		thenDeclined(status, DiagnosticsService.ERROR_STALE_INSTRUCTION);
		// @formatter:off
		then(message(status))
			.contains("nodeNowMs=" + NOW.toEpochMilli())
			.contains("instructionMs=" + old.toEpochMilli())
			;
		// @formatter:on
		thenCompleted(ok);
		then(systemOp.runs).hasValue(1);
	}

	@Test
	public void opBusy() throws Exception {
		// GIVEN
		service.setOpDeadlineMs(200);
		systemOp.body = ctx -> {
			while ( true ) {
				try {
					if ( release.await(5, TimeUnit.SECONDS) ) {
						return new DiagnosticResult();
					}
				} catch ( InterruptedException e ) {
					// ignore, simulating uninterruptible I/O
				}
			}
		};

		// WHEN
		InstructionStatus timeout = service.processInstruction(instruction("op", "system"));
		InstructionStatus busy = service.processInstruction(instruction("op", "system"));
		InstructionStatus other = service.processInstruction(instruction("op", "storage"));
		InstructionStatus caps = service.processInstruction(instruction("op", "capabilities"));

		// THEN
		thenDeclined(timeout, DiagnosticsService.ERROR_DEADLINE_EXCEEDED);
		thenDeclined(busy, DiagnosticsService.ERROR_OP_BUSY);
		thenCompleted(other);
		Map<String, Object> data = map(thenCompleted(caps).get("data"));
		// @formatter:off
		then(data.get("stuckOps"))
			.asInstanceOf(LIST)
			.hasSize(1)
			.element(0)
			.satisfies(m -> {
				then(map(m)).containsEntry("op", "system").containsEntry("sinceMs", NOW.toEpochMilli());
			})
			;
		// @formatter:on
		then(systemOp.runs).hasValue(1);
	}

	@Test
	public void opBusy_noRateToken() throws Exception {
		// GIVEN
		service.setOpDeadlineMs(200);
		systemOp.body = ctx -> {
			while ( true ) {
				try {
					if ( release.await(5, TimeUnit.SECONDS) ) {
						return new DiagnosticResult();
					}
				} catch ( InterruptedException e ) {
					// ignore, simulating uninterruptible I/O
				}
			}
		};
		thenDeclined(service.processInstruction(instruction("op", "system")),
				DiagnosticsService.ERROR_DEADLINE_EXCEEDED);

		// WHEN
		for ( int i = 0; i < 10; i++ ) {
			thenDeclined(service.processInstruction(instruction("op", "system")),
					DiagnosticsService.ERROR_OP_BUSY);
		}
		List<InstructionStatus> others = new ArrayList<>(4);
		for ( int i = 0; i < 4; i++ ) {
			others.add(service.processInstruction(instruction("op", "storage")));
		}

		// THEN
		for ( InstructionStatus other : others ) {
			thenCompleted(other);
		}
		then(systemOp.runs).hasValue(1);
	}

	@Test
	public void capabilities_hungOpCapabilities() throws Exception {
		// GIVEN
		service.setOpDeadlineMs(300);
		logsOp.capabilitiesHook = () -> {
			while ( true ) {
				try {
					if ( release.await(5, TimeUnit.SECONDS) ) {
						return;
					}
				} catch ( InterruptedException e ) {
					// ignore, simulating uninterruptible I/O
				}
			}
		};
		storageOp.capabilities = Map.of("x", 1);
		systemOp.capabilities = Map.of("y", 2);

		// WHEN
		long start = System.nanoTime();
		InstructionStatus caps = service.processInstruction(instruction("op", "capabilities"));
		long tookMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
		InstructionStatus logs = service.processInstruction(instruction("op", "logs"));
		InstructionStatus caps2 = service.processInstruction(instruction("op", "capabilities"));

		// THEN
		then(tookMs).as("Returned near hard deadline").isLessThan(1000L);
		Map<String, Object> env = thenCompleted(caps);
		Map<String, Object> data = map(env.get("data"));
		// @formatter:off
		then(data)
			.as("Other op capabilities collected")
			.containsEntry("storage", Map.of("x", 1))
			.containsEntry("system", Map.of("y", 2))
			.doesNotContainKey("logs")
			;
		then(data.get("stuckOps"))
			.asInstanceOf(LIST)
			.as("Hung capabilities reported as stuck")
			.hasSize(1)
			.element(0)
			.satisfies(m -> then(map(m)).containsEntry("op", "logs"))
			;
		then(env.get("notAvailable"))
			.asInstanceOf(LIST)
			.containsExactly(Map.of("item", "logs", "reason", "timeout"))
			;
		// @formatter:on
		thenDeclined(logs, DiagnosticsService.ERROR_OP_BUSY);
		Map<String, Object> env2 = thenCompleted(caps2);
		// @formatter:off
		then(env2.get("notAvailable"))
			.asInstanceOf(LIST)
			.as("Busy op capabilities not available")
			.containsExactly(Map.of("item", "logs", "reason", "timeout"))
			;
		then(map(map(map(env2.get("data")).get("rate")).get("scan")))
			.as("Busy logs request used no scan token")
			.containsEntry("available", 2)
			;
		// @formatter:on
		then(logsOp.runs).hasValue(0);
	}

	@Test
	public void internalError_noSecret() {
		// GIVEN
		systemOp.body = ctx -> {
			throw new RuntimeException("password=" + SECRET);
		};

		// WHEN
		InstructionStatus status = service.processInstruction(instruction("op", "system"));

		// THEN
		thenDeclined(status, DiagnosticsService.ERROR_INTERNAL);
		then(message(status)).isEqualTo("Internal error (RuntimeException)");
		then(status.getResultParameters().toString()).doesNotContain(SECRET);
	}

	@Test
	public void internalError_outOfMemory() {
		// GIVEN
		systemOp.body = ctx -> {
			throw new OutOfMemoryError(SECRET);
		};

		// WHEN
		InstructionStatus status = service.processInstruction(instruction("op", "system"));

		// THEN
		thenDeclined(status, DiagnosticsService.ERROR_INTERNAL);
		then(message(status)).isEqualTo("Internal error (OutOfMemoryError)");
	}

	@Test
	public void internalError_nullResult() {
		// GIVEN
		systemOp.body = ctx -> null;

		// WHEN
		InstructionStatus status = service.processInstruction(instruction("op", "system"));

		// THEN
		thenDeclined(status, DiagnosticsService.ERROR_INTERNAL);
	}

	@Test
	public void internalError_validate() {
		// GIVEN
		logsOp.validator = p -> {
			throw new IllegalStateException("secret=" + SECRET);
		};

		// WHEN
		InstructionStatus status = service.processInstruction(instruction("op", "logs"));

		// THEN
		thenDeclined(status, DiagnosticsService.ERROR_INTERNAL);
		then(message(status)).isEqualTo("Internal error (IllegalStateException)");
		then(status.getResultParameters().toString()).doesNotContain(SECRET);
	}

	@Test
	public void internalError_validateError() {
		// GIVEN
		logsOp.validator = p -> {
			throw new StackOverflowError();
		};

		// WHEN
		InstructionStatus status = service.processInstruction(instruction("op", "logs"));

		// THEN
		thenDeclined(status, DiagnosticsService.ERROR_INTERNAL);
		then(message(status)).isEqualTo("Internal error (StackOverflowError)");
	}

	@Test
	public void internalError_parse() {
		// GIVEN
		BasicInstruction instr = new BasicInstruction(1L, TOPIC_SYSTEM_CONFIGURATION, NOW, "test",
				null) {

			private static final long serialVersionUID = 1L;

			@Override
			public Iterable<String> getParameterNames() {
				throw new IllegalStateException(SECRET);
			}

		};
		instr.addParameter(PARAM_SERVICE, DiagnosticsService.DIAGNOSTICS_SERVICE_UID);
		instr.addParameter("op", "system");

		// WHEN
		InstructionStatus status = service.processInstruction(instr);

		// THEN
		thenDeclined(status, DiagnosticsService.ERROR_INTERNAL);
		then(systemOp.runs).hasValue(0);
	}

	@Test
	public void internalError_limiter() {
		// GIVEN
		systemOp.rateClassException = new IllegalStateException(SECRET);

		// WHEN
		Throwable t = catchThrowable(() -> {
			InstructionStatus status = service.processInstruction(instruction("op", "system"));
			thenDeclined(status, DiagnosticsService.ERROR_INTERNAL);
		});

		// THEN
		then(t).isNull();
		then(systemOp.runs).hasValue(0);
	}

	@Test
	public void internalError_encoder() {
		// GIVEN
		systemOp.body = ctx -> {
			Map<String, Object> deep = new LinkedHashMap<>();
			Map<String, Object> m = deep;
			for ( int i = 0; i < 100; i++ ) {
				Map<String, Object> next = new LinkedHashMap<>();
				m.put("n", next);
				m = next;
			}
			return new DiagnosticResult().put("deep", deep);
		};

		// WHEN
		InstructionStatus status = service.processInstruction(instruction("op", "system"));

		// THEN
		thenDeclined(status, DiagnosticsService.ERROR_INTERNAL);
		then(message(status)).isEqualTo("Internal error (IllegalStateException)");
	}

	@Test
	public void capabilities() throws Exception {
		// GIVEN
		logsOp.capabilities = Map.of("sources", List.of("a", "b"));
		storageOp.capabilitiesException = new IllegalStateException(SECRET);
		service.setDisabledOps("system,zzz");
		service.setLightRatePerHour(30);
		service.processInstruction(instruction("op", "logs"));

		// WHEN
		InstructionStatus status = service
				.processInstruction(instruction("op", "capabilities", "v", "1"));

		// THEN
		Map<String, Object> env = thenCompleted(status);
		then(env).containsEntry("v", 1).containsEntry("op", "capabilities").containsEntry("opV", 1);
		Map<String, Object> data = map(env.get("data"));
		// @formatter:off
		then(data)
			.as("Capabilities data")
			.containsEntry("api", 1)
			.containsEntry("ops", Map.of("logs", 2, "storage", 3, "system", 1))
			.containsEntry("disabledOps", List.of("system", "zzz"))
			.containsEntry("stuckOps", List.of())
			.containsEntry("logs", Map.of("sources", List.of("a", "b")))
			.doesNotContainKeys("storage", "system")
			;
		then(map(data.get("limits")))
			.as("Limits")
			.containsEntry("resultBudget", 7000)
			.containsEntry("opDeadlineMs", 5000)
			.containsEntry("logsDeadlineMs", 10000)
			.containsEntry("maxScanBytes", 8 * 1024 * 1024)
			.containsEntry("maxInstructionAgeMin", 15)
			.containsEntry("maxWindowDays", 14)
			.containsEntry("maxLimit", 30)
			;
		Map<String, Object> rate = map(data.get("rate"));
		then(map(rate.get("light")))
			.as("Light rate")
			.containsEntry("perHour", 30)
			.containsEntry("available", 5)
			;
		then(map(rate.get("scan")))
			.as("Scan rate reflects consumed token")
			.containsEntry("perHour", 20)
			.containsEntry("available", 1)
			;
		// @formatter:on
		then(status.getResultParameters().toString()).doesNotContain(SECRET);
		then(systemOp.runs).hasValue(0);
	}

	@Test
	public void capabilities_rateLimited() {
		// GIVEN
		for ( int i = 0; i < 10; i++ ) {
			then(service.processInstruction(instruction("op", "capabilities")).getInstructionState())
					.isEqualTo(InstructionState.Completed);
		}

		// WHEN
		InstructionStatus status = service.processInstruction(instruction("op", "capabilities"));
		InstructionStatus system = service.processInstruction(instruction("op", "system"));

		// THEN
		thenDeclined(status, DiagnosticsService.ERROR_RATE_LIMITED);
		then(system.getInstructionState()).as("Separate bucket").isEqualTo(InstructionState.Completed);
	}

	@Test
	public void constructor_duplicateName() {
		// WHEN
		Throwable t = catchThrowable(() -> new DiagnosticsService(
				Arrays.asList(systemOp, new StubOp("system", 1, DiagnosticOp.RateClass.LIGHT))));
		Throwable t2 = catchThrowable(() -> new DiagnosticsService(
				Arrays.asList(new StubOp("capabilities", 1, DiagnosticOp.RateClass.NONE))));

		// THEN
		then(t).isInstanceOf(IllegalArgumentException.class);
		then(t2).isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	public void settings() {
		// WHEN
		service.setOpDeadlineMs(1);
		service.setLogsDeadlineMs(20_000);
		service.setMaxInstructionAgeMin(0);
		service.setMaxScanBytes(-1);
		service.setScanRatePerHour(5);

		// THEN
		then(service.getOpDeadlineMs()).isEqualTo(200L);
		then(service.getLogsDeadlineMs()).isEqualTo(20_000L);
		then(service.getMaxInstructionAgeMin()).isEqualTo(1);
		then(service.getMaxScanBytes()).isEqualTo(1L);
		then(service.getScanRatePerHour()).isEqualTo(5);
		then(service.getLightRatePerHour()).isEqualTo(60);
	}

}
