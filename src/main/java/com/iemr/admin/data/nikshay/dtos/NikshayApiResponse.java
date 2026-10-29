package com.iemr.admin.data.nikshay.dtos;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.Data;

/** The {Success, Data, Error, ErrorMessage} envelope Ni-kshay endpoints return. */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class NikshayApiResponse {

    @JsonProperty("Success")
    private Boolean success;

    @JsonProperty("Data")
    private JsonNode data;

    @JsonProperty("Error")
    private JsonNode error;

    @JsonProperty("ErrorMessage")
    private String errorMessage;

    public boolean isSuccess() {
        return Boolean.TRUE.equals(success);
    }

    /** Best human-readable error the portal gave, or null. */
    public String errorText() {
        if (errorMessage != null && !errorMessage.isBlank()) {
            return errorMessage;
        }
        if (error == null || error.isNull()) {
            return null;
        }
        if (error.isTextual()) {
            return error.asText();
        }
        JsonNode m = error.get("Message");
        return m != null ? m.asText() : error.toString();
    }
}
