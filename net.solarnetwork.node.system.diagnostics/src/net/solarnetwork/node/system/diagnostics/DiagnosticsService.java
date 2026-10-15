/* ==================================================================
 * DiagnosticsService.java - 16/10/2026 9:00:00 am
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

package net.solarnetwork.node.system.diagnostics;

import static java.util.Collections.singletonMap;
import static net.solarnetwork.domain.InstructionStatus.InstructionState.Completed;
import static net.solarnetwork.domain.InstructionStatus.InstructionState.Declined;
import static net.solarnetwork.node.reactor.InstructionUtils.createErrorResultParameters;
import static net.solarnetwork.node.reactor.InstructionUtils.createStatus;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import org.osgi.framework.Bundle;
import org.osgi.framework.FrameworkUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import net.solarnetwork.domain.InstructionStatus.InstructionState;
import net.solarnetwork.node.reactor.Instruction;
import net.solarnetwork.node.reactor.InstructionHandler;
import net.solarnetwork.node.reactor.InstructionStatus;
import net.solarnetwork.node.system.diagnostics.DiagnosticOp.RateClass;
import net.solarnetwork.util.StringUtils;

/**
 * Read-only node diagnostics service.
 *
 * <p>
 * This {@link InstructionHandler} responds to
 * {@link InstructionHandler#TOPIC_SYSTEM_CONFIGURATION SystemConfiguration}
 * requests where the {@link InstructionHandler#PARAM_SERVICE service} parameter
 * value is {@link #DIAGNOSTICS_SERVICE_UID}. The {@link #PARAM_OP op} parameter
 * names the {@link DiagnosticOp} to run. The result is a Base64-encoded gzip
 * compressed JSON envelope on the
 * {@link InstructionHandler#PARAM_SERVICE_RESULT result} result parameter.
 * </p>
 *
 * <p>
 * Every failure, including any {@link Throwable} thrown while handling the
 * instruction, results in a {@code Declined} status with one of the
 * {@code NDS.*} error codes defined in this class.
 * </p>
 *
 * @author elijah
 * @version 1.0
 */
public class DiagnosticsService implements InstructionHandler {

	/** The service UID, used as the {@code service} instruction parameter. */
	public static final String DIAGNOSTICS_SERVICE_UID = "net.solarnetwork.node.diagnostics";

	/** The instruction parameter for the operation name. */
	public static final String PARAM_OP = "op";

	/** The instruction parameter for the API version. */
	public static final String PARAM_VERSION = "v";

	/** The instruction parameter for compression, accepted and ignored. */
	public static final String PARAM_COMPRESS = "compress";

	/**
	 * The result parameter for the number of seconds to wait after a rate
	 * limit.
	 */
	public static final String PARAM_RETRY_AFTER = "retryAfterSec";

	/** The API version. */
	public static final int API_VERSION = 1;

	/** The maximum encoded result length. */
	public static final int RESULT_BUDGET = ResultEncoder.DEFAULT_BUDGET;

	/** The built-in capabilities operation name. */
	public static final String CAPABILITIES_OP = "capabilities";

	/** The capabilities operation version. */
	public static final int CAPABILITIES_VERSION = 1;

	/** Error code for an unknown operation or unsupported API version. */
	public static final String ERROR_UNKNOWN_OP = "NDS.0001";

	/** Error code for an invalid parameter. */
	public static final String ERROR_INVALID_PARAMETER = "NDS.0002";

	/** Error code for a rate-limited request. */
	public static final String ERROR_RATE_LIMITED = "NDS.0003";

	/** Error code for an operation that exceeded its deadline. */
	public static final String ERROR_DEADLINE_EXCEEDED = "NDS.0004";

	/** Error code for a disabled operation. */
	public static final String ERROR_OP_DISABLED = "NDS.0005";

	/** Error code for a stale instruction. */
	public static final String ERROR_STALE_INSTRUCTION = "NDS.0006";

