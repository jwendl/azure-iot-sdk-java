/*
 *  Copyright (c) Microsoft. All rights reserved.
 *  Licensed under the MIT license. See LICENSE file in the project root for full license information.
 */

package com.microsoft.azure.sdk.iot.common.iothubservices;

import com.microsoft.azure.sdk.iot.common.*;
import com.microsoft.azure.sdk.iot.device.*;
import com.microsoft.azure.sdk.iot.device.exceptions.TransportException;
import com.microsoft.azure.sdk.iot.device.transport.IotHubConnectionStatus;
import com.sun.org.apache.bcel.internal.generic.RET;
import org.junit.Assert;

import java.io.IOException;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.List;

import static junit.framework.TestCase.assertTrue;
import static junit.framework.TestCase.fail;

/*
 * This class contains common code for Junit and Android test cases
 */
public class SendMessagesCommon
{
    private final static long OPEN_RETRY_TIMEOUT = 3*60*1000; //3 minutes, or about 3 retries if open's keep timing out

    /*
     * method to send message over given DeviceClient
     */
    public static void sendMessages(DeviceClient client,
                                    IotHubClientProtocol protocol,
                                    List<MessageAndResult> messagesToSend,
                                    final Integer RETRY_MILLISECONDS,
                                    final Integer SEND_TIMEOUT_MILLISECONDS,
                                    int interMessageDelay) throws IOException, InterruptedException
    {
        openDeviceClientWithRetry(client);

        for (int i = 0; i < messagesToSend.size(); ++i)
        {
            if (isErrorInjectionMessage(messagesToSend.get(i)))
            {
                //error injection message is not guaranteed to be ack'd by service so it may be re-sent. By setting expiry time,
                // we ensure that error injection message isn't resent to service too many times. The message will still likely
                // be sent 3 or 4 times causing 3 or 4 disconnections, but the test should recover anyways.
                messagesToSend.get(i).message.setExpiryTime(200);
            }

            sendMessageAndWaitForResponse(client, messagesToSend.get(i), RETRY_MILLISECONDS, SEND_TIMEOUT_MILLISECONDS, protocol);

            Thread.sleep(interMessageDelay);
        }

        client.closeNow();
    }

    public static void sendMessagesExpectingConnectionStatusChangeUpdate(DeviceClient client,
                                                                         IotHubClientProtocol protocol,
                                                                         List<MessageAndResult> messagesToSend,
                                                                         final Integer RETRY_MILLISECONDS,
                                                                         final Integer SEND_TIMEOUT_MILLISECONDS,
                                                                         final IotHubConnectionStatus expectedStatus,
                                                                         int interMessageDelay) throws IOException, InterruptedException
    {
        final List<IotHubConnectionStatus> expectedStatusUpdates = new ArrayList<>();
        client.registerConnectionStatusChangeCallback(new IotHubConnectionStatusChangeCallback()
        {
            @Override
            public void execute(IotHubConnectionStatus status, IotHubConnectionStatusChangeReason statusChangeReason, Throwable throwable, Object callbackContext) {
                expectedStatusUpdates.add(status);
            }
        }, new Object());

        sendMessages(client, protocol, messagesToSend, RETRY_MILLISECONDS, SEND_TIMEOUT_MILLISECONDS, interMessageDelay);

        assertTrue("Expected connection status update to occur: " + expectedStatus, expectedStatusUpdates.contains(expectedStatus));
    }

    /**
     * Send some messages that wait for callbacks to signify that the SAS token in the client config has expired.
     *
     * @param client the client to send the messages from
     * @param protocol the protocol the client is using
     */
    public static void sendMessagesExpectingSASTokenExpiration(DeviceClient client,
                                                               String protocol,
                                                               int numberOfMessages,
                                                               Integer retryMilliseconds,
                                                               long timeoutMilliseconds)
    {
        for (int i = 0; i < numberOfMessages; ++i)
        {
            try
            {
                Message messageToSend = new Message("Test message expecting SAS Token Expired callback for protocol: " + protocol);
                Success messageSent = new Success();
                Success statusUpdated = new Success();

                ConnectionStatusCallback stateCallback = new ConnectionStatusCallback(IotHubConnectionState.SAS_TOKEN_EXPIRED);
                EventCallback messageCallback = new EventCallback(IotHubStatusCode.UNAUTHORIZED);

                client.registerConnectionStateCallback(stateCallback, statusUpdated);
                client.sendEventAsync(messageToSend, messageCallback, messageSent);

                Integer waitDuration = 0;
                while(!messageSent.callbackWasFired() || !statusUpdated.getResult())
                {
                    Thread.sleep(retryMilliseconds);
                    if ((waitDuration += retryMilliseconds) > timeoutMilliseconds)
                    {
                        Assert.fail("Sending message over " + protocol + " protocol failed: " +
                                "never received connection status update for SAS_TOKEN_EXPIRED " +
                                "or never received UNAUTHORIZED message callback");
                    }
                }

                if (messageSent.getCallbackStatusCode() != IotHubStatusCode.UNAUTHORIZED)
                {
                    fail("Send messages expecting sas token expiration failed: expected UNAUTHORIZED message callback, but got " + messageSent.getCallbackStatusCode());
                }
            }
            catch (Exception e)
            {
                Assert.fail("Sending message over " + protocol + " protocol failed");
            }
        }
    }

