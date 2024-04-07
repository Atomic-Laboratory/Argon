package Argon;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import mindustry.Vars;

import java.util.Objects;

@SuppressWarnings("unused")
class RabbitMQDetails {
    private String url = "url_here";
    private int port = 5672;
    private String username = "username_here";
    private String password = "password_here";

    public RabbitMQDetails() {
    }

    public RabbitMQDetails(String url, int port, String username, String password) {
        this.url = url;
        this.port = port;
        this.username = username;
        this.password = password;
    }

    public String getUrl() {
        return url;
    }

    public void setUrl(String url) {
        this.url = url;
    }

    public int getPort() {
        return port;
    }

    public void setPort(int port) {
        this.port = port;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    public static void saveDefault() {
        var configFolder = Vars.modDirectory.child("Argon/");
        configFolder.mkdirs();
        var configFi = configFolder.child("config.json");
        try {
            configFi.writeString(new ObjectMapper().writeValueAsString(new RabbitMQDetails()));
        } catch (JsonProcessingException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        RabbitMQDetails that = (RabbitMQDetails) o;
        return getPort() == that.getPort() && Objects.equals(getUrl(), that.getUrl()) && Objects.equals(getUsername(), that.getUsername()) && Objects.equals(getPassword(), that.getPassword());
    }

    @Override
    public int hashCode() {
        return Objects.hash(getUrl(), getPort(), getUsername(), getPassword());
    }
}
