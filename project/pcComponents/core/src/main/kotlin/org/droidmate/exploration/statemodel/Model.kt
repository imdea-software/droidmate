// DroidMate, an automated execution generator for Android apps.
// Copyright (C) 2012-2018. Saarland University
//
// This program is free software: you can redistribute it and/or modify
// it under the terms of the GNU General Public License as published by
// the Free Software Foundation, either version 3 of the License, or
// (at your option) any later version.
//
// This program is distributed in the hope that it will be useful,
// but WITHOUT ANY WARRANTY; without even the implied warranty of
// MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
// GNU General Public License for more details.
//
// You should have received a copy of the GNU General Public License
// along with this program.  If not, see <http://www.gnu.org/licenses/>.
//
// Current Maintainers:
// Nataniel Borges Jr. <nataniel dot borges at cispa dot saarland>
// Jenny Hotzkow <jenny dot hotzkow at cispa dot saarland>
//
// Former Maintainers:
// Konrad Jamrozik <jamrozik at st dot cs dot uni-saarland dot de>
//
// web: www.droidmate.org

package org.droidmate.exploration.statemodel

import kotlinx.coroutines.experimental.*
import kotlinx.coroutines.experimental.channels.sendBlocking
import org.droidmate.configuration.ConfigProperties.ModelProperties
import org.droidmate.debug.debugT
import org.droidmate.exploration.statemodel.features.ModelFeature
import org.droidmate.exploration.statemodel.loader.ModelParser
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Paths
import java.nio.file.StandardOpenOption
import java.util.*
import kotlin.collections.HashSet
import kotlin.system.measureTimeMillis

internal operator fun UUID.plus(uuid: UUID?): UUID {
	return if(uuid == null) this
	else UUID(this.mostSignificantBits + uuid.mostSignificantBits, this.leastSignificantBits + uuid.mostSignificantBits)
}

/** s_* should be only used in sequential eContext as it currently does not handle parallelism*/
@Suppress("MemberVisibilityCanBePrivate")
class Model private constructor(val config: ModelConfig) {
	private val paths = LinkedList<Trace>()
	/** debugging counter do not use it in productive code, instead access the respective element set */
	private var nWidgets = 0
	/** debugging counter do not use it in productive code, instead access the respective element set */
	private var nStates = 0
	val modelJob = Job()
	val modelDumpJob = Job()


	fun initNewTrace(watcher: LinkedList<ModelFeature>,id: UUID = UUID.randomUUID()): Trace {
		return Trace(watcher,config, modelJob,id).also {actionTrace ->
			addTrace(actionTrace)
		}
	}

	private val states = CollectionActor(HashSet<StateData>(),"StateActor").create(modelJob)
	/** @return a view to the data (suspending function) */
	suspend fun getStates(): Set<StateData> = states.getAll<StateData,Set<StateData>>()
	@Suppress("unused")
			/** @return a view to the data (blocking function) */
	fun S_getStates(): Set<StateData> = runBlocking{ states.getAll<StateData,Set<StateData>>() }

	suspend fun addState(s: StateData){
		nStates +=1
		states.send(Add(s))
	}

	suspend fun getState(id: ConcreteId):StateData?{
//		println("call getStates")
		val states = getStates()
//		println("[${Thread.currentThread().name}] retrieved ${states.size} states")
		return states.find { it.stateId == id }
	}


	private val widgets = CollectionActor(HashSet<Widget>(), "WidgetActor").create(modelJob)

	suspend fun getWidgets(): Set<Widget>{
		return CompletableDeferred<Collection<Widget>>().let{ response ->
			widgets.send(GetAll(response))
			response.await() as Set
		}
	}

	fun S_addWidget(w: Widget) {
		nWidgets +=1
		widgets.sendBlocking(Add(w))
	}

	suspend fun addWidgets(w: Collection<Widget>) {
		nWidgets += w.size
		widgets.send(AddAll(w))
	}

	@Suppress("unused")
	fun getPaths(): List<Trace> = paths
	fun addTrace(t: Trace) = paths.add(t)

