package net.minecraftforge.eventbus.api;

import java.util.function.Consumer;

public interface IEventBus {
    <T> void addListener(Consumer<T> consumer);
}
