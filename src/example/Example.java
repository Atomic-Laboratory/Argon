package example;

import Argon.Argon;
import Argon.RegisterArgonEvents;
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

        Events.on(RegisterArgonEvents.class, ignored -> {
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