	/** use this function to find widgets with a specific [ConcreteId]
	 * REMARK if @widgets is set you have to ensure synchronization for this set on your own
	 * if it is not set it will automatically use the models widget actor
	 */
    @Deprecated("to be removed")
	suspend inline fun findWidgetOrElse(id: String, widgets: Collection<Widget>? = null, crossinline otherwise: (ConcreteId) -> Widget?): Widget? {
		return if (id == "null") null
		else idFromString(id).let {
			(widgets ?: getWidgets()).let{ widgets -> widgets.find { w -> w.id == it } ?: otherwise(it) }
		}
	}

	fun P_dumpModel(config: ModelConfig) = launch(CoroutineName("Model-dump"),parent = modelDumpJob) {
		getStates().let{ states ->
			println("dump Model with ${states.size}")
			states.forEach { s -> launch(CoroutineName("state-dump ${s.uid}"),parent = coroutineContext[Job]) { s.dump(config) } }
		}
		paths.forEach { t -> launch(CoroutineName("trace-dump"),parent = coroutineContext[Job]) { t.dump(config) } }
	}

	private var uTime: Long = 0
	/** update the model with any [action] executed as part of an execution [trace] **/
	fun S_updateModel(action: ActionResult, trace: Trace) = runBlocking {
		measureTimeMillis {
			var s: StateData? = null
			measureTimeMillis {
				s = computeNewState(action, trace.interactedEditFields)
			}//.let { println("state computation takes $it millis for ${s!!.widgets.size}") }
			s?.also { newState ->
				launch { newState.widgets } // initialize the widgets in parallel
				trace.update(action, newState)

				if (config[ModelProperties.dump.onEachAction]) {
					launch(CoroutineName("state-dump"),parent = modelDumpJob) { newState.dump(config) }
					launch(CoroutineName("trace-dump"),parent = modelDumpJob) { trace.dump(config) }
				}

				if (config[ModelProperties.imgDump.states]) launch(CoroutineName("screen-dump"),parent = modelDumpJob) {
					action.screenshot.let { 	// if there is any screen-shot write it to the state extraction directory
						if(it.isNotEmpty())
							java.io.File(config.statePath(newState.stateId,  fileExtension = ".png")).let { file ->
								Files.write(file.toPath(), it, StandardOpenOption.CREATE, StandardOpenOption.WRITE)
							}
                        else logger.warn("No Screenshot available for ${newState.stateId}")
						//DEBUG
//						java.io.File(config.statePath(newState.stateId, postfix = "$nStates", fileExtension = ".png")).let { file ->
//							Files.write(file.toPath(), it, StandardOpenOption.CREATE, StandardOpenOption.WRITE)
//						}
					}
				}

				debugT("${newState.widgets.size} widget adding ", {
					addWidgets(newState.widgets)
				}, inMillis = true)

				// this can occasionally take much time, therefore we use an actor for it as well
				debugT("state adding", { addState(newState) }, inMillis = true)
			}
		}.let {
//			println("model update took $it millis")
			uTime += it
//			println("---------- average model update time ${uTime / trace.size} ms overall ${uTime / 1000.0} seconds --------------")
		}
	}

