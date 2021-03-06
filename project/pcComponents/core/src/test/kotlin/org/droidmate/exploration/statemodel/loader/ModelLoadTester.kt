package org.droidmate.exploration.statemodel.loader

import kotlinx.coroutines.experimental.runBlocking
import org.droidmate.test_tools.DroidmateTestCase
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import org.junit.runners.MethodSorters
import org.droidmate.configuration.ConfigProperties.ModelProperties
import org.droidmate.exploration.statemodel.*
import java.util.*

private val config = ModelConfig("JUnit", true)

/** verify the ModelLoader correctly initializes/loads a model by using
 * - mocked model (mock the dump-file content read)
 * - loading real model dump files & verifying resulting model
 * - dumping and loading the same model => verify equality
 * - test watcher are correctly updated during model loading
 *
 * REMARK for mockito to work it is essential that all mocked/spied classes and methods have the `open` modifier
 */
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
@RunWith(JUnit4::class)
class ModelLoadTester: DroidmateTestCase(), TestModel by DefaultTestModel(), ModelLoaderTI by ModelLoaderT(config) {
	private val testState = StateData(setOf(testWidget), homeScreen = false, topPackage = testWidget.packageName)
	private val states = listOf(testState, StateData.emptyState)

	@Test
	fun widgetParsingTest() = runBlocking{
		expect(parseWidget(testWidget)!!.dataString(config[ModelProperties.dump.sep]),testWidget.dataString(config[ModelProperties.dump.sep]))
	}

	@Test fun loadTest(){
		val actions = listOf(createTestAction(testWidget, testState.stateId))
		val model = execute(listOf(actions),states)
		runBlocking {
			expect(model.getState(testState.stateId)!!.widgetsDump("\t"),testState.widgetsDump("\t"))
			model.getWidgets().let { widgets ->
				expect(widgets.size, 1)
				expect(widgets.first().dataString("\t"),testWidget.dataString("\t"))
			}
		}
		model.getPaths().let{ traces ->
			expect(traces.size,1)
			traces.first().getActions().let{ _actions ->
				expect(_actions.size,1)
				expect(_actions.first().actionString(),actions.first().actionString())
			}
		}
		println(model)
	}

	@Test fun loadMultipleActionsTest(){
		val actions = LinkedList<ActionData>().apply {
			add(createTestAction(nextState = testState.stateId, actionType = "ResetAppExplorationAction"))
			for(i in 1..5)
				add(createTestAction(oldState = testState.stateId, nextState = testState.stateId, actionType = "$i test action", targetWidget = testWidget))
			add(createTestAction(oldState = testState.stateId, actionType = "last null action"))
		}
		val model = execute(listOf(actions),states)

		println(model)
		model.getPaths().let{ traces ->
			expect(traces.size,1)
			traces.first().getActions().let{ _actions ->
				expect(_actions.size,7)
				_actions.forEachIndexed { index, action ->
					println(action.actionString())
					expect(action.actionString(), actions[index].actionString())
				}
			}
		}
	}

//	@Test fun debugStateParsingTest(){
//		testTraces = emptyList()
//		testStates = emptyList()
//		val state = runBlocking{
//			parseState(idFromString("fa5d6ec4-129e-cde6-cfbf-eb837096de60_829a5484-73d6-ba71-57fc-d143d1cecaeb")) }
//		println(state)
//	}
	//test dumped state f7acfd36-d72b-3b6b-cd0f-79f635234be5, 3243aafc-d785-0cc4-07a6-27bc357d1d3e


}

