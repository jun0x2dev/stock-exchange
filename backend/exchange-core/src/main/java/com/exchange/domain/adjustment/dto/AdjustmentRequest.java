package com.exchange.domain.adjustment.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/** 되돌릴 입금·입고와 사유. */
public record AdjustmentRequest(@NotNull @Positive Long depositId, @NotBlank @Size(max = 200) String reason) {
}
