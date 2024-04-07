package Argon;

import arc.Events;
import arc.func.Cons;
import arc.struct.ObjectMap;
import arc.struct.Seq;
import arc.util.Log;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
import com.rabbitmq.client.ShutdownListener;
import mindustry.Vars;
import mindustry.game.EventType;
import mindustry.mod.Plugin;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.concurrent.TimeoutException;

public class Argon extends Plugin {
    private static final ArrayList<String> queuesDeclared = new ArrayList<>();
    private static final HashMap<String, String> exchangeQueues = new HashMap<>();
    private static final ObjectMap<Object, Seq<Cons<?>>> events = new ObjectMap<>();
    private static ShutdownListener sdl;
    private static RabbitMQDetails factoryDetails = new RabbitMQDetails();
    private static ConnectionFactory factory;
    private static Connection connection;
    private static Channel channel;

    private static final ObjectMapper objectMapper = new ObjectMapper();

    private static boolean shutdown = false;

    static {
        sdl = cause -> {
            if (shutdown) return;

            connection = createConnectionWithRetry(factory);
            connection.addShutdownListener(sdl);
            try {
                channel = connection.createChannel();
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
            Log.debug("Argon: Firing RegisterRabbitQueues");
            Events.fire(new RegisterArgonEvents());
        };
    }

    @Override
    public void init() {
        Events.on(EventType.ServerLoadEvent.class, event -> {
            var configFolder = Vars.modDirectory.child("Argon/");
            configFolder.mkdirs();
            var configFi = configFolder.child("config.json");

            boolean bad = false;

            badExit:
            if (configFi.exists()) {
                //region read RabbitMQ connection settings
                try {
                    factoryDetails = objectMapper.readValue(configFi.file(), RabbitMQDetails.class);
                } catch (IOException e) {
                    //e.g. save bad/old config as config.json.123123123.old
                    if (!factoryDetails.equals(new RabbitMQDetails())) {
                        var old = configFolder.child(configFi.name() + '.' + System.currentTimeMillis() + ".old");
                        configFi.copyTo(old);
                        RabbitMQDetails.saveDefault();
                        Log.err("Argon: Bad config file copied to @ and replaced with a clean config file!", old.absolutePath());
                    }
                    Log.err(e);
                    bad = true;
                    break badExit;
                }
                //endregion

                //region RabbitMQ Factory Setup
                factory = new ConnectionFactory();
                factory.setHost(factoryDetails.getUrl());
                factory.setPort(factoryDetails.getPort());
                factory.setUsername(factoryDetails.getUsername());
                factory.setPassword(factoryDetails.getPassword());
                //endregion

                //attempt open the RabbitMQ connection
                try {
                    connection = factory.newConnection();
                    connection.addShutdownListener(sdl);
                } catch (IOException | TimeoutException e) {
                    Log.err(e);
                    bad = true;
                }
            } else {
                RabbitMQDetails.saveDefault();
                bad = true;
            }


            if (bad) {
                var h = "#".repeat(97);
                var t = "\t".repeat(5);
                Log.warn("\u001B[31m@\u001B[0m", h);
                Log.warn("\u001B[31m@\u001B[0m", h);
                Log.warn("");
                Log.warn("\u001B[31m@@\u001B[0m", t, "Argon config file needs to be configured!");
                Log.warn("\u001B[31m@@\u001B[0m", t, configFi.absolutePath());
                Log.warn("");
                Log.warn("\u001B[31m@\u001B[0m", h);
                Log.warn("\u001B[31m@\u001B[0m", h);
                return;
            }

            try {
                channel = connection.createChannel();
            } catch (IOException e) {
                Log.err(e);
                return;
            }

            Log.debug("Argon: Firing RegisterRabbitQueues");
            Events.fire(new RegisterArgonEvents());
        });
    }

    private static String getQueueName(String name, boolean exchange) {
        if (exchange) {
            return exchangeQueues.computeIfAbsent(name, k -> {
                try {
                    Log.debug("Argon: Setting up exchange @", name);
                    var exchangeQueueName = channel.queueDeclare().getQueue();
                    //noinspection SpellCheckingInspection
                    channel.exchangeDeclare(name + "_exchange", "fanout");
                    channel.queueBind(exchangeQueueName, name + "_exchange", "");
                    return exchangeQueueName;
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            });
        } else {
            if (!queuesDeclared.contains(name)) {
                try {
                    Log.debug("Argon: Setting up queue @", name);
                    channel.queueDeclare(name, false, false, false, null);
                    queuesDeclared.add(name);
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            }
            return name;
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes", "unused"})
    public static <T> void on(Class<T> type, Cons<T> listener) {
        boolean exchange = !type.isAnnotationPresent(NonExchangeEvent.class);

        events.get(type, () -> {
            //if no listener seq found, declare a new one
            String queueName = getQueueName(type.getSimpleName(), exchange);//gets, or declares new queue and returns proper name
            try {
                channel.basicConsume(queueName, true, (consumerTag, delivery) -> {//queue listener
                    Log.debug("Argon: Received Data on RabbitMQ Queue @", queueName);
                    Log.debug(new String(delivery.getBody()));
                    //deserialize body to proper class
                    T receivedData = objectMapper.readValue(delivery.getBody(), type);
                    //give data to all listeners
                    Seq<Cons<?>> listeners = events.get(type);
                    if (listeners != null) {
                        int len = listeners.size;
                        Cons[] items = listeners.items;
                        for (int i = 0; i < len; i++)
                            items[i].get(receivedData);
                    }
                }, ignored -> {
                });
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
            return new Seq<>(Cons.class);
        }).add(listener);//get listener seq and add this listener
    }

    @SuppressWarnings("unused")
    public static <T> void fire(T event) {
        boolean exchange = !event.getClass().isAnnotationPresent(NonExchangeEvent.class);

        String name = event.getClass().getSimpleName();
        getQueueName(name, exchange);//declare queue/exchange

        try {
            byte[] serializedData = objectMapper.writeValueAsBytes(event);
            Log.debug("Argon: Sending @", name);
            Log.debug(objectMapper.writeValueAsString(event));
            if (exchange) {
                channel.basicPublish(name + "_exchange", "", null, serializedData);
            } else {
                channel.basicPublish("", name, null, serializedData);
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @SuppressWarnings("BusyWait")
    private static Connection createConnectionWithRetry(ConnectionFactory factory) {
        long delay = 5 * 1000L;

        while (true) {
            try {
                Connection connection = factory.newConnection();
                Log.info("Argon: Connected to RabbitMQ!");
                return connection;
            } catch (Exception e) {
                Log.err("Argon: Failed to connect: " + e.getMessage());

                // Apply exponential backoff with a maximum delay of 60 seconds
                delay = Math.min(delay * 2, 60000);
                Log.warn("Argon: Retrying connection in " + delay / 1000 + " seconds...");
                try {
                    Thread.sleep(delay);
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException("Thread interrupted while waiting to reconnect.");
                }
            }
        }
    }

    @SuppressWarnings("unused")
    public static void shutdown() {
        shutdown = true;
        try {
            channel.close();
        } catch (IOException | TimeoutException e) {
            throw new RuntimeException(e);
        }
        try {
            connection.close();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}
