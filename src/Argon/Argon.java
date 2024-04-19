package Argon;

import arc.Events;
import arc.func.Cons;
import arc.struct.StringMap;
import arc.util.CommandHandler;
import arc.util.Log;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rabbitmq.client.*;
import mindustry.Vars;
import mindustry.game.EventType;
import mindustry.mod.Plugin;

import java.io.IOException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeoutException;

public class Argon extends Plugin {
    private static final StringMap queuesDeclared = new StringMap();
    private static final StringMap exchangeQueues = new StringMap();
    private static final ConcurrentHashMap<String, CopyOnWriteArrayList<ListenerPair>> events = new ConcurrentHashMap<>();
    private static final ObjectMapper objectMapper = new ObjectMapper();
    private static ShutdownListener sdl;
    private static ConnectionFactory factory;
    private static Connection connection;
    private static Channel channel;
    private static boolean shutdown = false;

    static {
        sdl = cause -> {
            if (shutdown) return;

            connection = createConnectionWithRetry(factory);
            connection.addShutdownListener(sdl);
            try {
                channel = connection.createChannel();
            } catch (IOException e) {
                Log.err(e);
            }
            Log.debug("Argon: Firing RegisterRabbitQueues");
            Events.fire(new RegisterArgonEvents());
        };
    }

    private static String getQueueName(String name, boolean exchange) {
        if (exchange) {
            return exchangeQueues.get(name, () -> {
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
            return queuesDeclared.get(name, () -> {
                try {
                    Log.debug("Argon: Setting up queue @", name);
                    channel.queueDeclare(name, false, false, false, null);
                } catch (IOException e) {
                    Log.err(e);
                }
                return name;
            });
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes", "unused"})
    public static <T> void on(Class<T> type, Cons<T> listener) {
        boolean exchange = !type.isAnnotationPresent(NonExchangeEvent.class);

        events.computeIfAbsent(type.getSimpleName(), k -> {
            //if no listener found, declare a new one
            String queueName = getQueueName(k, exchange);//gets, or declares new queue and returns proper name
            try {
                channel.basicConsume(queueName, true, (consumerTag, delivery) -> {//queue listener
                    Log.debug("Argon: Received Data on RabbitMQ Queue @", k);
                    Log.debug(new String(delivery.getBody()));
                    //give data to all event listeners
                    for (ListenerPair ep : events.get(k)) {
                        Cons c = ep.cons;
                        //deserialize body to proper class
                        var receivedData = objectMapper.readValue(delivery.getBody(), ep.tClass);
                        //fire the event
                        c.get(receivedData);
                    }
                }, ignored -> {
                });
            } catch (AlreadyClosedException ace) {
                shutdown(false);
                start();
            } catch (IOException e) {
                Log.err(e);
            }
            return new CopyOnWriteArrayList<>();
        }).add(new ListenerPair(type, listener));//get listener seq and add this listener
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
        } catch (AlreadyClosedException ace) {
            shutdown(false);
            start();
        } catch (IOException e) {
            Log.err(e);
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
                    Log.err(new RuntimeException("Thread interrupted while waiting to reconnect."));
                }
            }
        }
    }

    private static void start() {
        var configFolder = Vars.modDirectory.child("Argon/");
        configFolder.mkdirs();
        var configFi = configFolder.child("config.json");

        boolean bad = false;

        badExit:
        if (configFi.exists()) {
            RabbitMQDetails factoryDetails = new RabbitMQDetails();

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
                channel = connection.createChannel();
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

        Log.debug("Argon: Firing RegisterRabbitQueues");
        Events.fire(new RegisterArgonEvents());
        Log.info("Argon started successfully!");
    }

    @SuppressWarnings("unused")
    public static void shutdown(boolean forever) {
        shutdown = forever;

        queuesDeclared.clear();
        exchangeQueues.clear();
        events.clear();

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

    @Override
    public void init() {
        Events.on(EventType.ServerLoadEvent.class, event -> start());
    }

    @Override
    public void registerServerCommands(CommandHandler handler) {
        handler.register("argon-restart", "Restart the RabbitMQ connection or Argon", args -> {
            shutdown(false);
            start();
        });
    }

    record ListenerPair(Class<?> tClass, Cons<?> cons) {
    }
}