    /*
     * method to send message over given DeviceClient
     */
    public static void sendMessagesMultiplex(DeviceClient client,
                                             IotHubClientProtocol protocol,
                                             final int NUM_MESSAGES_PER_CONNECTION,
                                             final Integer RETRY_MILLISECONDS,
                                             final Integer SEND_TIMEOUT_MILLISECONDS)
    {
        openDeviceClientWithRetry(client);

        String messageString = "Java client e2e test message over " + protocol + " protocol";
        Message msg = new Message(messageString);

        for (int i = 0; i < NUM_MESSAGES_PER_CONNECTION; ++i)
        {
            try
            {
                Success messageSent = new Success();
                EventCallback callback = new EventCallback(IotHubStatusCode.OK_EMPTY);
                client.sendEventAsync(msg, callback, messageSent);

                Integer waitDuration = 0;
                while (!messageSent.callbackWasFired())
                {
                    Thread.sleep(RETRY_MILLISECONDS);
                    if ((waitDuration += RETRY_MILLISECONDS) > SEND_TIMEOUT_MILLISECONDS)
                    {
                        break;
                    }
                }

                if (messageSent.getCallbackStatusCode() != IotHubStatusCode.OK_EMPTY)
                {
                    if (messageSent.callbackWasFired())
                    {
                        Assert.fail("Sending message over " + protocol + " protocol failed: expected status code OK_EMPTY but received: " + messageSent.getCallbackStatusCode());
                    }
                    else
                    {
                        Assert.fail("Sending message over " + protocol + " protocol failed: never received message callback");
                    }
                }
            }
            catch (Exception e)
            {
                Assert.fail("Sending message over " + protocol + " protocol failed: Exception encountered while sending messages: " + e.getMessage());
            }
        }
    }

    public static void sendExpiredMessageExpectingMessageExpiredCallback(DeviceClient deviceClient,
                                                                         IotHubClientProtocol protocol,
                                                                         final Integer RETRY_MILLISECONDS,
                                                                         final Integer SEND_TIMEOUT_MILLISECONDS) throws IOException, URISyntaxException
    {
        try
        {
            Message expiredMessage = new Message("This message has expired");
            expiredMessage.setAbsoluteExpiryTime(1); //setting this to 0 causes the message to never expire
            Success messageSentExpiredCallback = new Success();

            openDeviceClientWithRetry(deviceClient);
            deviceClient.sendEventAsync(expiredMessage, new EventCallback(IotHubStatusCode.MESSAGE_EXPIRED), messageSentExpiredCallback);

            Integer waitDuration = 0;
            while (!messageSentExpiredCallback.callbackWasFired())
            {
                Thread.sleep(RETRY_MILLISECONDS);
                if ((waitDuration += RETRY_MILLISECONDS) > SEND_TIMEOUT_MILLISECONDS)
                {
                    break;
                }
            }

            deviceClient.closeNow();

            if (messageSentExpiredCallback.getCallbackStatusCode() != IotHubStatusCode.MESSAGE_EXPIRED)
            {
                if (messageSentExpiredCallback.callbackWasFired())
                {
                    Assert.fail("Sending message over " + protocol + " protocol failed: expected status code MESSAGE_EXPIRED but received: " + messageSentExpiredCallback.getCallbackStatusCode());
                }
                else
                {
                    Assert.fail("Sending message over " + protocol + " protocol failed: never received message callback");
                }
            }
        }
        catch (Exception e)
        {
            deviceClient.closeNow();
            Assert.fail("Sending expired message over " + protocol + " protocol failed: Exception encountered while sending message and waiting for MESSAGE_EXPIRED callback: " + e.getMessage());
        }
    }

