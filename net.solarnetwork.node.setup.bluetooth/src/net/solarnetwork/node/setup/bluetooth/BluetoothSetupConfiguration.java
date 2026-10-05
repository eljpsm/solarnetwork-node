/* ==================================================================
 * BluetoothSetupConfiguration.java - 6/10/2026 8:30:00 AM
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

package net.solarnetwork.node.setup.bluetooth;

import static net.solarnetwork.node.Constants.solarNodeHome;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import org.jspecify.annotations.Nullable;
import org.osgi.service.event.Event;
import org.osgi.service.event.EventHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.MessageSource;
import org.springframework.scheduling.TaskScheduler;
import net.solarnetwork.domain.InstructionStatus.InstructionState;
import net.solarnetwork.node.reactor.Instruction;
import net.solarnetwork.node.reactor.InstructionHandler;
import net.solarnetwork.node.reactor.InstructionStatus;
import net.solarnetwork.node.reactor.InstructionUtils;
import net.solarnetwork.node.service.OperationalModesService;
import net.solarnetwork.node.service.SystemHealthService;
import net.solarnetwork.node.service.SystemHealthService.PingTestResults;
import net.solarnetwork.node.service.support.BaseIdentifiable;
import net.solarnetwork.settings.SettingSpecifier;
import net.solarnetwork.settings.SettingSpecifierProvider;
import net.solarnetwork.settings.SettingsChangeObserver;
import net.solarnetwork.settings.support.BasicTextFieldSettingSpecifier;
import net.solarnetwork.settings.support.BasicTitleSettingSpecifier;
import net.solarnetwork.settings.support.BasicToggleSettingSpecifier;

/**
 * Settings provider and instruction handler that turns the Bluetooth setup
 * radio on only while it is needed.
 *
 * <p>
 * The radio is <b>off</b> unless at least one of the following holds:
 * </p>
 *
 * <ol>
 * <li>the {@link #getOpMode()} operational mode is active. SolarNetwork can
 * enable this remotely with the {@code EnableOperationalModes} instruction
 * (with an {@code Expiration}), and a Bluetooth client can enable it with the
 * {@code enable} action of this service (see below);</li>
 * <li>the node has been unable to reach SolarNetwork for at least
 * {@link #getOfflineThresholdMinutes()} minutes, until
 * {@link #getOnlineGraceMinutes()} minutes after it can again;</li>
 * <li>the {@link #isAlwaysOn()} setting is {@literal true}.</li>
 * </ol>
 *
 * <p>
 * This service handles the {@link InstructionHandler#TOPIC_SYSTEM_CONFIGURE}
 * instruction topic when the {@link InstructionHandler#PARAM_SERVICE} parameter
 * is {@link #BLUETOOTH_SERVICE_NAME}. The {@link #PARAM_ACTION} parameter
 * selects the operation to perform:
 * </p>
 *
 * <ul>
 * <li>{@code status} (or no action) - return the current radio status</li>
 * <li>{@code enable} - enable the operational mode for {@link #PARAM_DURATION}
 * seconds (default {@link #getDefaultDurationSeconds()}, at most
 * {@link #getMaxDurationSeconds()})</li>
 * <li>{@code disable} - disable the operational mode</li>
 * <li>{@code restart} - restart the Bluetooth peripheral service</li>
 * </ul>
 *
 * <p>
 * The radio itself is controlled through the OS-specific {@code solarcfg}
 * helper script, invoked as {@code solarcfg bluetooth <action>}.
 * </p>
 *
 * @author elijah
 * @version 1.0
 */