	/** Error code for an operation whose previous run is still stuck. */
	public static final String ERROR_OP_BUSY = "NDS.0007";

	/** Error code for an internal error. */
	public static final String ERROR_INTERNAL = "NDS.0100";

	/** The default {@code opDeadlineMs} property value. */
	public static final long DEFAULT_OP_DEADLINE_MS = 5_000L;

	/** The default {@code logsDeadlineMs} property value. */
	public static final long DEFAULT_LOGS_DEADLINE_MS = 10_000L;

	/** The default {@code maxScanBytes} property value. */
	public static final long DEFAULT_MAX_SCAN_BYTES = 8L * 1024L * 1024L;

	/** The default {@code maxInstructionAgeMin} property value. */
	public static final int DEFAULT_MAX_INSTRUCTION_AGE_MIN = 15;

	/** The maximum log window, in days, reported in capabilities. */
	public static final int MAX_WINDOW_DAYS = 14;

	/** The maximum log summary limit, reported in capabilities. */
	public static final int MAX_LIMIT = 30;

	/** The maximum parameter value length. */
	public static final int MAX_PARAM_VALUE_LENGTH = 256;

	/** The margin between the hard and soft deadlines, in milliseconds. */
	public static final long SOFT_DEADLINE_MARGIN_MS = 500L;

	/** The minimum soft deadline, in milliseconds. */
	public static final long MIN_SOFT_DEADLINE_MS = 100L;

	private static final Logger log = LoggerFactory.getLogger(DiagnosticsService.class);

	private static final Set<String> COMMON_PARAMS = Set.of(PARAM_SERVICE, PARAM_OP, PARAM_VERSION,
			PARAM_COMPRESS);
	private static final String API_VERSION_VALUE = String.valueOf(API_VERSION);
	private static final String DEV_PLUGIN_VERSION = "dev";
	private static final int MAX_REPORTED_NAMES = 5;
	private static final int MAX_AUDIT_NAMES = 12;
	private static final int MAX_NAME_LENGTH = 32;
	private static final int MAX_MESSAGE_LENGTH = 100;
	private static final long MIN_DEADLINE_MS = 200L;
	private static final Pattern INVALID_PARAMETER_MESSAGE = Pattern
			.compile("Invalid parameter: [A-Za-z][A-Za-z0-9_]{0,31}");

	private final Map<String, DiagnosticOp> ops;
	private final Clock clock;
	private final LongSupplier nanoTime;
	private final ZoneId zone;
	private final RateLimiter rateLimiter;
	private final OpRunner opRunner;
	private final String pluginVersion;

	private volatile long opDeadlineMs = DEFAULT_OP_DEADLINE_MS;
	private volatile long logsDeadlineMs = DEFAULT_LOGS_DEADLINE_MS;
	private volatile long maxScanBytes = DEFAULT_MAX_SCAN_BYTES;
	private volatile int maxInstructionAgeMin = DEFAULT_MAX_INSTRUCTION_AGE_MIN;
	private volatile Set<String> disabledOps = Collections.emptySet();

	/**
	 * Constructor.
	 *
	 * @param ops
	 *        the operations to support
	 * @throws IllegalArgumentException
	 *         if any operation name is duplicated or is the built-in
	 *         {@link #CAPABILITIES_OP}
	 */
	public DiagnosticsService(List<DiagnosticOp> ops) {
		this(ops, Clock.systemUTC(), System::nanoTime, ZoneId.systemDefault());
	}

