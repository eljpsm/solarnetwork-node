/* ==================================================================
 * SystemOp.java - 16/10/2026 9:00:00 am
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

import static net.solarnetwork.util.ObjectUtils.requireNonNullArgument;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryUsage;
import java.lang.management.RuntimeMXBean;
import java.lang.management.ThreadMXBean;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import org.osgi.framework.BundleContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import net.solarnetwork.node.service.PlatformService;
import net.solarnetwork.node.service.PlatformService.PlatformState;
import net.solarnetwork.service.OptionalService;

/**
 * Diagnostic operation to report general system information.
 *
 * <p>
 * This reports allowlisted OS release information, kernel, CPU, Java and JVM
 * runtime information, memory, uptime, load average, platform state, the node
 * database backend, and non-active bundles. Comparing the JVM uptime with the
 * system uptime gives a SolarNode restart signal.
 * </p>
 *
 * @author elijah
 * @version 1.0
 */
public class SystemOp implements DiagnosticOp {

	/** The operation name. */
	public static final String NAME = "system";

	/** The maximum length of string values read from system files. */
	public static final int MAX_VALUE_LENGTH = 100;

	/** The maximum number of bytes to read from small system files. */
	public static final int MAX_FILE_BYTES = 16 * 1024;

	/** The allowlisted JVM input argument patterns. */
	// @formatter:off
	private static final List<Pattern> INPUT_ARG_ALLOWLIST = List.of(
			Pattern.compile("^-Xm[sx][0-9]+[kKmMgG]?$"),
			Pattern.compile("^-Xss[0-9]+[kKmMgG]?$"),
			Pattern.compile("^-XX:[+-]?Use[A-Za-z0-9]+GC$"),
			Pattern.compile("^-XX:(Max|Initial|Min)RAMPercentage=[0-9.]+$"),
			Pattern.compile("^-XX:MaxMetaspaceSize=[0-9]+[kKmMgG]?$"));
	// @formatter:on

	/** The allowlisted os-release keys, mapped to their result keys. */
	private static final Map<String, String> OS_RELEASE_KEYS = Map.of("ID", "id", "VERSION_ID",
			"versionId", "VERSION_CODENAME", "versionCodename", "PRETTY_NAME", "prettyName");

	private static final Pattern OS_RELEASE_LINE = Pattern.compile("^([A-Z_]+)=(.*)$");

	private static final Pattern MEMINFO_LINE = Pattern
			.compile("^([A-Za-z0-9_()]+):\\s+([0-9]{1,18})(?:\\s+kB)?\\s*$");

	private static final Logger log = LoggerFactory.getLogger(SystemOp.class);

	private final BundleContext bundleContext;
	private final @Nullable OptionalService<PlatformService> platformService;
	private final DiagnosticsPaths paths;

	/**
	 * Constructor.
	 *
	 * @param bundleContext
	 *        the bundle context
	 * @param platformService
	 *        the optional platform service
	 * @param paths
	 *        the paths
	 * @throws IllegalArgumentException
	 *         if {@code bundleContext} or {@code paths} is {@code null}
	 */
	public SystemOp(BundleContext bundleContext,
			@Nullable OptionalService<PlatformService> platformService, DiagnosticsPaths paths) {
		super();
		this.bundleContext = requireNonNullArgument(bundleContext, "bundleContext");
		this.platformService = platformService;
		this.paths = requireNonNullArgument(paths, "paths");
	}

	@Override
	public String name() {
		return NAME;
	}

	@Override
	public int version() {
		return 1;
	}

	@Override
	public RateClass rateClass() {
		return RateClass.LIGHT;
	}

	/**
	 * A data item collector.
	 */
	@FunctionalInterface
	private interface ItemCollector {

		void collect() throws Exception;

	}

	@Override
	public DiagnosticResult run(OpContext ctx) throws Exception {
		final DiagnosticResult result = new DiagnosticResult();
		final Deadline deadline = ctx.getDeadline();
		final RuntimeMXBean runtime = ManagementFactory.getRuntimeMXBean();
		try {
			item(deadline, result, "os", () -> os(result));
			item(deadline, result, "kernel", () -> kernel(result));
			item(deadline, result, "arch", () -> result.put("arch", System.getProperty("os.arch")));
			item(deadline, result, "cpus",
					() -> result.put("cpus", Runtime.getRuntime().availableProcessors()));
			item(deadline, result, "java", () -> java(result));
			item(deadline, result, "jvm", () -> jvm(result, runtime));
			item(deadline, result, "memory", () -> memory(result));
			item(deadline, result, "uptime", () -> uptime(result, runtime));
			item(deadline, result, "loadAvg", () -> loadAvg(result));
			item(deadline, result, "platform", () -> platform(result));
			item(deadline, result, "db",
					() -> result.put("db", OsgiRuntimeInfo.dbBackend(bundleContext)));
			item(deadline, result, "bundles",
					() -> result.put("bundles", OsgiRuntimeInfo.bundles(bundleContext)));
		} catch ( DeadlineExceededException e ) {
			result.notAvailable(NAME, DiagnosticResult.Reason.TIMEOUT);
		}
		return result;
	}