public class BluetoothSetupConfiguration extends BaseIdentifiable
		implements SettingSpecifierProvider, SettingsChangeObserver, InstructionHandler, EventHandler {

	/** The {@code solarcfg} service name for the Bluetooth radio. */
	public static final String CONFIG_SERVICE = "bluetooth";

	/** The default value for the {@code command} property. */
	public static final String DEFAULT_COMMAND = solarNodeHome() + "/bin/solarcfg";

	/** The {@literal service} instruction parameter value for this service. */
	public static final String BLUETOOTH_SERVICE_NAME = "/setup/bluetooth";

	/** The {@literal action} instruction parameter name. */
	public static final String PARAM_ACTION = "action";

	/** The {@literal duration} instruction parameter name, in seconds. */
	public static final String PARAM_DURATION = "duration";

	/** The {@literal action} parameter value to return the current status. */
	public static final String ACTION_STATUS = "status";

	/** The {@literal action} parameter value to enable the radio. */
	public static final String ACTION_ENABLE = "enable";

	/** The {@literal action} parameter value to disable the radio. */
	public static final String ACTION_DISABLE = "disable";

	/** The {@literal action} parameter value to restart the peripheral. */
	public static final String ACTION_RESTART = "restart";

	/** The default value for the {@code opMode} property. */
	public static final String DEFAULT_OP_MODE = "bt-setup";

	/**
	 * The default value for the {@code pingTestIdRegex} property: the
	 * SolarIn/MQTT upload service ping test.
	 */
	public static final String DEFAULT_PING_TEST_ID_REGEX = "net\\.solarnetwork\\.node\\.upload\\.mqtt\\.MqttUploadService";

	/** The default value for the {@code offlineThresholdMinutes} property. */
	public static final int DEFAULT_OFFLINE_THRESHOLD_MINUTES = 15;

	/** The default value for the {@code onlineGraceMinutes} property. */
	public static final int DEFAULT_ONLINE_GRACE_MINUTES = 10;

	/** The default value for the {@code watchdogIntervalSeconds} property. */
	public static final int DEFAULT_WATCHDOG_INTERVAL_SECONDS = 60;

	/** The default value for the {@code defaultDurationSeconds} property. */
	public static final long DEFAULT_DEFAULT_DURATION_SECONDS = 1800L;

	/** The default value for the {@code maxDurationSeconds} property. */
	public static final long DEFAULT_MAX_DURATION_SECONDS = 14400L;

	/** The maximum time to wait for the helper command to complete. */
	private static final long COMMAND_TIMEOUT_SECONDS = 60L;

	private final Logger log = LoggerFactory.getLogger(getClass());

	private final OperationalModesService opModesService;
	private final TaskScheduler taskScheduler;
	private final SystemHealthService systemHealthService;

	private Clock clock = Clock.systemUTC();

	private String command = DEFAULT_COMMAND;
	private String opMode = DEFAULT_OP_MODE;
	private boolean alwaysOn = false;
	private boolean offlineTriggerEnabled = true;
	private int offlineThresholdMinutes = DEFAULT_OFFLINE_THRESHOLD_MINUTES;
	private int onlineGraceMinutes = DEFAULT_ONLINE_GRACE_MINUTES;
	private int watchdogIntervalSeconds = DEFAULT_WATCHDOG_INTERVAL_SECONDS;
	private String pingTestIdRegex = DEFAULT_PING_TEST_ID_REGEX;
	private long defaultDurationSeconds = DEFAULT_DEFAULT_DURATION_SECONDS;
	private long maxDurationSeconds = DEFAULT_MAX_DURATION_SECONDS;
	private int enableMinutes = 0;

	private boolean opModeActive = false;
	private boolean offline = false;
	private Instant offlineSince = null;
	private Instant onlineSince = null;
	private ScheduledFuture<?> watchdogFuture = null;
	private int watchdogScheduledIntervalSeconds = 0;

	/**
	 * Constructor.
	 *
	 * @param opModesService
	 *        the operational modes service
	 * @param taskScheduler
	 *        the task scheduler
	 * @param systemHealthService
	 *        the system health service
	 */
	public BluetoothSetupConfiguration(OperationalModesService opModesService,
			TaskScheduler taskScheduler, SystemHealthService systemHealthService) {
		super();
		this.opModesService = opModesService;
		this.taskScheduler = taskScheduler;
		this.systemHealthService = systemHealthService;
		setUid("net.solarnetwork.node.setup.bluetooth.BluetoothSetupConfiguration");
		setDisplayName("Bluetooth Setup");
	}

	/**
	 * Start up the service.
	 *
	 * <p>
	 * Synchronises the radio with the current desired state and starts the
	 * offline watchdog.
	 * </p>
	 */
	public synchronized void startup() {
		opModeActive = opModesService.isOperationalModeActive(opMode);
		reconcileQuietly();
		scheduleWatchdog();
	}

	/**
	 * Shut down the service.
	 *
	 * <p>
	 * The radio is left in its current state: the operational mode persists
	 * across restarts and will be re-applied on the next startup.
	 * </p>
	 */
	public synchronized void shutdown() {
		cancelWatchdog();
	}

	@Override
	public synchronized void configurationChanged(Map<String, Object> properties) {
		opModeActive = opModesService.isOperationalModeActive(opMode);
		// the "enableMinutes" field is transient: when set, enable the radio for
		// that long and then clear the value so the UI returns to 0 on reload
		if ( enableMinutes > 0 ) {
			final long seconds = enableMinutes * 60L;
			enableMinutes = 0;
			log.info("Bluetooth setup radio enable for {}s requested via settings", seconds);
			try {
				enableForSeconds(seconds);
			} catch ( Exception e ) {
				log.warn("Error enabling Bluetooth setup radio: {}", e.getMessage());
			}
		}
		if ( watchdogFuture != null && watchdogScheduledIntervalSeconds != watchdogIntervalSeconds ) {
			cancelWatchdog();
		}
		if ( watchdogFuture == null ) {
			scheduleWatchdog();
		}
		reconcileQuietly();
	}

	@Override
	public void handleEvent(Event event) {
		if ( event == null || !OperationalModesService.EVENT_TOPIC_OPERATIONAL_MODES_CHANGED
				.equals(event.getTopic()) ) {
			return;
		}
		final boolean active;
		synchronized ( this ) {
			active = OperationalModesService.hasActiveOperationalMode(event, opMode);
			if ( active == opModeActive ) {
				return;
			}
			opModeActive = active;
		}
		log.info("Bluetooth setup operational mode [{}] is now {}", opMode,
				active ? "active" : "inactive");
		// reconcile on another thread so the event delivery thread is not blocked
		// on the (slow) OS helper command
		if ( taskScheduler != null ) {
			taskScheduler.schedule(this::reconcileQuietly, clock.instant());
		} else {
			reconcileQuietly();
		}
	}

	@Override
	public boolean handlesTopic(String topic) {
		return InstructionHandler.TOPIC_SYSTEM_CONFIGURE.equals(topic);
	}

	@Override
	public synchronized InstructionStatus processInstruction(Instruction instruction) {
		if ( instruction == null || !handlesTopic(instruction.getTopic())
				|| !BLUETOOTH_SERVICE_NAME.equals(instruction.getParameterValue(PARAM_SERVICE)) ) {
			return null;
		}
		String action = instruction.getParameterValue(PARAM_ACTION);
		if ( action == null || action.isEmpty() ) {
			action = ACTION_STATUS;
		}
		Map<String, Object> resultParams = new LinkedHashMap<>(2);
		InstructionState resultState = InstructionState.Completed;
		try {
			switch (action.toLowerCase(Locale.ENGLISH)) {
				case ACTION_STATUS:
					resultParams.put(PARAM_SERVICE_RESULT, currentStatus());
					break;
				case ACTION_ENABLE:
					enableForSeconds(parseDuration(instruction.getParameterValue(PARAM_DURATION)));
					resultParams.put(PARAM_SERVICE_RESULT, currentStatus());
					break;
				case ACTION_DISABLE:
					opModesService.disableOperationalModes(Collections.singleton(opMode));
					opModeActive = false;
					reconcile();
					resultParams.put(PARAM_SERVICE_RESULT, currentStatus());
					break;
				case ACTION_RESTART:
					resultParams.put(PARAM_SERVICE_RESULT, executeAction(ACTION_RESTART));
					break;
				default:
					resultParams.put(PARAM_MESSAGE,
							getMessageSource().getMessage("error.unsupportedAction",
									new Object[] { action }, "Unsupported action.",
									Locale.getDefault()));
					resultState = InstructionState.Declined;
			}
		} catch ( Exception e ) {
			resultParams.put(PARAM_MESSAGE, e.toString());
			resultState = InstructionState.Declined;
		}
		return InstructionUtils.createStatus(instruction, resultState, Instant.now(), resultParams);
	}

	private static long parseDuration(@Nullable String value) {
		if ( value == null || value.isEmpty() ) {
			return 0L;
		}
		try {
			return Long.parseLong(value.trim());
		} catch ( NumberFormatException e ) {
			throw new IllegalArgumentException("Invalid duration [" + value + "]: not a number.");
		}
	}

	/**
	 * Enable the operational mode, and so the radio, for a period of time.
	 *
	 * @param requestedSeconds
	 *        the requested duration, in seconds; {@literal 0} or less for the
	 *        default duration; capped at the maximum duration
	 */
	public synchronized void enableForSeconds(long requestedSeconds) {
		long seconds = (requestedSeconds <= 0 ? defaultDurationSeconds : requestedSeconds);
		if ( maxDurationSeconds > 0 && seconds > maxDurationSeconds ) {
			seconds = maxDurationSeconds;
		}
		final Instant expire = clock.instant().plusSeconds(seconds);
		log.info("Enabling Bluetooth setup operational mode [{}] until {}", opMode, expire);
		opModesService.enableOperationalModes(Collections.singleton(opMode), expire);
		opModeActive = true;
		reconcile();
	}

	/**
	 * Get the desired radio state.
	 *
	 * @return {@literal true} if the radio should be on
	 */
	public synchronized boolean desiredState() {
		return (alwaysOn || opModeActive || (offlineTriggerEnabled && offline));
	}

	/**
	 * Apply the desired radio state, if it differs from the actual state.
	 *
	 * <p>
	 * The actual state is always queried from the OS so this is idempotent and
	 * self-healing.
	 * </p>
	 */
	public synchronized void reconcile() {
		final boolean want = desiredState();
		final Status status = currentStatus();
		if ( status == null ) {
			log.warn("Unable to determine Bluetooth setup radio status; not changing state.");
			return;
		}
		if ( want != status.isRadioActive() ) {
			log.info("Turning Bluetooth setup radio {}", want ? "ON" : "OFF");
			executeAction(want ? ACTION_ENABLE : ACTION_DISABLE);
		} else if ( !want && (status.isDiscoverable() || status.isPowered()) ) {
			// peripheral is stopped but the adapter was left on (e.g. after boot or
			// an upgrade): turn it off
			log.info("Bluetooth adapter is on while the setup radio should be off; turning it off");
			executeAction(ACTION_DISABLE);
		}
	}

	private void reconcileQuietly() {
		try {
			reconcile();
		} catch ( Exception e ) {
			log.warn("Error reconciling Bluetooth setup radio state: {}", e.getMessage());
		}
	}

	private void scheduleWatchdog() {
		if ( taskScheduler == null || watchdogIntervalSeconds < 1 ) {
			return;
		}
		final Duration interval = Duration.ofSeconds(watchdogIntervalSeconds);
		watchdogFuture = taskScheduler.scheduleWithFixedDelay(this::watchdogTick,
				clock.instant().plus(interval), interval);
		watchdogScheduledIntervalSeconds = watchdogIntervalSeconds;
	}

	private void cancelWatchdog() {
		if ( watchdogFuture != null ) {
			watchdogFuture.cancel(false);
			watchdogFuture = null;
		}
	}

	/**
	 * Perform one offline watchdog check and reconcile the radio state.
	 *
	 * <p>
	 * The SolarNetwork connection is probed via the configured ping test. If no
	 * matching ping test exists the offline state is left unchanged, so the
	 * offline trigger never fires on nodes without the expected upload service.
	 * </p>
	 */
	public synchronized void watchdogTick() {
		try {
			if ( offlineTriggerEnabled && systemHealthService != null ) {
				updateOfflineState();
			}
		} catch ( Exception e ) {
			log.warn("Error checking SolarNetwork connectivity: {}", e.getMessage());
		}
		reconcileQuietly();
	}

	private void updateOfflineState() {
		final PingTestResults results = systemHealthService
				.performPingTests(Collections.singleton(pingTestIdRegex));
		if ( results == null || results.getResults() == null || results.getResults().isEmpty() ) {
			log.debug("No ping test matching [{}] available; offline state unchanged", pingTestIdRegex);
			return;
		}
		final boolean ok = results.isAllGood();
		final Instant now = clock.instant();
		if ( !ok ) {
			onlineSince = null;
			if ( offlineSince == null ) {
				offlineSince = now;
				log.info(
						"SolarNetwork connection lost; Bluetooth setup radio will turn on after {} minutes",
						offlineThresholdMinutes);
			}
			if ( !offline && !Duration.between(offlineSince, now)
					.minus(Duration.ofMinutes(offlineThresholdMinutes)).isNegative() ) {
				offline = true;
				log.info("SolarNetwork connection lost since {}; turning Bluetooth setup radio on",
						offlineSince);
			}
		} else {
			offlineSince = null;
			if ( offline ) {
				if ( onlineSince == null ) {
					onlineSince = now;
					log.info(
							"SolarNetwork connection restored; Bluetooth setup radio will turn off after {} minutes",
							onlineGraceMinutes);
				}
				if ( !Duration.between(onlineSince, now).minus(Duration.ofMinutes(onlineGraceMinutes))
						.isNegative() ) {
					log.info("SolarNetwork connection restored since {}; offline trigger cleared",
							onlineSince);
					offline = false;
					onlineSince = null;
				}
			}
		}
	}

	@Override
	public String getSettingUid() {
		return getUid();
	}

	@Override
	public List<SettingSpecifier> getSettingSpecifiers() {
		final Status status = currentStatus();
		final List<SettingSpecifier> result = new ArrayList<>(12);
		result.add(new BasicTitleSettingSpecifier("status", statusMessage(status)));

		BasicTextFieldSettingSpecifier enable = new BasicTextFieldSettingSpecifier("enableMinutes", "0");
		enable.setTransient(true);
		result.add(enable);

		result.add(new BasicToggleSettingSpecifier("alwaysOn", Boolean.FALSE));
		result.add(new BasicTextFieldSettingSpecifier("opMode", DEFAULT_OP_MODE));
		result.add(new BasicTextFieldSettingSpecifier("defaultDurationSeconds",
				String.valueOf(DEFAULT_DEFAULT_DURATION_SECONDS)));
		result.add(new BasicTextFieldSettingSpecifier("maxDurationSeconds",
				String.valueOf(DEFAULT_MAX_DURATION_SECONDS)));
		result.add(new BasicToggleSettingSpecifier("offlineTriggerEnabled", Boolean.TRUE));
		result.add(new BasicTextFieldSettingSpecifier("offlineThresholdMinutes",
				String.valueOf(DEFAULT_OFFLINE_THRESHOLD_MINUTES)));
		result.add(new BasicTextFieldSettingSpecifier("onlineGraceMinutes",
				String.valueOf(DEFAULT_ONLINE_GRACE_MINUTES)));
		result.add(new BasicTextFieldSettingSpecifier("watchdogIntervalSeconds",
				String.valueOf(DEFAULT_WATCHDOG_INTERVAL_SECONDS)));
		result.add(new BasicTextFieldSettingSpecifier("pingTestIdRegex", DEFAULT_PING_TEST_ID_REGEX));
		result.add(new BasicTextFieldSettingSpecifier("command", DEFAULT_COMMAND));
		return result;
	}

	private String statusMessage(@Nullable Status status) {
		MessageSource messageSource = getMessageSource();
		if ( messageSource == null ) {
			return "";
		}
		if ( status == null ) {
			return messageSource.getMessage("status.unknown", null, "Unknown", Locale.getDefault());
		}
		final List<String> reasons = new ArrayList<>(3);
		if ( status.isAlwaysOn() ) {
			reasons.add(
					messageSource.getMessage("status.alwaysOn", null, "always on", Locale.getDefault()));
		}
		if ( status.isOpModeActive() ) {
			Object expires = (status.getOpModeExpires() != null
					? Instant.ofEpochMilli(status.getOpModeExpires())
					: messageSource.getMessage("status.noExpiry", null, "no expiry",
							Locale.getDefault()));
			reasons.add(messageSource.getMessage("status.opMode", new Object[] { opMode, expires },
					"operational mode active", Locale.getDefault()));
		}
		if ( status.isOffline() ) {
			reasons.add(
					messageSource.getMessage("status.offline", new Object[] { status.getOfflineSince() },
							"SolarNetwork offline", Locale.getDefault()));
		}
		String state = messageSource.getMessage(status.isRadioActive() ? "status.on" : "status.off",
				null, status.isRadioActive() ? "On" : "Off", Locale.getDefault());
		if ( reasons.isEmpty() ) {
			return state;
		}
		return state + " (" + String.join("; ", reasons) + ")";
	}

	/**
	 * The Bluetooth setup radio status.
	 */
	public static final class Status {

		private final boolean radioActive;
		private final boolean discoverable;
		private final boolean powered;
		private final boolean installEnabled;
		private final boolean opModeActive;
		private final Long opModeExpires;
		private final boolean offline;
		private final Instant offlineSince;
		private final boolean alwaysOn;

		private Status(boolean radioActive, boolean discoverable, boolean powered,
				boolean installEnabled, boolean opModeActive, Long opModeExpires, boolean offline,
				Instant offlineSince, boolean alwaysOn) {
			super();
			this.radioActive = radioActive;
			this.discoverable = discoverable;
			this.powered = powered;
			this.installEnabled = installEnabled;
			this.opModeActive = opModeActive;
			this.opModeExpires = opModeExpires;
			this.offline = offline;
			this.offlineSince = offlineSince;
			this.alwaysOn = alwaysOn;
		}

		/**
		 * Get the radio active state.
		 *
		 * @return {@literal true} if the Bluetooth peripheral is running (and so
		 *         advertising)
		 */
		public boolean isRadioActive() {
			return radioActive;
		}

		/**
		 * Get the adapter discoverable state.
		 *
		 * @return {@literal true} if the Bluetooth adapter is discoverable
		 */
		public boolean isDiscoverable() {
			return discoverable;
		}

		/**
		 * Get the adapter powered state.
		 *
		 * @return {@literal true} if the Bluetooth adapter is powered
		 */
		public boolean isPowered() {
			return powered;
		}

		/**
		 * Get the OS install-enabled state.
		 *
		 * @return {@literal true} if the OS has the peripheral service enabled to
		 *         start at boot (legacy always-on install mode)
		 */
		public boolean isInstallEnabled() {
			return installEnabled;
		}

		/**
		 * Get the operational mode active state.
		 *
		 * @return {@literal true} if the configured operational mode is active
		 */
		public boolean isOpModeActive() {
			return opModeActive;
		}

		/**
		 * Get the operational mode expiration.
		 *
		 * @return the expiration, as milliseconds since the epoch, or
		 *         {@literal null} if not active or no expiration
		 */
		public Long getOpModeExpires() {
			return opModeExpires;
		}

		/**
		 * Get the offline trigger state.
		 *
		 * @return {@literal true} if the offline trigger is currently holding the
		 *         radio on
		 */
		public boolean isOffline() {
			return offline;
		}

		/**
		 * Get the time the SolarNetwork connection was first seen as lost.
		 *
		 * @return the time, or {@literal null} if the connection is up
		 */
		public Instant getOfflineSince() {
			return offlineSince;
		}

		/**
		 * Get the always-on setting.
		 *
		 * @return {@literal true} if the always-on setting is enabled
		 */
		public boolean isAlwaysOn() {
			return alwaysOn;
		}
	}

	/**
	 * Get the current status.
	 *
	 * @return the status, or {@literal null} if the OS status cannot be
	 *         determined
	 */
	public synchronized @Nullable Status currentStatus() {
		boolean active = false;
		boolean discoverable = false;
		boolean powered = false;
		boolean enabled = false;
		try {
			List<String> result = executeAction(ACTION_STATUS);
			if ( result != null ) {
				for ( String line : result ) {
					int idx = line.indexOf(':');
					if ( idx < 0 ) {
						continue;
					}
					String key = line.substring(0, idx).trim().toLowerCase(Locale.ENGLISH);
					String value = line.substring(idx + 1).trim();
					switch (key) {
						case "active":
							active = "true".equalsIgnoreCase(value);
							break;
						case "discoverable":
							discoverable = "true".equalsIgnoreCase(value);
							break;
						case "powered":
							powered = "true".equalsIgnoreCase(value);
							break;
						case "enabled":
							enabled = "true".equalsIgnoreCase(value);
							break;
						default:
							// ignore
					}
				}
			}
		} catch ( Throwable t ) {
			log.warn("Error getting current Bluetooth setup radio status: {}", t.getMessage());
			return null;
		}
		Long expires = null;
		if ( opModeActive ) {
			try {
				Map<String, Long> expirations = opModesService.activeOperationalModesWithExpirations();
				if ( expirations != null ) {
					expires = expirations.get(opMode);
				}
			} catch ( Exception e ) {
				log.debug("Error getting operational mode expirations: {}", e.getMessage());
			}
		}
		return new Status(active, discoverable, powered, enabled, opModeActive, expires,
				offlineTriggerEnabled && offline, offlineSince, alwaysOn);
	}

	private synchronized List<String> executeAction(final String action, String... args) {
		log.debug("Executing bluetooth action {}", action);
		List<String> cmd = new ArrayList<>(8);
		cmd.add(command);
		cmd.add(CONFIG_SERVICE);
		cmd.add(action);
		if ( args != null && args.length > 0 ) {
			for ( String arg : args ) {
				cmd.add(arg);
			}
		}
		List<String> result = new ArrayList<>(8);
		ProcessBuilder pb = new ProcessBuilder(cmd);
		try {
			Process pr = pb.start();
			BufferedReader in = new BufferedReader(new InputStreamReader(pr.getInputStream()));
			String line = null;
			while ( (line = in.readLine()) != null ) {
				result.add(line);
			}

			BufferedReader err = new BufferedReader(new InputStreamReader(pr.getErrorStream()));
			StringBuilder buf = new StringBuilder();
			line = null;
			while ( (line = err.readLine()) != null ) {
				if ( buf.length() > 0 ) {
					buf.append('\n');
				}
				buf.append(line);
			}

			if ( !pr.waitFor(COMMAND_TIMEOUT_SECONDS, TimeUnit.SECONDS) ) {
				pr.destroyForcibly();
				throw new RuntimeException("Bluetooth action " + action + " timed out after "
						+ COMMAND_TIMEOUT_SECONDS + "s.");
			}
			final int exitCode = pr.exitValue();
			if ( exitCode != 0 ) {
				throw new RuntimeException("Bluetooth action " + action + " failed with exit code "
						+ exitCode + (buf.length() > 0 ? ": " + buf : "."));
			}
			if ( buf.length() > 0 ) {
				log.warn("Bluetooth action {} reported: {}", action, buf);
			}
			return result;
		} catch ( IOException e ) {
			throw new RuntimeException(e);
		} catch ( InterruptedException e ) {
			Thread.currentThread().interrupt();
			throw new RuntimeException("Interrupted waiting for bluetooth action " + action, e);
		}
	}

	/**
	 * Set the clock to use.
	 *
	 * @param clock
	 *        the clock to set; defaults to the system UTC clock
	 */
	public void setClock(Clock clock) {
		if ( clock != null ) {
			this.clock = clock;
		}
	}

	/**
	 * Set the command to use.
	 *
	 * @param command
	 *        the command to set; defaults to {@link #DEFAULT_COMMAND}
	 */
	public void setCommand(String command) {
		this.command = command;
	}

	/**
	 * Get the operational mode that turns the radio on.
	 *
	 * @return the mode name
	 */
	public String getOpMode() {
		return opMode;
	}

	/**
	 * Set the operational mode that turns the radio on.
	 *
	 * @param opMode
	 *        the mode name to set; defaults to {@link #DEFAULT_OP_MODE}
	 */
	public void setOpMode(String opMode) {
		if ( opMode != null && !opMode.isEmpty() ) {
			this.opMode = opMode.trim().toLowerCase(Locale.ENGLISH);
		}
	}

	/**
	 * Get the always-on setting.
	 *
	 * @return {@literal true} to keep the radio on at all times
	 */
	public boolean isAlwaysOn() {
		return alwaysOn;
	}

	/**
	 * Set the always-on setting.
	 *
	 * @param alwaysOn
	 *        {@literal true} to keep the radio on at all times
	 */
	public void setAlwaysOn(boolean alwaysOn) {
		this.alwaysOn = alwaysOn;
	}

	/**
	 * Get the offline trigger enabled setting.
	 *
	 * @return {@literal true} to turn the radio on while SolarNetwork is
	 *         unreachable
	 */
	public boolean isOfflineTriggerEnabled() {
		return offlineTriggerEnabled;
	}

	/**
	 * Set the offline trigger enabled setting.
	 *
	 * @param offlineTriggerEnabled
	 *        {@literal true} to turn the radio on while SolarNetwork is
	 *        unreachable
	 */
	public void setOfflineTriggerEnabled(boolean offlineTriggerEnabled) {
		this.offlineTriggerEnabled = offlineTriggerEnabled;
	}

	/**
	 * Get the offline threshold.
	 *
	 * @return the minutes SolarNetwork must be unreachable before the radio
	 *         turns on
	 */
	public int getOfflineThresholdMinutes() {
		return offlineThresholdMinutes;
	}

	/**
	 * Set the offline threshold.
	 *
	 * @param offlineThresholdMinutes
	 *        the minutes SolarNetwork must be unreachable before the radio
	 *        turns on
	 */
	public void setOfflineThresholdMinutes(int offlineThresholdMinutes) {
		this.offlineThresholdMinutes = offlineThresholdMinutes;
	}

	/**
	 * Get the online grace period.
	 *
	 * @return the minutes to keep the radio on after SolarNetwork becomes
	 *         reachable again
	 */
	public int getOnlineGraceMinutes() {
		return onlineGraceMinutes;
	}

	/**
	 * Set the online grace period.
	 *
	 * @param onlineGraceMinutes
	 *        the minutes to keep the radio on after SolarNetwork becomes
	 *        reachable again
	 */
	public void setOnlineGraceMinutes(int onlineGraceMinutes) {
		this.onlineGraceMinutes = onlineGraceMinutes;
	}

	/**
	 * Get the watchdog interval.
	 *
	 * @return the seconds between connectivity checks
	 */
	public int getWatchdogIntervalSeconds() {
		return watchdogIntervalSeconds;
	}

	/**
	 * Set the watchdog interval.
	 *
	 * @param watchdogIntervalSeconds
	 *        the seconds between connectivity checks; {@literal 0} disables
	 *        the watchdog
	 */
	public void setWatchdogIntervalSeconds(int watchdogIntervalSeconds) {
		this.watchdogIntervalSeconds = watchdogIntervalSeconds;
	}

	/**
	 * Get the ping test ID regular expression.
	 *
	 * @return the regular expression matching the ping test(s) that indicate
	 *         SolarNetwork connectivity
	 */
	public String getPingTestIdRegex() {
		return pingTestIdRegex;
	}

	/**
	 * Set the ping test ID regular expression.
	 *
	 * @param pingTestIdRegex
	 *        the regular expression matching the ping test(s) that indicate
	 *        SolarNetwork connectivity; defaults to
	 *        {@link #DEFAULT_PING_TEST_ID_REGEX}
	 */
	public void setPingTestIdRegex(String pingTestIdRegex) {
		if ( pingTestIdRegex != null && !pingTestIdRegex.isEmpty() ) {
			this.pingTestIdRegex = pingTestIdRegex;
		}
	}

	/**
	 * Get the default enable duration.
	 *
	 * @return the seconds to enable the radio for when no duration is given
	 */
	public long getDefaultDurationSeconds() {
		return defaultDurationSeconds;
	}

	/**
	 * Set the default enable duration.
	 *
	 * @param defaultDurationSeconds
	 *        the seconds to enable the radio for when no duration is given
	 */
	public void setDefaultDurationSeconds(long defaultDurationSeconds) {
		this.defaultDurationSeconds = defaultDurationSeconds;
	}

	/**
	 * Get the maximum enable duration.
	 *
	 * @return the maximum seconds the radio can be enabled for in one request
	 */
	public long getMaxDurationSeconds() {
		return maxDurationSeconds;
	}

	/**
	 * Set the maximum enable duration.
	 *
	 * @param maxDurationSeconds
	 *        the maximum seconds the radio can be enabled for in one request;
	 *        {@literal 0} for no limit
	 */
	public void setMaxDurationSeconds(long maxDurationSeconds) {
		this.maxDurationSeconds = maxDurationSeconds;
	}

	/**
	 * Set the transient enable duration.
	 *
	 * <p>
	 * This is a transient setting: when set to a positive value the radio is
	 * enabled for that many minutes and the value is reset to {@literal 0}.
	 * </p>
	 *
	 * @param enableMinutes
	 *        the minutes to enable the radio for
	 */
	public void setEnableMinutes(int enableMinutes) {
		this.enableMinutes = enableMinutes;
	}

}
