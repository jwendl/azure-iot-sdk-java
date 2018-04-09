// Copyright (c) Microsoft. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package com.microsoft.azure.sdk.iot.common;

import com.microsoft.azure.sdk.iot.device.IotHubStatusCode;

public class Success
{
    public Boolean result = null;
    private IotHubStatusCode callbackStatusCode;

    public void setResult(Boolean result)
    {
        this.result = result;
    }

    public void setCallbackStatusCode(IotHubStatusCode callbackStatusCode)
    {
        this.callbackStatusCode = callbackStatusCode;
    }

    public Boolean getResult()
    {
        return this.result;
    }

    public boolean callbackWasFired()
    {
        return this.callbackStatusCode != null;
    }

    public IotHubStatusCode getCallbackStatusCode()
    {
        return this.callbackStatusCode;
    }
}