	/**
	 * Constructor.
	 *
	 * @param ops
	 *        the operations to support
	 * @param clock
	 *        the wall clock
	 * @param nanoTime
	 *        the monotonic nanosecond time source
	 * @param zone
	 *        the node time zone
	 * @throws IllegalArgumentException
	 *         if any operation name is duplicated or is the built-in
	 *         {@link #CAPABILITIES_OP}
	 */
	public DiagnosticsService(List<DiagnosticOp> ops, Clock clock, LongSupplier nanoTime, ZoneId zone) {
		super();
		final Map<String, DiagnosticOp> m = new TreeMap<>();
		for ( DiagnosticOp op : ops ) {
			final String name = op.name();
			if ( CAPABILITIES_OP.equals(name) || m.containsKey(name) ) {
				throw new IllegalArgumentException("Duplicate diagnostic op name [" + name + "]");
			}
			m.put(name, op);
		}
		this.ops = Collections.unmodifiableMap(m);
		this.clock = clock;
		this.nanoTime = nanoTime;
		this.zone = zone;
		this.rateLimiter = new RateLimiter(nanoTime);
		this.opRunner = new OpRunner(clock);
		this.pluginVersion = resolvePluginVersion(getClass());
	}

	/**
	 * Shut down the service, interrupting any running operations.
	 */
	public void shutdown() {
		opRunner.shutdown();
	}

	@Override
	public boolean handlesTopic(@Nullable String topic) {
		return TOPIC_SYSTEM_CONFIGURATION.equals(topic);
	}

	@Override
	public @Nullable InstructionStatus processInstruction(@Nullable Instruction instruction) {
		try {
			if ( instruction == null || !handlesTopic(instruction.getTopic()) ) {
				return null;
			}
			final String serviceId = instruction.getParameterValue(PARAM_SERVICE);
			if ( !DIAGNOSTICS_SERVICE_UID.equals(serviceId) ) {
				return null;
			}
		} catch ( Throwable t ) {
			// not something we can handle
			return null;
		}

		final long start = nanoTime.getAsLong();
		final Audit audit = new Audit();
		InstructionStatus status;
		try {
			status = handle(instruction, audit);
		} catch ( Throwable t ) {
			logError(audit, t);
			status = internalError(instruction, audit, t);
		}
		if ( status != null ) {
			audit(instruction, audit, status, start);
		}
		return status;
	}

	private static @Nullable InstructionStatus internalError(Instruction instruction, Audit audit,
			Throwable t) {
		try {
			return decline(instruction, audit, ERROR_INTERNAL, internalErrorMessage(t));
		} catch ( Throwable t2 ) {
			// fall back to a status without any result parameters
			try {
				return createStatus(instruction, Declined);
			} catch ( Throwable t3 ) {
				return null;
			}
		}
	}