	private fun computeNewState(action: ActionResult, @Suppress("UNUSED_PARAMETER") interactedEF: Map<UUID, List<Pair<StateData, Widget>>>): StateData {
		debugT("compute Widget set ", { action.getWidgets(config) })  // compute all widgets existing in the current state
				.let { widgets ->
					widgets.forEach { widget ->  // Initialize the parent ID. It's first necessary to have all widgets  converted before being able to link them.
						widget.parentId = widgets.firstOrNull {
							it.idHash == widget.parentHash
						}?.id
					}
					debugT("compute result State for ${widgets.size}\n", { action.resultState(widgets) }).let { state ->
						// revise state if it contains previously interacted edit fields

						//FIXME this is currently buggy anyway and issues concurrent modification exception
//						return debugT("special widget handling", {
//							if (state.hasEdit) interactedEF[state.iEditId]?.let {
//								action.resultState(lazy { handleEditFields(state, it) })
//							} ?: state
//							else state
//						})
//					return s!! //}
				return state
					}
				}
	}

//	val editTask = {state:StateData,(iUid, widgets):Pair<UUID,List<Widget>> ->
//		if (state.idWhenIgnoring(widgets) == iUid &&
//				widgets.all { candidate -> state.widgets.any { it.xpath == candidate.xpath } })
//			state.widgets.map { w -> w.apply { uid = widgets.find { it.uid == w.uid }?.uid ?: w.uid } } // replace with initial uid
//		else null
//	}
	/** check for all edit fields of the state if we already interacted with them and thus potentially changed their text property, if so overwrite the uid to the original one (before we interacted with it) */
	/* TODO instead of modifying, we simply re-create widgets and states
	private val handleEditFields: (StateData, List<Pair<StateData, Widget>>) -> List<Widget> = { state, interactedEF ->
		//		async {
		// different states may coincidentally have the same iEditId => grouping and check which (if any) is the same conceptional state as [state]
		debugT("candidate computation", {
			synchronized(interactedEF) {
				interactedEF.groupBy { it.first }.map { (s, pairs) ->
					pairs.map { it.second }.let { widgets -> s.idWhenIgnoring(widgets) to widgets }
				}
			}
		})
				.let { candidates ->

					//			debugT("parallel edit field",{
//				runBlocking {
//					it.map { //(iUid, widgets) ->
//						async {	editTask(state,it) }
//					}.mapNotNull { it.await() }
//				}
//			})
//			debugT("parallel edit unconfined",{
//				runBlocking {
//					it.map { //(iUid, widgets) ->
//						async(Unconfined) {	editTask(state,it) }
//					}.mapNotNull { it.await() }
//				}
//			})
//FIXME same issue for Password fields?
					debugT("sequential edit field", {
						// faster then parallel alternatives
						candidates.fold(state.widgets, { res, (iUid, widgets) ->
							// determine which candidate matches the current [state] and replace the respective widget.uid`s
							if (state.idWhenIgnoring(widgets) == iUid &&
									widgets.all { candidate -> state.widgets.any { it.xpath == candidate.xpath } })
								state.widgets.map { w -> w.apply {
									uid = widgets.find { it.uid == w.uid }?.uid ?: w.uid } } // replace with initial uid
							else res // contain different elements => wrong state candidate
						})
					})
				}
//		}
	}
	// */

	companion object {
        val logger: Logger by lazy { LoggerFactory.getLogger(this::class.java) }
		@JvmStatic
		fun emptyModel(config: ModelConfig): Model = Model(config).apply { runBlocking { addState(StateData.emptyState) }}

		/**
		 * use this method to load a specific app model from its dumped data
		 *
		 * example:
		 * val test = loadAppModel("ch.bailu.aat")
		 * runBlocking { println("$test #widgets=${test.getWidgets().size} #states=${test.getStates().size} #paths=${test.getPaths().size}") }
		 */
		@Suppress("unused")
		@JvmStatic fun loadAppModel(appName: String, watcher: LinkedList<ModelFeature> = LinkedList())
				= ModelParser.loadModel(ModelConfig(appName = appName, isLoadC = true), watcher)

		@JvmStatic
		fun main(args: Array<String>) {
			val test = ModelParser.loadModel(ModelConfig(path = Paths.get("..","out","playback"), appName = "testModel", isLoadC =true))//loadAppModel("loadTest")
			runBlocking { println("$test #widgets=${test.getWidgets().size} #states=${test.getStates().size} #paths=${test.getPaths().size}") }
			test.getPaths().first().getActions().forEach { a ->
				println("ACTION: " + a.actionString())
			}
		}

	} /** end COMPANION **/

	/**
	 * this only shows how often the addState or addWidget function was called, but if identical id's were added multiple
	 * times the real set will contain less elements then these counter indicate
	 */
	override fun toString(): String {
		return "Model[#addState=$nStates, #addWidget=$nWidgets, paths=${paths.size}]"
	}
}
