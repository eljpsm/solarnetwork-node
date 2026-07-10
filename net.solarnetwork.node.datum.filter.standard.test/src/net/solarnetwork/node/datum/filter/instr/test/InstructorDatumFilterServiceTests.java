/* ==================================================================
 * InstructorDatumFilterServiceTests.java - 10/07/2026 7:37:28 am
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

package net.solarnetwork.node.datum.filter.instr.test;

import static net.solarnetwork.test.CommonTestUtils.randomString;
import static org.assertj.core.api.BDDAssertions.from;
import static org.assertj.core.api.BDDAssertions.then;
import static org.assertj.core.api.InstanceOfAssertFactories.map;
import static org.assertj.core.api.InstanceOfAssertFactories.type;
import static org.easymock.EasyMock.capture;
import static org.easymock.EasyMock.expect;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.easymock.Capture;
import org.easymock.EasyMock;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import net.solarnetwork.common.expr.spel.SpelExpressionService;
import net.solarnetwork.domain.InstructionStatus.InstructionState;
import net.solarnetwork.domain.datum.DatumSamples;
import net.solarnetwork.domain.datum.DatumSamplesOperations;
import net.solarnetwork.domain.datum.DatumSamplesType;
import net.solarnetwork.node.dao.LocalStateDao;
import net.solarnetwork.node.datum.filter.instr.InstructorConfig;
import net.solarnetwork.node.datum.filter.instr.InstructorDatumFilterService;
import net.solarnetwork.node.domain.LocalState;
import net.solarnetwork.node.domain.datum.SimpleDatum;
import net.solarnetwork.node.reactor.Instruction;
import net.solarnetwork.node.reactor.InstructionExecutionService;
import net.solarnetwork.node.reactor.InstructionHandler;
import net.solarnetwork.node.reactor.InstructionUtils;
import net.solarnetwork.node.service.support.ExpressionConfig;
import net.solarnetwork.service.ExpressionService;
import net.solarnetwork.service.StaticOptionalService;
import net.solarnetwork.service.StaticOptionalServiceCollection;

/**
 * Test cases for the {@link InstructorDatumFilterService} class.
 *
 * @author matt
 * @version 1.0
 */
public class InstructorDatumFilterServiceTests {

	private static final String INPUT_SIGNAL_PROP_NAME = "presentSignal";
	private static final String SIGNAL_SERVICE_UID = "Signal Handler " + randomString();
	private static final String PRESENT_SIGNAL_INSTRUCTION_ID_LOCAL_STATE_KEY = "present-signal-instruction-id";
	private static final String INSTRUCTION_STATUS_RESULT_PARAM_NAME = "result";
	private static final String RESULT_PROP_NAME = "instructionResult";

	private InstructionExecutionService instructionExecutionService;
	private LocalStateDao localStateDao;
	private ExpressionService exprService;
	private InstructorDatumFilterService xform;

	@Before
	public void setup() {
		instructionExecutionService = EasyMock.createMock(InstructionExecutionService.class);
		localStateDao = EasyMock.createMock(LocalStateDao.class);
		xform = new InstructorDatumFilterService(
				new StaticOptionalService<>(instructionExecutionService));
		xform.setUid("Test");
		exprService = new SpelExpressionService();
		xform.setExpressionServices(new StaticOptionalServiceCollection<>(List.of(exprService)));
		xform.setLocalStateDao(new StaticOptionalService<>(localStateDao));
	}

	@After
	public void teardown() {
		EasyMock.verify(instructionExecutionService, localStateDao);
	}

	private void replayAll() {
		EasyMock.replay(instructionExecutionService, localStateDao);
	}

	private SimpleDatum createTestSimpleDatum(String sourceId, String prop, Number val) {
		SimpleDatum datum = SimpleDatum.nodeDatum(sourceId);
		datum.getSamples().putInstantaneousSampleValue(prop, val);
		return datum;
	}