	private InstructionStatus handle(Instruction instruction, Audit audit) {
		final String v = instruction.getParameterValue(PARAM_VERSION);
		if ( v != null && !API_VERSION_VALUE.equals(v) ) {
			return decline(instruction, audit, ERROR_UNKNOWN_OP, "Unsupported API version.");
		}
		final String opName = instruction.getParameterValue(PARAM_OP);
		if ( opName == null || opName.isEmpty() ) {
			return decline(instruction, audit, ERROR_UNKNOWN_OP, "Missing op parameter.");
		}
		final boolean capabilities = CAPABILITIES_OP.equals(opName);
		final DiagnosticOp op = (capabilities ? null : ops.get(opName));
		if ( !capabilities && op == null ) {
			return decline(instruction, audit, ERROR_UNKNOWN_OP, "Unknown op.");
		}
		audit.op = opName;

		if ( disabledOps.contains(opName) ) {
			return decline(instruction, audit, ERROR_OP_DISABLED, "Op disabled.");
		}

		final Instant now = clock.instant();
		final Instant instructionDate = instruction.getInstructionDate();
		if ( instructionDate != null
				&& instructionDate.isBefore(now.minus(Duration.ofMinutes(maxInstructionAgeMin))) ) {
			return decline(instruction, audit, ERROR_STALE_INSTRUCTION, "Instruction too old (nodeNowMs="
					+ now.toEpochMilli() + ", instructionMs=" + instructionDate.toEpochMilli() + ").");
		}

		// parameters
		final Set<String> opParamNames = (op != null ? op.parameterNames() : Set.of());
		final Set<String> names = new LinkedHashSet<>(8);
		for ( String name : instruction.getParameterNames() ) {
			if ( name != null ) {
				names.add(name);
			}
		}
		final Set<String> unknown = new TreeSet<>();
		for ( String name : names ) {
			if ( audit.paramNames.size() < MAX_AUDIT_NAMES ) {
				audit.paramNames.add(sanitizeName(name));
			}
			if ( !(COMMON_PARAMS.contains(name) || opParamNames.contains(name)) ) {
				unknown.add(sanitizeName(name));
			}
		}
		if ( !unknown.isEmpty() ) {
			return decline(instruction, audit, ERROR_INVALID_PARAMETER,
					"Unknown parameter(s): " + joinNames(unknown));
		}
		final Map<String, String> params = new LinkedHashMap<>(8);
		for ( String name : names ) {
			final String[] values = instruction.getAllParameterValues(name);
			if ( values == null || values.length != 1 ) {
				return decline(instruction, audit, ERROR_INVALID_PARAMETER,
						"Parameter " + name + " must have exactly one value.");
			}
			final String value = values[0];
			if ( !validValue(value) ) {
				return decline(instruction, audit, ERROR_INVALID_PARAMETER,
						"Invalid value for parameter " + name + ".");
			}
			if ( !COMMON_PARAMS.contains(name) ) {
				params.put(name, value);
			}
		}
		final Map<String, String> opParams = Collections.unmodifiableMap(params);

		if ( op != null ) {
			try {
				op.validate(opParams, now);
			} catch ( IllegalArgumentException e ) {
				return decline(instruction, audit, ERROR_INVALID_PARAMETER,
						sanitizeMessage(e.getMessage()));
			}
			if ( opRunner.isRunning(op.name()) ) {
				// decline before taking a rate token
				return declineBusy(instruction, audit);
			}
		}

		// rate limit
		final RateClass rateClass = (op != null ? op.rateClass() : RateClass.NONE);
		final Duration retry = rateLimiter.tryAcquire(rateClass);
		if ( retry != null ) {
			final long secs = Math.max(1L,
					(retry.toNanos() + TimeUnit.SECONDS.toNanos(1) - 1) / TimeUnit.SECONDS.toNanos(1));
			final Map<String, Object> resultParams = new LinkedHashMap<>(4);
			resultParams.putAll(createErrorResultParameters("Rate limited.", ERROR_RATE_LIMITED));
			resultParams.put(PARAM_RETRY_AFTER, secs);
			audit.code = ERROR_RATE_LIMITED;
			return createStatus(instruction, Declined, resultParams);
		}

		final Redactor redactor = new Redactor();
		if ( op == null ) {
			return capabilities(instruction, audit, now, redactor);
		}
		return runOp(instruction, audit, op, opParams, now, redactor);
	}

