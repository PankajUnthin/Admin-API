package com.iemr.admin.data.nikshay.dtos;

import java.time.Instant;
import lombok.Builder;
import lombok.Value;

/** A logged-in Ni-kshay session for one device. Immutable; discard after logout. */
@Value
@Builder
public class NikshaySession {
    Integer deviceConfigId;
    String deviceId;
    String phiUserId;
    /** portal origin, e.g. https://beta.nikshay.in */
    String origin;
    /** "name=value; name=value" for the portal origin */
    String cookieHeader;
    Instant loggedInAt;
}