    public static void sendMessagesExpectingUnrecoverableConnectionLossAndTimeout(DeviceClient client,
                                                                                  IotHubClientProtocol protocol,
                                                                                  Message errorInjectionMessage) throws IOException, InterruptedException
    {
        final List<IotHubConnectionStatus> statusUpdates = new ArrayList<>();
        client.registerConnectionStatusChangeCallback(new IotHubConnectionStatusChangeCallback()
        {
            @Override
            public void execute(IotHubConnectionStatus status, IotHubConnectionStatusChangeReason statusChangeReason, Throwable throwable, Object callbackContext) {
                statusUpdates.add(status);
            }
        }, new Object());

        openDeviceClientWithRetry(client);

        client.sendEventAsync(errorInjectionMessage, new EventCallback(null), new Success());

        long startTime = System.currentTimeMillis();
        while (!(statusUpdates.contains(IotHubConnectionStatus.DISCONNECTED_RETRYING) && statusUpdates.contains(IotHubConnectionStatus.DISCONNECTED)))
        {
            Thread.sleep(500);

            if (System.currentTimeMillis() - startTime > 30 * 1000)
            {
                break;
            }
        }

        assertTrue("Expected notification about disconnected but retrying. Protocol: " + protocol, statusUpdates.contains(IotHubConnectionStatus.DISCONNECTED_RETRYING));
        assertTrue("Expected notification about disconnected. Protocol: " + protocol, statusUpdates.contains(IotHubConnectionStatus.DISCONNECTED));

        client.closeNow();
    }

    private static void sendMessageAndWaitForResponse(DeviceClient client, MessageAndResult messageAndResult, int RETRY_MILLISECONDS, int SEND_TIMEOUT_MILLISECONDS, IotHubClientProtocol protocol)
    {
        try
        {
            Success messageSent = new Success();
            EventCallback callback = new EventCallback(messageAndResult.statusCode);
            client.sendEventAsync(messageAndResult.message, callback, messageSent);

            Integer waitDuration = 0;
            while (!messageSent.callbackWasFired())
            {
                Thread.sleep(RETRY_MILLISECONDS);
                if ((waitDuration += RETRY_MILLISECONDS) > SEND_TIMEOUT_MILLISECONDS)
                {
                    break;
                }
            }

            if (messageSent.getCallbackStatusCode() != messageAndResult.statusCode)
            {
                if (messageSent.callbackWasFired())
                {
                    Assert.fail("Sending message over " + protocol + " protocol failed: unexpected status code received from callback: " + messageSent.getCallbackStatusCode());
                }
                else
                {
                    Assert.fail("Sending message over " + protocol + " protocol failed: never received message callback");
                }
            }
        }
        catch (Exception e)
        {
            Assert.fail("Sending message over " + protocol + " protocol failed: Exception encountered while sending and waiting on a message: " + e.getMessage());
        }
    }

    private static boolean isErrorInjectionMessage(MessageAndResult messageAndResult)
    {
        MessageProperty[] properties = messageAndResult.message.getProperties();
        for (int i = 0; i < properties.length; i++)
        {
            if (properties[i].getValue().equals(ErrorInjectionHelper.FaultCloseReason_Boom.toString()) || properties[i].getValue().equals(ErrorInjectionHelper.FaultCloseReason_Bye.toString()))
            {
                return true;
            }
        }

        return false;
    }

    public static void openDeviceClientWithRetry(DeviceClient client)
    {
        boolean clientOpenSucceeded = false;
        long startTime = System.currentTimeMillis();
        while (!clientOpenSucceeded)
        {
            if (System.currentTimeMillis() - startTime > OPEN_RETRY_TIMEOUT)
            {
                Assert.fail("Could not open the device client");
            }

            try
            {
                client.open();
                clientOpenSucceeded = true;
            }
            catch (IOException e)
            {
                //ignore and try again
            }
        }
    }

    public static void openTransportClientWithRetry(TransportClient client)
    {
        boolean clientOpenSucceeded = false;
        long startTime = System.currentTimeMillis();
        while (!clientOpenSucceeded)
        {
            if (System.currentTimeMillis() - startTime > OPEN_RETRY_TIMEOUT)
            {
                Assert.fail("Could not open the device client");
            }

            try
            {
                client.open();
                clientOpenSucceeded = true;
            }
            catch (IOException e)
            {
                //ignore and try again
            }
        }
    }
}