	private InstructionStatus runOp(Instruction instruction, Audit audit, DiagnosticOp op,
			Map<String, String> params, Instant now, Redactor redactor) {
		final long hardMs = (op.rateClass() == RateClass.SCAN ? logsDeadlineMs : opDeadlineMs);
		final OpContext ctx = new OpContext(params, now, zone,
				new Deadline(softDeadlineMs(hardMs), nanoTime), redactor, maxScanBytes);
		final Callable<DiagnosticResult> task = () -> Objects.requireNonNull(op.run(ctx), "result");
		final long start = nanoTime.getAsLong();
		final OpRunner.Outcome outcome = opRunner.run(op.name(), task, hardMs);
		final long elapsedNanos = nanoTime.getAsLong() - start;
		final Throwable error = outcome.getError();
		final String invalidParam = (outcome.getKind() == OpRunner.Outcome.Kind.ERROR && error != null
				? invalidParameterMessage(error)
				: null);
		if ( outcome.getKind() == OpRunner.Outcome.Kind.BUSY || invalidParam != null ) {
			// the op did not run, or rejected its parameters: give back the rate token
			rateLimiter.release(op.rateClass());
		} else {
			rateLimiter.chargeWallTime(elapsedNanos);
		}
		final DiagnosticResult result = outcome.getResult();
		switch (outcome.getKind()) {
			case OK:
				if ( result != null ) {
					return complete(instruction, audit, op.name(), op.version(), now,
							TimeUnit.NANOSECONDS.toMillis(elapsedNanos), result, redactor);
				}
				return decline(instruction, audit, ERROR_INTERNAL,
						internalErrorMessage(new NullPointerException()));

			case BUSY:
				return declineBusy(instruction, audit);

			case TIMEOUT:
				return decline(instruction, audit, ERROR_DEADLINE_EXCEEDED, "Deadline exceeded.");

			default:
				final Throwable err = (error != null ? error : new IllegalStateException());
				if ( invalidParam != null ) {
					// a parameter that failed a check that requires the current time
					return decline(instruction, audit, ERROR_INVALID_PARAMETER, invalidParam);
				}
				logError(audit, err);
				return decline(instruction, audit, ERROR_INTERNAL, internalErrorMessage(err));
		}
	}

	private InstructionStatus capabilities(Instruction instruction, Audit audit, Instant now,
			Redactor redactor) {
		final long start = nanoTime.getAsLong();
		final long hardMs = opDeadlineMs;
		final Set<String> disabled = disabledOps;

		final DiagnosticResult result = new DiagnosticResult();
		result.put("api", API_VERSION);

		final Map<String, Object> opVersions = new LinkedHashMap<>(8);
		for ( DiagnosticOp op : ops.values() ) {
			opVersions.put(op.name(), op.version());
		}
		result.put("ops", opVersions);
		result.put("disabledOps", new ArrayList<>(new TreeSet<>(disabled)));

		final Map<String, Object> limits = new LinkedHashMap<>(8);
		limits.put("resultBudget", RESULT_BUDGET);
		limits.put("opDeadlineMs", opDeadlineMs);
		limits.put("logsDeadlineMs", logsDeadlineMs);
		limits.put("maxScanBytes", maxScanBytes);
		limits.put("maxInstructionAgeMin", maxInstructionAgeMin);
		limits.put("maxWindowDays", MAX_WINDOW_DAYS);
		limits.put("maxLimit", MAX_LIMIT);
		result.put("limits", limits);

		final Map<String, Object> rate = new LinkedHashMap<>(2);
		rate.put("light", rateInfo(RateClass.LIGHT));
		rate.put("scan", rateInfo(RateClass.SCAN));
		result.put("rate", rate);

		result.put("stuckOps", opRunner.stuckOps());

		for ( DiagnosticOp op : ops.values() ) {
			final String name = op.name();
			if ( disabled.contains(name) || result.getData().containsKey(name) ) {
				continue;
			}
			// all op capabilities share one hard deadline
			final long remainingMs = hardMs
					- TimeUnit.NANOSECONDS.toMillis(nanoTime.getAsLong() - start);
			if ( remainingMs < 1 ) {
				result.notAvailable(name, DiagnosticResult.Reason.TIMEOUT);
				continue;
			}
			opCapabilities(op, now, remainingMs, result);
		}

		// an op capabilities call may have just become stuck
		result.put("stuckOps", opRunner.stuckOps());

		final long tookMs = TimeUnit.NANOSECONDS.toMillis(nanoTime.getAsLong() - start);
		return complete(instruction, audit, CAPABILITIES_OP, CAPABILITIES_VERSION, now, tookMs, result,
				redactor);
	}