	@Test
	public void generateDeferredInstruction_saveInstructionIdToLocalState() {
		// GIVEN
		final InstructorConfig config = new InstructorConfig();
		config.setTopic(InstructionHandler.TOPIC_SIGNAL);
		config.getPrecicate().setExpression("""
				%s == 1
				""".formatted(INPUT_SIGNAL_PROP_NAME));
		config.getPrecicate().setExpressionServiceId(exprService.getUid());

		final ExpressionConfig signalParamConfig = new ExpressionConfig();
		signalParamConfig.setName(InstructionHandler.PARAM_SERVICE);
		signalParamConfig.setExpression(SIGNAL_SERVICE_UID);

		final ExpressionConfig signalInputParamConfig = new ExpressionConfig();
		signalInputParamConfig.setName(InstructionHandler.PARAM_SERVICE_ARGUMENT);
		signalInputParamConfig.setExpression("""
				%s
				""".formatted(INPUT_SIGNAL_PROP_NAME));
		signalInputParamConfig.setExpressionServiceId(exprService.getUid());

		config.setParameters(new ExpressionConfig[] { signalParamConfig, signalInputParamConfig });

		final ExpressionConfig saveInstructionIdResponseConfig = new ExpressionConfig();
		saveInstructionIdResponseConfig.setExpression("""
				saveLocalState("%s", instruction.id)
				""".formatted(PRESENT_SIGNAL_INSTRUCTION_ID_LOCAL_STATE_KEY));
		saveInstructionIdResponseConfig.setExpressionServiceId(exprService.getUid());
		config.setResponses(new ExpressionConfig[] { saveInstructionIdResponseConfig });

		xform.setInstructorConfigs(new InstructorConfig[] { config });

		final Capture<Instruction> instructionCaptor = Capture.newInstance();
		expect(instructionExecutionService.executeInstruction(capture(instructionCaptor)))
				.andAnswer(() -> {
					final Instruction instr = (Instruction) EasyMock.getCurrentArguments()[0];
					return InstructionUtils.createStatus(instr, InstructionState.Completed);
				});

		final Capture<LocalState> localStateCaptor = Capture.newInstance();
		expect(localStateDao.compareAndChange(capture(localStateCaptor))).andAnswer(() -> {
			return (LocalState) EasyMock.getCurrentArguments()[0];
		});

		// WHEN
		replayAll();
		final SimpleDatum d = createTestSimpleDatum(randomString(), INPUT_SIGNAL_PROP_NAME, 1);
		final Map<String, Object> parameters = new LinkedHashMap<>();
		final DatumSamplesOperations result = xform.filter(d, d.getSamples(), parameters);

		// THEN
		// @formatter:off
		then(result)
			.as("Result provided")
			.isNotNull()
			;

		final Instruction instruction = instructionCaptor.getValue();
		then(instruction)
			.as("Instruction generated")
			.isNotNull()
			.as("Instruction topic from config")
			.returns(config.getTopic(), from(Instruction::getTopic))
			.extracting(Instruction::getParameterMap, map(String.class, String.class))
			.containsExactlyInAnyOrderEntriesOf(Map.of(
				InstructionHandler.PARAM_SERVICE, SIGNAL_SERVICE_UID,
				InstructionHandler.PARAM_SERVICE_ARGUMENT, "1"
			))
			;

		then(localStateCaptor.getValue())
			.as("LocalState for instruction ID saved")
			.isNotNull()
			.as("LocalState key from expression")
			.returns(PRESENT_SIGNAL_INSTRUCTION_ID_LOCAL_STATE_KEY, from(LocalState::getKey))
			.as("LocalState value is instruction ID")
			.returns(instruction.getId(), from(LocalState::getValue))
			;
		// @formatter:on
	}

	@Test
	public void generateDeferredInstruction_saveResultAsDatumProp() {
		// GIVEN
		final InstructorConfig config = new InstructorConfig();
		config.setTopic(InstructionHandler.TOPIC_SIGNAL);
		config.getPrecicate().setExpression("""
				%s == 1
				""".formatted(INPUT_SIGNAL_PROP_NAME));
		config.getPrecicate().setExpressionServiceId(exprService.getUid());

		final ExpressionConfig signalParamConfig = new ExpressionConfig();
		signalParamConfig.setName(InstructionHandler.PARAM_SERVICE);
		signalParamConfig.setExpression(SIGNAL_SERVICE_UID);

		config.setParameters(new ExpressionConfig[] { signalParamConfig });

		final ExpressionConfig resultResponseConfig = new ExpressionConfig();
		resultResponseConfig.setPropertyKey(RESULT_PROP_NAME);
		resultResponseConfig.setPropertyType(DatumSamplesType.Status);
		resultResponseConfig.setExpression("""
				instructionResult != null && instructionResult.completed
				? instructionResult.resultParameters["%s"]
				: null
				""".formatted(INSTRUCTION_STATUS_RESULT_PARAM_NAME));
		resultResponseConfig.setExpressionServiceId(exprService.getUid());
		config.setResponses(new ExpressionConfig[] { resultResponseConfig });

		xform.setInstructorConfigs(new InstructorConfig[] { config });

		final String instructionStatusResult = randomString();
		final Capture<Instruction> instructionCaptor = Capture.newInstance();
		expect(instructionExecutionService.executeInstruction(capture(instructionCaptor)))
				.andAnswer(() -> {
					final Instruction instr = (Instruction) EasyMock.getCurrentArguments()[0];
					return InstructionUtils.createStatus(instr, InstructionState.Completed,
							Map.of(INSTRUCTION_STATUS_RESULT_PARAM_NAME, instructionStatusResult));
				});

		// WHEN
		replayAll();
		final SimpleDatum d = createTestSimpleDatum(randomString(), INPUT_SIGNAL_PROP_NAME, 1);
		final Map<String, Object> parameters = new LinkedHashMap<>();
		final DatumSamplesOperations result = xform.filter(d, d.getSamples(), parameters);

		// THEN
		// @formatter:off
		then(result)
			.as("Result provided")
			.isNotNull()
			.as("New samples instance returned")
			.isNotSameAs(d.getSamples())
			.as("DatumSamples instance returned")
			.isInstanceOf(DatumSamples.class)
			.asInstanceOf(type(DatumSamples.class))
			.satisfies(s -> {
				then(s.getInstantaneous())
					.as("Given instantaneous datum properties remain")
					.containsExactlyInAnyOrderEntriesOf(d.getSamples().getInstantaneous())
					;
				then(s.getStatus())
					.as("Response expression result saved to status datum property")
					.containsExactlyInAnyOrderEntriesOf(Map.of(
						RESULT_PROP_NAME, instructionStatusResult
					))
					;
			})
			;

		final Instruction instruction = instructionCaptor.getValue();
		then(instruction)
			.as("Instruction generated")
			.isNotNull()
			.as("Instruction topic from config")
			.returns(config.getTopic(), from(Instruction::getTopic))
			.extracting(Instruction::getParameterMap, map(String.class, String.class))
			.containsExactlyInAnyOrderEntriesOf(Map.of(
				InstructionHandler.PARAM_SERVICE, SIGNAL_SERVICE_UID
			))
			;
		// @formatter:on
	}

}
