/* ==================================================================
 * BluetoothSetupConfigurationTests.java - 6/10/2026 8:30:00 AM
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

package net.solarnetwork.node.setup.bluetooth.test;

import static org.easymock.EasyMock.anyObject;
import static org.easymock.EasyMock.capture;
import static org.easymock.EasyMock.eq;
import static org.easymock.EasyMock.expect;
import static org.easymock.EasyMock.replay;
import static org.easymock.EasyMock.verify;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.Assert.assertThat;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.easymock.Capture;
import org.easymock.EasyMock;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.osgi.service.event.Event;
import org.springframework.context.support.ResourceBundleMessageSource;
import org.springframework.util.FileCopyUtils;
import net.solarnetwork.domain.InstructionStatus.InstructionState;
import net.solarnetwork.node.reactor.Instruction;
import net.solarnetwork.node.reactor.InstructionHandler;
import net.solarnetwork.node.reactor.InstructionStatus;
import net.solarnetwork.node.reactor.InstructionUtils;
import net.solarnetwork.node.service.OperationalModesService;
import net.solarnetwork.node.service.SystemHealthService;
import net.solarnetwork.node.service.SystemHealthService.PingTestResults;
import net.solarnetwork.node.setup.bluetooth.BluetoothSetupConfiguration;
import net.solarnetwork.node.setup.bluetooth.BluetoothSetupConfiguration.Status;
import net.solarnetwork.service.PingTest;
import net.solarnetwork.service.PingTestResult;
import net.solarnetwork.service.PingTestResultDisplay;

/**
 * Test cases for the {@link BluetoothSetupConfiguration} class.
 *
 * @author elijah
 * @version 1.0
 */
public class BluetoothSetupConfigurationTests {

	private static final String OP_MODE = "bt-setup";
	private static final Instant START = Instant.parse("2026-10-06T00:00:00Z");

	private OperationalModesService opModesService;
	private SystemHealthService systemHealthService;
	private BluetoothSetupConfiguration service;
	private File tmpFile;
	private File logFile;
	private File stateFile;

	@Before
	public void setup() throws IOException {
		opModesService = EasyMock.createMock(OperationalModesService.class);
		systemHealthService = EasyMock.createMock(SystemHealthService.class);
		// no task scheduler: reconcile runs inline, which keeps the tests deterministic
		service = new BluetoothSetupConfiguration(opModesService, null, systemHealthService);

		// copy script to file so test can run via JAR (i.e. Ant)
		tmpFile = File.createTempFile("solarcfg-", ".sh");
		tmpFile.setExecutable(true, true);
		FileCopyUtils.copy(getClass().getResourceAsStream("solarcfg.sh"), new FileOutputStream(tmpFile));
		logFile = new File(tmpFile.getAbsolutePath() + ".log");
		stateFile = new File(tmpFile.getAbsolutePath() + ".state");
		service.setCommand(tmpFile.getAbsolutePath());
		service.setClock(Clock.fixed(START, ZoneOffset.UTC));

		ResourceBundleMessageSource msgSource = new ResourceBundleMessageSource();
		msgSource.setBasenames(BluetoothSetupConfiguration.class.getName());
		service.setMessageSource(msgSource);
	}

	@After
	public void teardown() {
		verify(opModesService, systemHealthService);
		if ( tmpFile != null ) {
			tmpFile.delete();
		}
		if ( logFile != null ) {
			logFile.delete();
		}
		if ( stateFile != null ) {
			stateFile.delete();
		}
	}

	private void replayAll() {
		replay(opModesService, systemHealthService);
	}

	private List<String> actions() throws IOException {
		if ( !logFile.exists() ) {
			return Collections.emptyList();
		}
		return Files.readAllLines(logFile.toPath()).stream()
				.map(l -> l.split(" ")[1]).filter(a -> !"status".equals(a)).toList();
	}

	private boolean radioState() throws IOException {
		return stateFile.exists() && "true".equals(Files.readString(stateFile.toPath()).trim());
	}

	private Instant now = START;

	private void advanceClock(Duration d) {
		now = now.plus(d);
		service.setClock(Clock.fixed(now, ZoneOffset.UTC));
	}

	private Instruction instruction(String action, Map<String, String> extra) {
		Map<String, String> params = new LinkedHashMap<>();
		params.put(InstructionHandler.PARAM_SERVICE, BluetoothSetupConfiguration.BLUETOOTH_SERVICE_NAME);
		if ( action != null ) {
			params.put(BluetoothSetupConfiguration.PARAM_ACTION, action);
		}
		if ( extra != null ) {
			params.putAll(extra);
		}
		return InstructionUtils.createLocalInstruction(InstructionHandler.TOPIC_SYSTEM_CONFIGURE, params);
	}