	/**
	 * Collect the capabilities of one op on the op's worker thread, as they may
	 * perform blocking I/O.
	 */
	private void opCapabilities(DiagnosticOp op, Instant now, long hardMs, DiagnosticResult result) {
		final String name = op.name();
		final OpContext ctx = new OpContext(Collections.emptyMap(), now, zone,
				new Deadline(softDeadlineMs(hardMs), nanoTime), new Redactor(), maxScanBytes);
		final OpRunner.Outcome outcome = opRunner.run(name,
				() -> new DiagnosticResult().put(name, op.capabilities(ctx)), hardMs);
		final DiagnosticResult r = outcome.getResult();
		switch (outcome.getKind()) {
			case OK:
				if ( r != null ) {
					result.put(name, r.getData().get(name));
				}
				break;

			case BUSY:
			case TIMEOUT:
				result.notAvailable(name, DiagnosticResult.Reason.TIMEOUT);
				break;

			default:
				final Throwable t = outcome.getError();
				final String type = (t != null ? t.getClass().getSimpleName() : "?");
				log.warn("Diagnostics op [{}] capabilities failed ({})", name, type);
				log.debug("Diagnostics op [{}] capabilities failure", name, t);
				result.notAvailable(name, DiagnosticResult.Reason.ERROR);
		}
	}

	private Map<String, Object> rateInfo(RateClass rateClass) {
		final Map<String, Object> m = new LinkedHashMap<>(2);
		m.put("perHour", rateLimiter.getPerHour(rateClass));
		m.put("available", rateLimiter.available(rateClass));
		return m;
	}

	private InstructionStatus complete(Instruction instruction, Audit audit, String opName,
			int opVersion, Instant now, long tookMs, DiagnosticResult result, Redactor redactor) {
		final Map<String, Object> node = new LinkedHashMap<>(2);
		node.put("nowMs", now.toEpochMilli());
		node.put("zone", zone.getId());

		final Map<String, Object> envelope = new LinkedHashMap<>(12);
		envelope.put("v", API_VERSION);
		envelope.put("op", opName);
		envelope.put("opV", opVersion);
		envelope.put("plugin", pluginVersion);
		envelope.put("tookMs", tookMs);
		envelope.put("node", node);
		envelope.put("data", result.getData());
		envelope.put("truncated", new ArrayList<>(result.getTruncated()));
		envelope.put("notAvailable", new ArrayList<>(result.getNotAvailable()));
		envelope.put("redacted", 0);

		final String encoded = ResultEncoder.encode(envelope, RESULT_BUDGET, redactor);
		audit.chars = encoded.length();
		return createStatus(instruction, Completed, singletonMap(PARAM_SERVICE_RESULT, encoded));
	}

	private static InstructionStatus decline(Instruction instruction, Audit audit, String code,
			String message) {
		audit.code = code;
		return createStatus(instruction, Declined, createErrorResultParameters(message, code));
	}

	private static InstructionStatus declineBusy(Instruction instruction, Audit audit) {
		return decline(instruction, audit, ERROR_OP_BUSY, "Op busy: previous run still in progress.");
	}

	private static long softDeadlineMs(long hardMs) {
		return Math.max(MIN_SOFT_DEADLINE_MS, hardMs - SOFT_DEADLINE_MARGIN_MS);
	}

	/**
	 * Get a decline message for an invalid parameter exception thrown while
	 * running an operation.
	 *
	 * <p>
	 * Only an {@link IllegalArgumentException} whose message exactly matches
	 * {@link #INVALID_PARAMETER_MESSAGE} is treated as an invalid parameter, so
	 * that no parameter value can be echoed.
	 * </p>
	 *
	 * @param t
	 *        the exception
	 * @return the message, or {@code null} if {@code t} does not represent an
	 *         invalid parameter
	 */
	private static @Nullable String invalidParameterMessage(Throwable t) {
		if ( !(t instanceof IllegalArgumentException) ) {
			return null;
		}
		final String msg = t.getMessage();
		return (msg != null && INVALID_PARAMETER_MESSAGE.matcher(msg).matches() ? msg + "." : null);
	}

