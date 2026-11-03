package com.docchat.system;

public enum ComponentStatus {
    UP,
    DOWN,
    /** The component could not be checked, for example because the database is unreachable. */
    UNKNOWN
}
