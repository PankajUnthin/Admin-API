package com.iemr.admin.data.nikshay.dtos;

import lombok.Data;

/**
 * Body of POST /nikshay/transfer-out. Phase 1: both values are placeholders and are only
 * checked for presence; the Ni-kshay login uses each NAAT device's credentials from
 * m_nikshay_device_configuration.
 */
@Data
public class TransferOutRequest {
    private String username;
    private String password;
}