	private static String internalErrorMessage(Throwable t) {
		return "Internal error (" + sanitizeName(t.getClass().getSimpleName()) + ")";
	}

	private static void logError(Audit audit, Throwable t) {
		try {
			log.warn("Diagnostics op [{}] failed ({})", audit.op, t.getClass().getSimpleName());
			log.debug("Diagnostics op [{}] failure", audit.op, t);
		} catch ( Throwable t2 ) {
			// ignore
		}
	}

	private void audit(Instruction instruction, Audit audit, InstructionStatus status, long start) {
		try {
			final long elapsedMs = TimeUnit.NANOSECONDS.toMillis(nanoTime.getAsLong() - start);
			final InstructionState state = status.getInstructionState();
			log.info("Diagnostics instruction {} op [{}] params {} -> {} {} in {}ms ({} chars)",
					instruction.getId(), audit.op, audit.paramNames, state, audit.code, elapsedMs,
					audit.chars);
		} catch ( Throwable t ) {
			// ignore
		}
	}

	private static boolean validValue(@Nullable String value) {
		if ( value == null || value.length() > MAX_PARAM_VALUE_LENGTH ) {
			return false;
		}
		for ( int i = 0, len = value.length(); i < len; i++ ) {
			final char c = value.charAt(i);
			if ( c < 0x20 || c > 0x7E ) {
				return false;
			}
		}
		return true;
	}

