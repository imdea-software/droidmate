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

package org.droidmate.exploration.statemodel.features

import kotlinx.coroutines.experimental.CoroutineName
import kotlinx.coroutines.experimental.Job
import kotlinx.coroutines.experimental.NonCancellable.isActive
import kotlinx.coroutines.experimental.delay
import kotlinx.coroutines.experimental.newCoroutineContext
import org.droidmate.configuration.ConfigProperties
import org.droidmate.exploration.statemodel.ConcreteId
import org.droidmate.exploration.statemodel.ModelConfig
import org.droidmate.exploration.statemodel.StateData
import org.droidmate.exploration.statemodel.Widget
import org.droidmate.misc.deleteDir
import java.awt.*
import java.awt.image.BufferedImage
import java.io.File
import java.nio.file.Files
import java.util.*
import java.util.concurrent.atomic.AtomicInteger
import javax.imageio.ImageIO
import kotlin.collections.HashMap
import kotlin.coroutines.experimental.CoroutineContext

/** use this function to create a sequence of screen images in which the interacted target is highlighted by a red boarder */
class ImgTraceMF(val cfg: ModelConfig) : ModelFeature() {
	override val context: CoroutineContext = newCoroutineContext(context = CoroutineName("ImgTraceMF"), parent = job)

	private val targetDir = (cfg.baseDir.resolve("ModelFeatures/imgTrace"))
	init {
		job = Job(parent = (this.job)) // we don't want to wait for other features (or having them wait for us), therefore create our own (child) job
		targetDir.deleteDir()
		Files.createDirectories(targetDir)
	}

	var i: AtomicInteger = AtomicInteger(0)
	override suspend fun onNewInteracted(traceId: UUID, targetWidgets: List<Widget>, prevState: StateData, newState: StateData){
		// check if we have any screenshots to process
		if(!cfg[ConfigProperties.ModelProperties.imgDump.states]) return

		val step = i.incrementAndGet()-1
		val screenFile = java.io.File(cfg.statePath(prevState.stateId, fileExtension = ".png"))

		while(isActive && !screenFile.exists()) delay(100)
		while(isActive && !screenFile.canRead()) delay(100)
		if(!isActive) return
		if(!screenFile.exists()) return // thread was canceled but no file to process is ready yet

		val targetFile = File("${targetDir.toAbsolutePath()}${File.separator}$step.png")
		if(targetWidgets.isEmpty()) {		// move file to trace directory
			screenFile.copyTo(targetFile, overwrite = true)
			return
		}

		val stateImg = ImageIO.read(targetFile)
		highlightWidget(stateImg, targetWidgets, step)
		ImageIO.write(stateImg,"png",targetFile)
	}

}

var shapeColor: Color = Color.red
var textColor: Color = Color.magenta
fun highlightWidget(stateImg: BufferedImage, targetWidgets: List<Widget>,idxOffset: List<Int>){
	stateImg.createGraphics().apply{
		stroke = BasicStroke(10F)
		font = Font("TimesRoman", Font.PLAIN, 60)

		val targetsPerAction = targetWidgets.mapIndexed{ i,t -> Pair(idxOffset[i],t)}.groupBy { it.first }

		val targetCounter: MutableMap<ConcreteId,LinkedList<Pair<Int,Int>>> = HashMap() // used to compute offsets in the number string
		// compute the list of indicies for each widget-target (for labeling)
		targetsPerAction.forEach{ (idx, targets) ->
			targets.forEachIndexed{ index, (_,t) ->
				targetCounter.compute(t.id) { _, indicies -> (indicies ?: LinkedList()).apply { add(Pair(idx,index)) }}
			}
		}
		// highlight all targets and add text labels
		targetWidgets.forEach{ it ->
			paint = shapeColor// reset color for next shape drawing
			drawOval(it.bounds)
			// draw the label number for the element
			val text = targetCounter[it.id]!!.joinToString(separator = ", ") { if(it.first!=0) "${it.first}.${it.second}" else "${it.second}" }
			if( text.length>20 ) 		font = Font("TimesRoman", Font.PLAIN, 20)
			paint = textColor// for better visibility use a different color then the boarder
			drawString(text,it.bounds.x+(it.bounds.width/10),it.bounds.y+(it.bounds.height/10))
			font = Font("TimesRoman", Font.PLAIN, 60) // reset font to bigger font
		}
	}
}
fun highlightWidget(stateImg: BufferedImage, targetWidgets: List<Widget>,idxOffset: Int = 0)
	= highlightWidget(stateImg,targetWidgets,(0 until targetWidgets.size).map{ idxOffset })


fun Graphics.drawOval(bounds: Rectangle){
	this.drawOval(bounds.x,bounds.y,bounds.width,bounds.height)
}