	private Event modesEvent(String... activeModes) {
		Map<String, Object> props = new HashMap<>();
		props.put(OperationalModesService.EVENT_PARAM_ACTIVE_OPERATIONAL_MODES, Set.of(activeModes));
		return new Event(OperationalModesService.EVENT_TOPIC_OPERATIONAL_MODES_CHANGED, props);
	}

	private PingTestResults pingResults(Boolean success) {
		if ( success == null ) {
			return new PingTestResults(Instant.now(), Collections.emptyMap());
		}
		PingTest test = new PingTest() {

			@Override
			public String getPingTestId() {
				return "net.solarnetwork.node.upload.mqtt.MqttUploadService";
			}

			@Override
			public String getPingTestName() {
				return "SolarIn/MQTT";
			}

			@Override
			public long getPingTestMaximumExecutionMilliseconds() {
				return 1000;
			}

			@Override
			public Result performPingTest() throws Exception {
				return new PingTestResult(success, success ? "OK" : "No MQTT connection available.");
			}
		};
		Map<String, PingTestResultDisplay> results = new HashMap<>();
		results.put(test.getPingTestId(), new PingTestResultDisplay(test,
				new PingTestResult(success, success ? "OK" : "No MQTT connection available."),
				Instant.now()));
		return new PingTestResults(Instant.now(), results);
	}

	@Test
	public void handlesTopic() {
		replayAll();
		assertThat(service.handlesTopic(InstructionHandler.TOPIC_SYSTEM_CONFIGURE), is(true));
		assertThat(service.handlesTopic("Other"), is(false));
	}

	@Test
	public void instruction_otherService_ignored() {
		replayAll();
		Map<String, String> params = new LinkedHashMap<>();
		params.put(InstructionHandler.PARAM_SERVICE, "/setup/network/wifi");
		Instruction instr = InstructionUtils
				.createLocalInstruction(InstructionHandler.TOPIC_SYSTEM_CONFIGURE, params);
		assertThat(service.processInstruction(instr), is(nullValue()));
	}

	@Test
	public void instruction_unsupportedAction_declined() {
		replayAll();
		InstructionStatus status = service.processInstruction(instruction("explode", null));
		assertThat(status, is(notNullValue()));
		assertThat(status.getInstructionState(), is(InstructionState.Declined));
		assertThat(status.getResultParameters().get(InstructionHandler.PARAM_MESSAGE),
				is(notNullValue()));
	}

	@Test
	public void instruction_status() throws IOException {
		replayAll();
		InstructionStatus status = service.processInstruction(instruction(null, null));
		assertThat(status.getInstructionState(), is(InstructionState.Completed));
		Object result = status.getResultParameters().get(InstructionHandler.PARAM_SERVICE_RESULT);
		assertThat(result, instanceOf(Status.class));
		Status s = (Status) result;
		assertThat(s.isRadioActive(), is(false));
		assertThat(s.isOpModeActive(), is(false));
		assertThat(s.isOffline(), is(false));
		assertThat("status does not change the radio", actions(), is(empty()));
	}

	@Test
	public void instruction_enable_defaultDuration() throws IOException {
		service.setDefaultDurationSeconds(600);
		Capture<Instant> expireCapture = Capture.newInstance();
		expect(opModesService.enableOperationalModes(eq(Set.of(OP_MODE)), capture(expireCapture)))
				.andReturn(Set.of(OP_MODE));
		expect(opModesService.activeOperationalModesWithExpirations())
				.andReturn(Map.of(OP_MODE, START.plusSeconds(600).toEpochMilli())).anyTimes();
		replayAll();

		InstructionStatus status = service.processInstruction(instruction("enable", null));

		assertThat(status.getInstructionState(), is(InstructionState.Completed));
		assertThat(expireCapture.getValue(), is(START.plusSeconds(600)));
		Status s = (Status) status.getResultParameters().get(InstructionHandler.PARAM_SERVICE_RESULT);
		assertThat(s.isRadioActive(), is(true));
		assertThat(s.isOpModeActive(), is(true));
		assertThat(s.getOpModeExpires(), is(START.plusSeconds(600).toEpochMilli()));
		assertThat(actions(), contains("enable"));
		assertThat(radioState(), is(true));
	}