	private static String sanitizeName(String name) {
		final int len = Math.min(name.length(), MAX_NAME_LENGTH);
		final StringBuilder buf = new StringBuilder(len);
		for ( int i = 0; i < len; i++ ) {
			final char c = name.charAt(i);
			if ( (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '_'
					|| c == '.' || c == '-' ) {
				buf.append(c);
			} else {
				buf.append('_');
			}
		}
		return buf.toString();
	}

	private static String joinNames(Set<String> names) {
		final StringBuilder buf = new StringBuilder();
		int i = 0;
		for ( String name : names ) {
			if ( i == MAX_REPORTED_NAMES ) {
				buf.append(", ...");
				break;
			}
			if ( i > 0 ) {
				buf.append(", ");
			}
			buf.append(name);
			i++;
		}
		return buf.toString();
	}

	private static String sanitizeMessage(@Nullable String message) {
		if ( message == null || message.isEmpty() ) {
			return "Invalid parameter.";
		}
		final StringBuilder buf = new StringBuilder(Math.min(message.length(), MAX_MESSAGE_LENGTH));
		for ( int i = 0, len = message.length(); i < len && buf.length() < MAX_MESSAGE_LENGTH; i++ ) {
			final char c = message.charAt(i);
			if ( c >= 0x20 && c <= 0x7E ) {
				buf.append(c);
			}
		}
		return (buf.length() > 0 ? buf.toString() : "Invalid parameter.");
	}

	private static String resolvePluginVersion(Class<?> clazz) {
		try {
			final Bundle bundle = FrameworkUtil.getBundle(clazz);
			if ( bundle != null ) {
				return bundle.getVersion().toString();
			}
		} catch ( RuntimeException | LinkageError e ) {
			// ignore
		}
		return DEV_PLUGIN_VERSION;
	}

	/**
	 * Get the hard deadline for non-scanning operations.
	 *
	 * @return the deadline, in milliseconds
	 */
	public long getOpDeadlineMs() {
		return opDeadlineMs;
	}

	/**
	 * Set the hard deadline for non-scanning operations.
	 *
	 * @param opDeadlineMs
	 *        the deadline to set, in milliseconds; values less than {@code 200}
	 *        are treated as {@code 200}
	 */
	public void setOpDeadlineMs(long opDeadlineMs) {
		this.opDeadlineMs = Math.max(MIN_DEADLINE_MS, opDeadlineMs);
	}

	/**
	 * Get the hard deadline for {@link RateClass#SCAN} operations.
	 *
	 * @return the deadline, in milliseconds
	 */
	public long getLogsDeadlineMs() {
		return logsDeadlineMs;
	}

	/**
	 * Set the hard deadline for {@link RateClass#SCAN} operations.
	 *
	 * @param logsDeadlineMs
	 *        the deadline to set, in milliseconds; values less than {@code 200}
	 *        are treated as {@code 200}
	 */
	public void setLogsDeadlineMs(long logsDeadlineMs) {
		this.logsDeadlineMs = Math.max(MIN_DEADLINE_MS, logsDeadlineMs);
	}

	/**
	 * Get the maximum number of bytes an operation may read when scanning
	 * files.
	 *
	 * @return the maximum scan bytes
	 */
	public long getMaxScanBytes() {
		return maxScanBytes;
	}

	/**
	 * Set the maximum number of bytes an operation may read when scanning
	 * files.
	 *
	 * @param maxScanBytes
	 *        the maximum scan bytes to set; values less than {@code 1} are
	 *        treated as {@code 1}
	 */
	public void setMaxScanBytes(long maxScanBytes) {
		this.maxScanBytes = Math.max(1L, maxScanBytes);
	}

	/**
	 * Get the maximum instruction age.
	 *
	 * @return the maximum age, in minutes
	 */
	public int getMaxInstructionAgeMin() {
		return maxInstructionAgeMin;
	}

	/**
	 * Set the maximum instruction age.
	 *
	 * <p>
	 * Instructions with an instruction date older than this are declined.
	 * </p>
	 *
	 * @param maxInstructionAgeMin
	 *        the maximum age to set, in minutes; values less than {@code 1} are
	 *        treated as {@code 1}
	 */
	public void setMaxInstructionAgeMin(int maxInstructionAgeMin) {
		this.maxInstructionAgeMin = Math.max(1, maxInstructionAgeMin);
	}

	/**
	 * Get the disabled operation names.
	 *
	 * @return the disabled operation names as a sorted comma-delimited string,
	 *         or {@code null} if none are disabled
	 */
	public @Nullable String getDisabledOps() {
		return StringUtils.commaDelimitedStringFromCollection(new TreeSet<>(disabledOps));
	}

	/**
	 * Set the disabled operation names.
	 *
	 * @param disabledOps
	 *        a comma-delimited list of operation names to disable
	 */
	public void setDisabledOps(@Nullable String disabledOps) {
		final Set<String> set = (disabledOps != null ? StringUtils.commaDelimitedStringToSet(disabledOps)
				: null);
		this.disabledOps = (set != null ? Set.copyOf(set) : Collections.emptySet());
	}

	/**
	 * Get the {@link RateClass#LIGHT} rate per hour.
	 *
	 * @return the rate per hour
	 */
	public int getLightRatePerHour() {
		return rateLimiter.getPerHour(RateClass.LIGHT);
	}

	/**
	 * Set the {@link RateClass#LIGHT} rate per hour.
	 *
	 * @param lightRatePerHour
	 *        the rate per hour to set
	 */
	public void setLightRatePerHour(int lightRatePerHour) {
		rateLimiter.setPerHour(RateClass.LIGHT, lightRatePerHour);
	}

	/**
	 * Get the {@link RateClass#SCAN} rate per hour.
	 *
	 * @return the rate per hour
	 */
	public int getScanRatePerHour() {
		return rateLimiter.getPerHour(RateClass.SCAN);
	}

	/**
	 * Set the {@link RateClass#SCAN} rate per hour.
	 *
	 * @param scanRatePerHour
	 *        the rate per hour to set
	 */
	public void setScanRatePerHour(int scanRatePerHour) {
		rateLimiter.setPerHour(RateClass.SCAN, scanRatePerHour);
	}

	/**
	 * Audit information for a single instruction.
	 */
	private static final class Audit {

		private String op = "?";
		private final List<String> paramNames = new ArrayList<>(8);
		private String code = "";
		private int chars;

	}

}
