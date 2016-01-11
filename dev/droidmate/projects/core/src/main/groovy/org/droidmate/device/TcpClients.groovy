// Copyright (c) 2012-2016 Saarland University
// All rights reserved.
//
// Author: Konrad Jamrozik, jamrozik@st.cs.uni-saarland.de
//
// This file is part of the "DroidMate" project.
//
// www.droidmate.org
package org.droidmate.device

import org.droidmate.android_sdk.IAdbWrapper
import org.droidmate.common_android.DeviceCommand
import org.droidmate.common_android.DeviceResponse
import org.droidmate.exceptions.DeviceException
import org.droidmate.exceptions.TcpServerUnreachableException

class TcpClients implements ITcpClients
{
  @Delegate
  private final IMonitorsClient                                       monitorsClient

  private final ISerializableTCPClient<DeviceCommand, DeviceResponse> uiautomatorClient
  private final int                                                   uiautomatorDaemonTcpPort

  TcpClients(IAdbWrapper adbWrapper, String deviceSerialNumber,int socketTimeout, int uiautomatorDaemonTcpPort)
  {
    this.uiautomatorDaemonTcpPort = uiautomatorDaemonTcpPort
    this.uiautomatorClient = new SerializableTCPClient<DeviceCommand, DeviceResponse>(socketTimeout)
    this.monitorsClient = new MonitorsClient(socketTimeout, deviceSerialNumber, adbWrapper)
  }

  @Override
  DeviceResponse sendCommandToUiautomatorDaemon(DeviceCommand deviceCommand)throws TcpServerUnreachableException, DeviceException
  {
    this.uiautomatorClient.queryServer(deviceCommand, this.uiautomatorDaemonTcpPort)
  }
}
