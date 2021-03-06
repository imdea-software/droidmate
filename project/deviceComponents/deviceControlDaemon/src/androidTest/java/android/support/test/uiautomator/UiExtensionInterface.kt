package android.support.test.uiautomator

import android.graphics.Rect
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo


fun AccessibilityNodeInfo.getBounds(width: Int, height: Int): Rect = when{
	isVisibleToUser ->
		AccessibilityNodeInfoHelper.getVisibleBoundsInScreen(this, width, height)
	else -> Rect().apply { getBoundsInScreen(this)}
}

/** @return true if children should be recursively traversed */
typealias NodeProcessor = (rootNode: AccessibilityNodeInfo, index: Int, xPath: String)	-> Boolean
typealias PostProcessor<T> = (rootNode: AccessibilityNodeInfo)	-> T
const val osPkg = "com.android.systemui"
var rootIndex:Int = 0
inline fun<reified T> UiDevice.apply(noinline processor: NodeProcessor, noinline postProcessor: PostProcessor<T>): List<T> =
	getNonSystemRootNodes().mapIndexed { index,root: AccessibilityNodeInfo ->
		rootIndex = index
		processTopDown(root,processor = processor,postProcessor = postProcessor)
	}

fun UiDevice.apply(processor: NodeProcessor){
	try{
		getNonSystemRootNodes().mapIndexed { index, root: AccessibilityNodeInfo ->
			rootIndex = index
			processTopDown(root, processor = processor, postProcessor = { _ -> Unit })
		}
	}catch(e: Exception){
		Log.w("droidmate/UiDevice","error while processing AccessibilityNode tree ${e.localizedMessage}")
	}
}

fun<T> processTopDown(node:AccessibilityNodeInfo, index: Int=0, processor: NodeProcessor, postProcessor: PostProcessor<T>, parentXpath: String = "//"):T{
	val nChildren = node.childCount
	val xPath = parentXpath +"${node.className}[${index + 1}]"
	val proceed = processor(node,index,xPath)

	try {
		if(proceed)
			(0 until nChildren).map { i ->
				processTopDown(node.getChild(i), i, processor, postProcessor, "$xPath/")
			}
	} catch (e: Exception){	// the accessibilityNode service may throw this if the node is no longer up-to-date
		Log.w("droidmate/UiDevice", "error child of $parentXpath node no longer available ${e.localizedMessage}")
		node.refresh()
	}
	val res = postProcessor(node)

	node.recycle()
	return res
}

@Suppress("UsePropertyAccessSyntax")
fun UiDevice.getNonSystemRootNodes():List<AccessibilityNodeInfo> = getWindowRoots().filterNot { it.packageName == osPkg }

fun UiDevice.longClick(x: Int, y: Int, timeout: Long)=
	interactionController.longTapAndSync(x,y,timeout)
//	interactionController.longTapNoSync(x,y)

fun UiDevice.click(x: Int, y: Int, timeout: Long)=
	interactionController.clickAndSync(x,y,timeout)
//	interactionController.clickNoSync(x,y)


