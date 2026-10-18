package com.hkin.exiledsettlement.settlement;

import org.jetbrains.annotations.Nullable;

public record OperationResult<T>(boolean success, String reason, @Nullable T value) {
    public static <T> OperationResult<T> success(T value) {
        return new OperationResult<>(true, "OK", value);
    }

    public static <T> OperationResult<T> failure(String reason) {
        return new OperationResult<>(false, reason, null);
    }
}