	private static void item(Deadline deadline, DiagnosticResult result, String item,
			ItemCollector collector) {
		deadline.check();
		try {
			collector.collect();
		} catch ( DeadlineExceededException e ) {
			throw e;
		} catch ( Exception e ) {
			log.debug("Error collecting system diagnostic item [{}]: {}", item, e.toString());
			result.notAvailable(item, DiagnosticResult.Reason.ERROR);
		}
	}

	private void os(DiagnosticResult result) {
		// /etc/os-release is commonly a link to /usr/lib/os-release; links are not followed
		String text = null;
		for ( Path p : paths.getOsReleaseFiles() ) {
			text = DiagnosticsFiles.readSmall(p, MAX_FILE_BYTES);
			if ( text != null ) {
				break;
			}
		}
		if ( text == null ) {
			result.notAvailable("os", DiagnosticResult.Reason.ABSENT);
			return;
		}
		result.put("os", parseOsRelease(text));
	}

	/**
	 * Parse allowlisted values from {@code os-release} content.
	 *
	 * @param text
	 *        the {@code os-release} content
	 * @return the allowlisted values, never {@code null}
	 */
	public static Map<String, Object> parseOsRelease(String text) {
		final Map<String, Object> os = new LinkedHashMap<>(4);
		for ( String line : text.split("\\r?\\n") ) {
			Matcher m = OS_RELEASE_LINE.matcher(line.trim());
			if ( !m.matches() ) {
				continue;
			}
			String key = OS_RELEASE_KEYS.get(m.group(1));
			if ( key == null ) {
				continue;
			}
			String val = m.group(2).trim();
			if ( val.length() >= 2 && (val.charAt(0) == '"' || val.charAt(0) == '\'')
					&& val.charAt(val.length() - 1) == val.charAt(0) ) {
				val = val.substring(1, val.length() - 1);
			}
			os.put(key, Redactor.cut(val, MAX_VALUE_LENGTH));
		}
		// order keys consistently
		final Map<String, Object> result = new LinkedHashMap<>(4);
		for ( String k : new String[] { "id", "versionId", "versionCodename", "prettyName" } ) {
			Object v = os.get(k);
			if ( v != null ) {
				result.put(k, v);
			}
		}
		return result;
	}

	private void kernel(DiagnosticResult result) {
		final String text = DiagnosticsFiles
				.readSmall(paths.getProcDir().resolve("sys/kernel/osrelease"), MAX_FILE_BYTES);
		if ( text == null ) {
			result.notAvailable("kernel", DiagnosticResult.Reason.ABSENT);
			return;
		}
		result.put("kernel", Redactor.cut(text.trim(), MAX_VALUE_LENGTH));
	}

	private static void java(DiagnosticResult result) {
		final Map<String, Object> java = new LinkedHashMap<>(4);
		putProperty(java, "version", "java.version");
		putProperty(java, "vendor", "java.vendor");
		putProperty(java, "vmName", "java.vm.name");
		putProperty(java, "vmVersion", "java.vm.version");
		result.put("java", java);
	}

	private static void putProperty(Map<String, Object> m, String key, String prop) {
		String v = System.getProperty(prop);
		if ( v != null ) {
			m.put(key, Redactor.cut(v, MAX_VALUE_LENGTH));
		}
	}

	private static void jvm(DiagnosticResult result, RuntimeMXBean runtime) {
		final Map<String, Object> jvm = new LinkedHashMap<>(8);
		jvm.put("startMs", runtime.getStartTime());
		jvm.put("uptimeMs", runtime.getUptime());

		final MemoryMXBean mem = ManagementFactory.getMemoryMXBean();
		final MemoryUsage heapUsage = mem.getHeapMemoryUsage();
		final Map<String, Object> heap = new LinkedHashMap<>(4);
		heap.put("usedBytes", heapUsage.getUsed());
		heap.put("committedBytes", heapUsage.getCommitted());
		if ( heapUsage.getMax() >= 0 ) {
			heap.put("maxBytes", heapUsage.getMax());
		}
		jvm.put("heap", heap);
		jvm.put("nonHeapUsedBytes", mem.getNonHeapMemoryUsage().getUsed());

		final ThreadMXBean threads = ManagementFactory.getThreadMXBean();
		jvm.put("threads", threads.getThreadCount());

		jvm.put("inputArgs", filterInputArgs(runtime.getInputArguments()));
		result.put("jvm", jvm);
	}

