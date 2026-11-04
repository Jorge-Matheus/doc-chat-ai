package com.docchat.storage;

import java.io.IOException;

/** Lets tests in other packages simulate a storage failure. */
public class StorageExceptionForTests extends StorageException {

    public StorageExceptionForTests() {
        super("simulated storage failure", new IOException("simulated"));
    }
}
