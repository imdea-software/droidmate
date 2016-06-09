// Copyright (c) 2012-2016 Saarland University
// All rights reserved.
//
// Author: Konrad Jamrozik, jamrozik@st.cs.uni-saarland.de
//
// This file is part of the "DroidMate" project.
//
// www.droidmate.org

package org.droidmate.plugin_hook;

import org.jetbrains.annotations.NotNull;

// !!!!! =================================================================
// !!!!! DO NOT EDIT THIS FILE !!!
// !!!!! =================================================================
// !!!!! Instead, run 'gradlew :projects:plugin-api-hook:compileJava' 
// !!!!! to generate HookPlugin class and edit HookPlugin.java.

public class HookPluginTemplate implements IHookPlugin
{
  // KJA add to apk fixtures sendTextMessage and debug logs
  // KJA add support for Android. Might require upgrading to newest IJ to handle "provided" dependencies. 
  // https://developer.android.com/training/basics/data-storage/files.html
  // File file = new File(context.getFilesDir(), filename);
  public void hookBeforeApiCall(@NotNull String apiLogcatMessagePayload)
  {
    System.out.println("hookBeforeApiCall/apiLogcatMessagePayload: "+ apiLogcatMessagePayload);
  }

  public Object hookAfterApiCall(Object returnValue)
  {
    System.out.println("hookAfterApiCall/returnValue: "+ returnValue);
    if (returnValue instanceof String)
      return "xxx";
    else
      return returnValue;
  }
}
