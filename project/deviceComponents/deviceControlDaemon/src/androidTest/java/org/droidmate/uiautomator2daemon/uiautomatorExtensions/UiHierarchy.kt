@file:Suppress("MemberVisibilityCanBePrivate")

package org.droidmate.uiautomator2daemon.uiautomatorExtensions

import android.graphics.Bitmap
import android.graphics.Rect
import android.support.test.runner.screenshot.Screenshot
import android.support.test.uiautomator.*
import android.util.Log
import android.util.Xml
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.experimental.NonCancellable.isActive
import kotlinx.coroutines.experimental.async
import kotlinx.coroutines.experimental.delay
import kotlinx.coroutines.experimental.runBlocking
import org.droidmate.uiautomator2daemon.debugT
import org.droidmate.deviceInterface.guimodel.WidgetData
import java.io.ByteArrayOutputStream
import java.io.StringWriter
import java.util.*
import kotlin.math.max
import kotlin.system.measureTimeMillis


@Suppress("unused")
object UiHierarchy : UiParser() {
	private const val LOGTAG = "droidmate/UiHierarchy"

	var appArea: Rect = Rect()

	private var nActions = 0
	private var ut = 0L
	suspend fun fetch(device: UiDevice): List<WidgetData> = debugT(" compute UiNodes avg= ${ut/(max(nActions,1)*1000000)}", {
		deviceW = device.displayWidth
		deviceH = device.displayHeight
		appArea = computeAppArea()
		val nodes = LinkedList<WidgetData>()

		try {
			device.getNonSystemRootNodes().let {
				it.forEachIndexed { index: Int, root: AccessibilityNodeInfo ->
					rootIdx = index
					createBottomUp(root, parentXpath = "//", nodes = nodes)
				}
			}
		} catch (e: Exception){	// the accessibilityNode service may throw this if the node is no longer up-to-date
			Log.w("droidmate/UiDevice", "error while fetching widgets ${e.localizedMessage}\n last widget was ${nodes.lastOrNull()}")
		}

		nodes.also { Log.d(LOGTAG,"#elems = ${it.size}")}
	}, inMillis = true, timer = {ut += it; nActions+=1})


	fun getXml(device: UiDevice):String = 	debugT(" fetching gui Dump ", {StringWriter().use { out ->
		device.waitForIdle()

		val serializer = Xml.newSerializer()
		serializer.setFeature("http://xmlpull.org/v1/doc/features.html#indent-output", true)
		serializer.setOutput(out)//, "UTF-8")

		serializer.startDocument("UTF-8", true)
		serializer.startTag("", "hierarchy")
		serializer.attribute("", "rotation", Integer.toString(device.displayRotation))

		device.apply(nodeDumper(serializer, device.displayWidth, device.displayHeight)
		) { _-> serializer.endTag("", "node")}

		serializer.endTag("", "hierarchy")
		serializer.endDocument()
		serializer.flush()
		out.toString()
	}}, inMillis = true)

	/** check if this node fullfills the given condition and recursively check descendents if not **/
	fun any(device: UiDevice, retry: Boolean=false, cond: SelectorCondition):Boolean{
		return findAndPerform(device, cond, retry) { _ -> true}
	}

	/** looks for a UiElement fulfilling [cond] and executes [action] on it.
	 * The search condition should be unique to avoid unwanted side-effects on other nodes which fulfill the same condition.
	 */
	@JvmOverloads fun findAndPerform(device: UiDevice, cond: SelectorCondition, retry: Boolean=true, action:((AccessibilityNodeInfo)->Boolean)): Boolean{
		var found = false
		var successfull = false

		val processor:NodeProcessor = { node,_, xPath ->
			when{
				found -> false // we already found our target and performed our action -> stop searching
				!isActive -> {Log.w(LOGTAG,"process became inactive"); false}
				!node.isVisibleToUser -> {
//					Log.d(LOGTAG,"node $xPath is invisible")
					false}
				!node.refresh() -> {Log.w(LOGTAG,"refresh on node $xPath failed"); false}
			// do not traverse deeper
			else -> {
				found = cond(node,xPath).also { isFound ->
					if(isFound){
						successfull = action(node).run { if(retry && !this){
							Log.d(LOGTAG,"action failed on $node\n with id ${xPath.hashCode()+rootIndex}, try a second time")
							runBlocking { delay(20) }
							action(node)
							}else this
						}.also {
							Log.d(LOGTAG,"action returned $it")
						}
					}
				}
				!found // continue if condition is not fulfilled yet
				}
			}
		}
		device.apply(processor)
		if(retry && !found) {
			Log.d(LOGTAG,"didn't find target, try a second time")
			runBlocking { delay(20) }
			device.apply(processor)
		}
		Log.d(LOGTAG,"found = $found")
		return found && successfull
	}

	/** @paramt timeout amount of mili seconds, maximal spend to wait for condition [cond] to become true (default 10s)
	 * @return if the condition was fulfilled within timeout
	 * */
	@JvmOverloads
	fun waitFor(device: UiDevice, timeout: Long = 10000, cond: SelectorCondition): Boolean{
		return waitFor(device,timeout,10,cond)
	}
	/** @param pollTime time intervall (in ms) to recheck the condition [cond] */
	fun waitFor(device: UiDevice, timeout: Long, pollTime: Long, cond: SelectorCondition) = runBlocking{
		// lookup should only take less than 100ms (avg 50-80ms) if the UiAutomator did not screw up
		val scanTimeout = 100 // this is the maximal number of mili seconds, which is spend for each lookup in the hierarchy
		var time = 0.0
		var found = false

		while(!found && time<timeout){
			measureTimeMillis {
				with(async { any(device, retry=false, cond=cond) }) {
					var i = 0
					while(!isCompleted && i<scanTimeout){
						delay(10)
						i+=10
					}
					if (isCompleted)
						found = await()
					else cancel()
				}
			}.run{ time += this
				device.runWatchers() // to update the ui view?
				if(!found && this<pollTime) delay(pollTime-this)
				Log.d(LOGTAG,"$found single wait iteration $this")
			}
		}
		found.also {
			Log.d(LOGTAG,"wait was successful: $found")
		}
	}

	suspend fun getScreenShot(delayForRetry:Long): Bitmap? {
		var screenshot: Bitmap? = null
		debugT("first screen-fetch attempt ", {
			try{ screenshot = Screenshot.capture()?.bitmap }
			catch (e: Exception){ Log.w(LOGTAG,"exception on screenshot-capture") }
		},inMillis = true)

		if (screenshot == null){
			Log.d(LOGTAG,"screenshot failed")
			delay(delayForRetry)
			screenshot = Screenshot.capture()?.bitmap
		}
		return screenshot.also {
			if (it == null)
				Log.w(LOGTAG,"no screenshot available")
		}
	}

	@JvmStatic private var t = 0.0
	@JvmStatic private var c = 0
	@JvmStatic
	fun compressScreenshot(screenshot: Bitmap?): ByteArray = debugT("compress image avg = ${t/ max(1,c)}",{
		var bytes = ByteArray(0)
		val stream = ByteArrayOutputStream()
		try {
			screenshot?.setHasAlpha(false)
			screenshot?.compress(Bitmap.CompressFormat.PNG, 100, stream)
			stream.flush()

			bytes = stream.toByteArray()
			stream.close()
		} catch (e: Exception) {
			Log.w(LOGTAG, "Failed to compress screenshot: ${e.message}. Stacktrace: ${e.stackTrace}")
		}

		bytes
	}, inMillis = true, timer = { t += it / 1000000.0; c += 1})

}




