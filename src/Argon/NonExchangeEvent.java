package Argon;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

// For events that should only be processed by one of many listeners.
// Not having this flag will send the event to all listeners on the channel.
@Retention(RetentionPolicy.RUNTIME)
public @interface NonExchangeEvent {
}
