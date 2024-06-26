package com.zazsona.wearstatus.client;

import com.google.gson.Gson;
import com.zazsona.wearstatus.messages.Message;
import com.zazsona.wearstatus.messages.PlayerStatusMessage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.player.ClientPlayerEntity;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.time.Instant;
import java.util.LinkedList;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

public class WearConnector
{
    private static final Logger LOGGER = LogManager.getLogger();
    public final int PORT = 25500;
    public final int PING_FREQUENCY_MS = 1500;

    private static WearConnector instance;
    private ServerSocket serverSocket;
    private Socket clientSocket;
    private ObjectInputStream inputStream;
    private ObjectOutputStream outputStream;
    private Object lock = new Object();
    private BlockingQueue<Message> messageQueue;

    public static WearConnector getInstance()
    {
        if (instance == null)
            instance = new WearConnector();
        return instance;
    }

    private WearConnector()
    {
        try
        {
            LOGGER.info("Creating wear server...");
            messageQueue = new LinkedBlockingQueue<>();
            serverSocket = new ServerSocket(PORT);
            serverSocket.setSoTimeout(0);
            LOGGER.info("Wear server created: "+serverSocket.getInetAddress()+":"+serverSocket.getLocalPort());
        }
        catch (IOException e)
        {
            LOGGER.error("Unable to create Wear Server - "+e.getMessage());
            e.printStackTrace();
        }
    }

    public void startServer()
    {
        try
        {
            while (true)
            {
                LOGGER.info("Waiting for Wear client...");
                clientSocket = serverSocket.accept();
                LOGGER.info("Wear connected! - "+clientSocket.getInetAddress().getHostName());
                outputStream = new ObjectOutputStream(clientSocket.getOutputStream());
                outputStream.flush();
                inputStream = new ObjectInputStream(clientSocket.getInputStream());

                ClientPlayerEntity clientPlayerEntity = Minecraft.getInstance().player;
                if (clientPlayerEntity != null)
                    sendMessage(new PlayerStatusMessage(clientPlayerEntity.getHealth(), 0.0f, clientPlayerEntity.getMaxHealth(), clientPlayerEntity.getFoodStats().getFoodLevel()));

                runQueuedMessageSender(); //This will hold the thread until a ping fails.
                stopConnection();
            }
        }
        catch (SocketException e)
        {
            LOGGER.info("Wear Client socket error - "+e.getMessage());
            e.printStackTrace();
        }
        catch (IOException e)
        {
            LOGGER.error("Unable to run Wear Server - "+e.getMessage());
            e.printStackTrace();
        }
    }

    public void stopConnection()
    {
        try
        {
            if (clientSocket != null)
            {
                if (!clientSocket.isClosed())
                    clientSocket.close();
                clientSocket = null;
            }
            messageQueue.clear();
            LOGGER.info("Wear Connection was stopped.");
        }
        catch (IOException e)
        {
            LOGGER.error("Could not gracefully stop Wear connection - "+e.getMessage());
            e.printStackTrace();
        }
    }

    public void queueMessage(Message message)
    {
        messageQueue.add(message);
    }

    private boolean sendMessage(Message message)
    {
        try
        {
            if (clientSocket != null && outputStream != null && !clientSocket.isClosed())
            {
                synchronized (lock)
                {
                    Gson gson = new Gson();
                    String messageJson = gson.toJson(message);
                    outputStream.writeObject(messageJson);
                    outputStream.flush();
                    return true;
                }
            }
            return false;
        }
        catch (IOException e)
        {
            LOGGER.warn("Unable to send message - "+e.getMessage());
            return false;
        }
    }

    private void runQueuedMessageSender()
    {
        try
        {
            long lastMessageSentTime = Instant.now().toEpochMilli();
            boolean success = true;
            while (success)
            {
                if (!messageQueue.isEmpty())
                {
                    success = sendMessage(messageQueue.take());
                    lastMessageSentTime = Instant.now().toEpochMilli();
                }
                else if (Instant.now().toEpochMilli()-lastMessageSentTime >= PING_FREQUENCY_MS)
                {
                    success = sendMessage(new Message("PING"));
                    lastMessageSentTime = Instant.now().toEpochMilli();
                }
                Thread.sleep(16); //Check 60 times a second.
            }
        }
        catch (InterruptedException e)
        {
            LOGGER.info("Ping loop interrupted.");
        }
    }
}
