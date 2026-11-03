package com.docchat.document;

import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.unit.DataSize;
import org.springframework.validation.annotation.Validated;

/** @param storagePerUser total size of the files one user can keep, so one account cannot fill the disk */
@Validated
@ConfigurationProperties("docchat.documents")
record DocumentProperties(@NotNull DataSize storagePerUser) {}