	@Test
	public void instruction_enable_capsDuration() throws IOException {
		service.setMaxDurationSeconds(100);
		Capture<Instant> expireCapture = Capture.newInstance();
		expect(opModesService.enableOperationalModes(eq(Set.of(OP_MODE)), capture(expireCapture)))
				.andReturn(Set.of(OP_MODE));
		expect(opModesService.activeOperationalModesWithExpirations()).andReturn(Map.of()).anyTimes();
		replayAll();

		service.processInstruction(instruction("enable", Map.of("duration", "99999")));

		assertThat(expireCapture.getValue(), is(START.plusSeconds(100)));
	}

	@Test
	public void instruction_enable_invalidDuration_declined() throws IOException {
		replayAll();
		InstructionStatus status = service
				.processInstruction(instruction("enable", Map.of("duration", "soon")));
		assertThat(status.getInstructionState(), is(InstructionState.Declined));
		assertThat(actions(), is(empty()));
	}

	@Test
	public void instruction_disable() throws IOException {
		// GIVEN radio currently on via op mode
		expect(opModesService.enableOperationalModes(eq(Set.of(OP_MODE)), anyObject()))
				.andReturn(Set.of(OP_MODE));
		expect(opModesService.activeOperationalModesWithExpirations()).andReturn(Map.of()).anyTimes();
		expect(opModesService.disableOperationalModes(Set.of(OP_MODE))).andReturn(Set.of());
		replayAll();
		service.processInstruction(instruction("enable", null));
		assertThat(radioState(), is(true));

		// WHEN
		InstructionStatus status = service.processInstruction(instruction("disable", null));

		// THEN
		assertThat(status.getInstructionState(), is(InstructionState.Completed));
		Status s = (Status) status.getResultParameters().get(InstructionHandler.PARAM_SERVICE_RESULT);
		assertThat(s.isRadioActive(), is(false));
		assertThat(s.isOpModeActive(), is(false));
		assertThat(actions(), contains("enable", "disable"));
		assertThat(radioState(), is(false));
	}

	@Test
	public void instruction_restart() throws IOException {
		replayAll();
		InstructionStatus status = service.processInstruction(instruction("restart", null));
		assertThat(status.getInstructionState(), is(InstructionState.Completed));
		assertThat(actions(), contains("restart"));
	}

	@Test
	public void modesChanged_enablesThenDisables() throws IOException {
		expect(opModesService.activeOperationalModesWithExpirations()).andReturn(Map.of()).anyTimes();
		replayAll();

		service.handleEvent(modesEvent(OP_MODE, "other"));
		assertThat(radioState(), is(true));

		service.handleEvent(modesEvent("other"));
		assertThat(radioState(), is(false));

		assertThat(actions(), contains("enable", "disable"));
	}

	@Test
	public void modesChanged_otherMode_ignored() throws IOException {
		replayAll();
		service.handleEvent(modesEvent("quiet"));
		assertThat(actions(), is(empty()));
		assertThat(radioState(), is(false));
	}

	@Test
	public void reconcile_idempotent() throws IOException {
		expect(opModesService.activeOperationalModesWithExpirations()).andReturn(Map.of()).anyTimes();
		replayAll();

		service.handleEvent(modesEvent(OP_MODE));
		service.handleEvent(modesEvent(OP_MODE));
		service.reconcile();
		service.reconcile();

		assertThat("only one enable despite repeated events", actions(), contains("enable"));
	}

	@Test
	public void alwaysOn_turnsRadioOn() throws IOException {
		replayAll();
		service.setAlwaysOn(true);
		service.reconcile();
		assertThat(actions(), contains("enable"));
		assertThat(radioState(), is(true));
	}

	@Test
	public void offline_triggersOnlyAfterThreshold() throws IOException {
		service.setOfflineThresholdMinutes(2);
		expect(systemHealthService.performPingTests(anyObject())).andReturn(pingResults(false))
				.anyTimes();
		replayAll();

		service.watchdogTick();
		assertThat("not yet offline", radioState(), is(false));

		advanceClock(Duration.ofMinutes(1));
		service.watchdogTick();
		assertThat("still under threshold", radioState(), is(false));

		advanceClock(Duration.ofMinutes(1));
		service.watchdogTick();
		assertThat("threshold reached", radioState(), is(true));
		assertThat(actions(), contains("enable"));

		Status s = service.currentStatus();
		assertThat(s.isOffline(), is(true));
		assertThat(s.getOfflineSince(), is(START));
	}

