![GitHub Downloads (all assets, all releases)](https://img.shields.io/github/downloads/Atomic-Laboratory/Argon/total)
![GitHub Release](https://img.shields.io/github/v/release/Atomic-Laboratory/Argon)
![Discord](https://img.shields.io/discord/1158888581964779530)

### Overview
Argon is a Mindustry RabbitMQ client that sends events across servers through the use of Mindustry-like events.

### Example
[View the file](https://github.com/Atomic-Laboratory/Argon/tree/master/src/example/Example.java)
```java
package example;

import Argon.Argon;
import Argon.RegisterRabbitQueues;
import arc.Events;
import arc.util.Log;
import mindustry.Vars;
import mindustry.game.EventType;
import mindustry.mod.Plugin;

public class Example extends Plugin {
    @Override
    public void init() {
        Events.on(EventType.PlayerBanEvent.class, event -> {
            Log.debug("Firing ban event across servers");
            Argon.fire(new PlayerKick(event.uuid, event.player.con.address));
        });
        
        Events.on(RegisterRabbitQueues.class, ignored -> {
            //Argon events listeners go here
            Argon.on(PlayerKick.class, event -> {
                Log.debug("Received ban event");
                Vars.netServer.admins.banPlayer(event.uuid);
                Vars.netServer.admins.banPlayerIP(event.ip);
            });
        });
    }

    private record PlayerKick(String uuid, String ip) {}
}
```


### Setup

#### Rabbit MQ Server
Google how to install a RabbitMQ server, or chatGPT it. The following is a basic, not all-encompassing guide for linux.
1) Install Rabbit MQ on your server.  
`sudo apt install rabbitmq-server`
2) Create a RabbitMQ User  
`sudo rabbitmqctl add_user username password`
3) Grand the user external access (so it can talk outside localhost)  
`sudo rabbitmqctl set_permissions -p / username ".*" ".*" ".*"`
4) Restart RabbitMQ  
`sudo systemctl restart rabbitmq-server`

#### Game Server
1) Go to [Relases](https://github.com/Atomic-Laboratory/Argon/releases/latest), download `Argon.jar`, and place it inside your mindustry server's `mods` folder.
2) Find `./config/mods/Argon/config.json` and set the proper RabbitMQ url, port, username and password.

### Development
In order to use this plugin with your custom plugin, you will need to add Argon as dependency.

In gradle, add the following:
```groovy
dependencies {
  //other dependencies
  compileOnly "com.github.Atomic-Laboratory:Argon:1.0.0"
}
```
In your plugins.json, append the following:
```json
"dependencies": [
  "Argon (A RabbitMQ Client)"
]
```


### Building a Jar

`gradlew jar` / `./gradlew jar`

Output jar should be in `build/libs`.