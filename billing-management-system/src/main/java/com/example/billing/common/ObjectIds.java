package com.example.billing.common;

import com.example.billing.exception.BadRequestException;
import org.bson.types.ObjectId;

/** Checks that an ID looks like a MongoDB ObjectId (24 hexadecimal characters). */
public final class ObjectIds {

    private ObjectIds() {
    }

    public static void requireValid(String id, String label) {
        if (id == null || !ObjectId.isValid(id)) {
            throw new BadRequestException("Invalid " + label + " '" + id
                    + "'. A MongoDB ID must be 24 hexadecimal characters.");
        }
    }
}
