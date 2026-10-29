package com.iemr.admin.data.nikshay.dtos;

import java.util.List;
import lombok.Builder;
import lombok.Value;

/** Summary returned by POST /nikshay/transfer-out. */
@Value
@Builder
public class TransferOutResponse {
    int fetched;
    int eligible;
    int transferred;
    int held;
    int failed;
    int devicesProcessed;
    long durationMs;
    List<TransferOutcome> results;
}
