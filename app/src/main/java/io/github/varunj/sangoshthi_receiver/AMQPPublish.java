package io.github.varunj.sangoshthi_receiver;

import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;

import org.apache.commons.lang3.SerializationUtils;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.concurrent.BlockingDeque;
import java.util.concurrent.LinkedBlockingDeque;

/**
 * Created by Varun on 22-Mar-17.
 */

public class AMQPPublish {

    public static Thread publishThread;
    public static JSONObject messagePresent;
    public static String QUEUE_NAME = "asha_to_server";
    public static BlockingDeque<JSONObject> queue = new LinkedBlockingDeque<>();

    public static ConnectionFactory factory = new ConnectionFactory();
    public static  void setupConnectionFactory() {
        try {
            factory.setUsername(StarterActivity.SERVER_USERNAME);
            factory.setPassword(StarterActivity.SERVER_PASS);
            factory.setHost(StarterActivity.IP_ADDR);
            factory.setPort(StarterActivity.SERVER_PORT);
            factory.setAutomaticRecoveryEnabled(true);
            factory.setNetworkRecoveryInterval(10000);
        }
        catch (Exception e) {
            e.printStackTrace();
        }
    }

    public static void publishToAMQP() {
        publishThread = new Thread(new Runnable() {
            @Override
            public void run() {
                while(true) {
                    try {
                        Connection connection = factory.newConnection();
                        Channel channel = connection.createChannel();
                        channel.confirmSelect();
                        while (true) {
                            messagePresent = queue.takeFirst();
                            try {
                                // xxx: read http://www.rabbitmq.com/api-guide.html. Set QueueName=RoutingKey to send message to only 1 queue
                                channel.exchangeDeclare("defaultExchangeName", "direct", true);
                                // xxx: first field true?
                                channel.queueDeclare(QUEUE_NAME, false, false, false, null);
                                channel.queueBind(QUEUE_NAME, "defaultExchangeName", QUEUE_NAME);
                                channel.basicPublish("defaultExchangeName", QUEUE_NAME, null, messagePresent.toString().getBytes());
                                displayMessage(messagePresent);
                                channel.waitForConfirmsOrDie();
                            } catch (Exception e) {
                                queue.putFirst(messagePresent);
                                e.printStackTrace();
                                throw e;
                            }
                        }
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                        break;
                    } catch (Exception e) {
                        e.printStackTrace();
                        try {
                            Thread.sleep(5000); //sleep and then try again
                        } catch (InterruptedException e1) {
                            e1.printStackTrace();
                            break;
                        }
                    }
                }
            }
        });
        publishThread.start();
    }

    public static void displayMessage(JSONObject message) {
        try {
            System.out.println("xxx:" + " " + message.getString("objective") + ": " + message.getString("show_name") + " " +
                    message.getString("sender") + ":  " + message.get("location") +
                    " " + message.getString("timestamp"));
        } catch (JSONException e) {
            e.printStackTrace();
        }
    }

}
