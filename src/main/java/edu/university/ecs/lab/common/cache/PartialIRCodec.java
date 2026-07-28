package edu.university.ecs.lab.common.cache;

import edu.university.ecs.lab.common.models.dto.PartialMicroserviceSystemDto;
import edu.university.ecs.lab.common.utils.JsonReadWriteUtils;

import java.io.IOException;
import java.io.UncheckedIOException;

/**
 * Serializes partial IRs to/from JSON strings for cache storage.
 *
 * <p>Uses the exact same Jackson configuration as the legacy {@code PART_*.json}
 * files ({@link JsonReadWriteUtils}), so cached payloads are byte-compatible with
 * the previous on-disk format.
 */
final class PartialIRCodec {

    private PartialIRCodec() {
    }

    static String toJson(PartialMicroserviceSystemDto dto) {
        try {
            return JsonReadWriteUtils.writeToJSONString(dto);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to serialize partial IR for caching", e);
        }
    }

    static PartialMicroserviceSystemDto fromJson(String json) {
        try {
            return JsonReadWriteUtils.readFromJSONString(json, PartialMicroserviceSystemDto.class);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to deserialize cached partial IR", e);
        }
    }
}