	@Test
	public void offline_clearsOnlyAfterGrace() throws IOException {
		service.setOfflineThresholdMinutes(1);
		service.setOnlineGraceMinutes(3);
		expect(systemHealthService.performPingTests(anyObject())).andReturn(pingResults(false))
				.times(2);
		expect(systemHealthService.performPingTests(anyObject())).andReturn(pingResults(true))
				.anyTimes();
		replayAll();

		service.watchdogTick();
		advanceClock(Duration.ofMinutes(1));
		service.watchdogTick();
		assertThat(radioState(), is(true));

		// back online
		advanceClock(Duration.ofMinutes(1));
		service.watchdogTick();
		assertThat("grace period started", radioState(), is(true));

		advanceClock(Duration.ofMinutes(2));
		service.watchdogTick();
		assertThat("still within grace", radioState(), is(true));

		advanceClock(Duration.ofMinutes(1));
		service.watchdogTick();
		assertThat("grace elapsed", radioState(), is(false));
		assertThat(actions(), contains("enable", "disable"));
	}

	@Test
	public void offline_graceResetsOnNewFailure() throws IOException {
		service.setOfflineThresholdMinutes(1);
		service.setOnlineGraceMinutes(2);
		expect(systemHealthService.performPingTests(anyObject())).andReturn(pingResults(false))
				.times(2);
		expect(systemHealthService.performPingTests(anyObject())).andReturn(pingResults(true))
				.times(1);
		expect(systemHealthService.performPingTests(anyObject())).andReturn(pingResults(false))
				.times(1);
		expect(systemHealthService.performPingTests(anyObject())).andReturn(pingResults(true))
				.times(2);
		replayAll();

		service.watchdogTick();
		advanceClock(Duration.ofMinutes(1));
		service.watchdogTick();
		assertThat(radioState(), is(true));

		advanceClock(Duration.ofMinutes(1));
		service.watchdogTick(); // online, grace starts
		advanceClock(Duration.ofMinutes(1));
		service.watchdogTick(); // offline again, grace reset
		advanceClock(Duration.ofMinutes(1));
		service.watchdogTick(); // online, grace restarts
		advanceClock(Duration.ofMinutes(1));
		service.watchdogTick(); // only 1 minute of grace since restart
		assertThat("grace restarted so radio still on", radioState(), is(true));
		assertThat(actions(), contains("enable"));
	}

	@Test
	public void offline_noMatchingTest_neverTriggers() throws IOException {
		service.setOfflineThresholdMinutes(1);
		expect(systemHealthService.performPingTests(anyObject())).andReturn(pingResults(null))
				.anyTimes();
		replayAll();

		service.watchdogTick();
		advanceClock(Duration.ofMinutes(10));
		service.watchdogTick();

		assertThat(radioState(), is(false));
		assertThat(actions(), is(empty()));
	}

	@Test
	public void offline_triggerDisabled_neverTriggers() throws IOException {
		service.setOfflineTriggerEnabled(false);
		service.setOfflineThresholdMinutes(1);
		replayAll();

		service.watchdogTick();
		advanceClock(Duration.ofMinutes(10));
		service.watchdogTick();

		assertThat(radioState(), is(false));
		assertThat(actions(), is(empty()));
	}

	@Test
	public void status_commandFailure_returnsNull() throws IOException {
		replayAll();
		service.setCommand("/nonexistent/solarcfg");
		assertThat(service.currentStatus(), is(nullValue()));
	}

	@Test
	public void executeAction_nonZeroExit_declined() throws IOException {
		replayAll();
		// the fake script exits 3 from "restart" when a ".fail" marker exists; the
		// non-zero exit must surface as a Declined instruction, not Completed
		File failFile = new File(tmpFile.getAbsolutePath() + ".fail");
		try {
			Files.writeString(failFile.toPath(), "");
			InstructionStatus status = service.processInstruction(instruction("restart", null));
			assertThat(status.getInstructionState(), is(InstructionState.Declined));
			assertThat(String.valueOf(status.getResultParameters().get(InstructionHandler.PARAM_MESSAGE)),
					org.hamcrest.Matchers.containsString("exit code 3"));
		} finally {
			failFile.delete();
		}
	}

	@Test
	public void settings() {
		replayAll();
		List<net.solarnetwork.settings.SettingSpecifier> specs = service.getSettingSpecifiers();
		assertThat(specs, is(notNullValue()));
		assertThat(specs.size(), equalTo(12));
	}

}