	/**
	 * Filter JVM input arguments to an allowlist of memory, stack, and garbage
	 * collector settings.
	 *
	 * <p>
	 * System property ({@code -D}) arguments are never included.
	 * </p>
	 *
	 * @param args
	 *        the input arguments
	 * @return the allowlisted arguments, never {@code null}
	 */
	public static List<String> filterInputArgs(@Nullable List<String> args) {
		final List<String> result = new ArrayList<>(4);
		if ( args == null ) {
			return result;
		}
		for ( String arg : args ) {
			if ( arg == null || arg.length() > MAX_VALUE_LENGTH ) {
				continue;
			}
			for ( Pattern p : INPUT_ARG_ALLOWLIST ) {
				if ( p.matcher(arg).matches() ) {
					result.add(arg);
					break;
				}
			}
		}
		return result;
	}

	private void memory(DiagnosticResult result) {
		final String text = DiagnosticsFiles.readSmall(paths.getProcDir().resolve("meminfo"),
				MAX_FILE_BYTES);
		if ( text == null ) {
			result.notAvailable("memory", DiagnosticResult.Reason.ABSENT);
			return;
		}
		result.put("memory", parseMeminfo(text));
	}

	/**
	 * Parse memory information from {@code /proc/meminfo} content.
	 *
	 * @param text
	 *        the content
	 * @return a map with optional {@code totalBytes}, {@code availableBytes},
	 *         {@code swapTotalBytes}, and {@code swapFreeBytes} keys
	 */
	public static Map<String, Object> parseMeminfo(String text) {
		final Map<String, Long> vals = new LinkedHashMap<>(8);
		for ( String line : text.split("\\r?\\n") ) {
			Matcher m = MEMINFO_LINE.matcher(line);
			if ( m.matches() ) {
				vals.put(m.group(1), Long.parseLong(m.group(2)) * 1024L);
			}
		}
		final Map<String, Object> result = new LinkedHashMap<>(4);
		putValue(result, "totalBytes", vals.get("MemTotal"));
		putValue(result, "availableBytes", vals.get("MemAvailable"));
		putValue(result, "swapTotalBytes", vals.get("SwapTotal"));
		putValue(result, "swapFreeBytes", vals.get("SwapFree"));
		return result;
	}

	private static void putValue(Map<String, Object> m, String key, @Nullable Object val) {
		if ( val != null ) {
			m.put(key, val);
		}
	}

	private void uptime(DiagnosticResult result, RuntimeMXBean runtime) {
		final Map<String, Object> uptime = new LinkedHashMap<>(2);
		final String text = DiagnosticsFiles.readSmall(paths.getProcDir().resolve("uptime"),
				MAX_FILE_BYTES);
		Long systemMs = null;
		if ( text != null ) {
			String[] tokens = text.trim().split("\\s+");
			if ( tokens.length > 0 && !tokens[0].isEmpty() ) {
				systemMs = (long) (Double.parseDouble(tokens[0]) * 1000.0);
			}
		}
		if ( systemMs != null ) {
			uptime.put("systemMs", systemMs);
		} else {
			result.notAvailable("uptime", DiagnosticResult.Reason.ABSENT);
		}
		uptime.put("jvmMs", runtime.getUptime());
		result.put("uptime", uptime);
	}

	private void loadAvg(DiagnosticResult result) {
		final String text = DiagnosticsFiles.readSmall(paths.getProcDir().resolve("loadavg"),
				MAX_FILE_BYTES);
		if ( text == null ) {
			result.notAvailable("loadAvg", DiagnosticResult.Reason.ABSENT);
			return;
		}
		String[] tokens = text.trim().split("\\s+");
		if ( tokens.length < 3 ) {
			throw new IllegalArgumentException("Unexpected loadavg format.");
		}
		List<Double> load = new ArrayList<>(3);
		for ( int i = 0; i < 3; i++ ) {
			load.add(Double.valueOf(tokens[i]));
		}
		result.put("loadAvg", load);
	}

	private void platform(DiagnosticResult result) {
		final PlatformService service = (platformService != null ? platformService.service() : null);
		if ( service == null ) {
			result.notAvailable("platform", DiagnosticResult.Reason.ABSENT);
			return;
		}
		final Map<String, Object> platform = new LinkedHashMap<>(2);
		final PlatformState state = service.activePlatformState();
		if ( state != null ) {
			platform.put("state", state.name());
		}
		result.put("platform", platform);
	}

}
