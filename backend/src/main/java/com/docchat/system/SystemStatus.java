package com.docchat.system;

/** Snapshot of the backend's dependencies. The overall status is UP only if all of them are. */
public record SystemStatus(
        ComponentStatus status, ComponentStatus database, ComponentStatus vectorExtension, ComponentStatus ai) {

    static SystemStatus of(ComponentStatus database, ComponentStatus vectorExtension, ComponentStatus ai) {
        boolean allUp = database == ComponentStatus.UP
                && vectorExtension == ComponentStatus.UP
                && ai == ComponentStatus.UP;
        ComponentStatus overall = allUp ? ComponentStatus.UP : ComponentStatus.DOWN;
        return new SystemStatus(overall, database, vectorExtension, ai);
    }
}
