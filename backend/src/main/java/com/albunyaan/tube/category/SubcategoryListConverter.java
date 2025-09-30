package com.albunyaan.tube.category;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import java.util.Collections;
import java.util.List;

@Converter
public class SubcategoryListConverter implements AttributeConverter<List<Subcategory>, String> {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final TypeReference<List<Subcategory>> TYPE = new TypeReference<>() {};

    @Override
    public String convertToDatabaseColumn(List<Subcategory> attribute) {
        if (attribute == null || attribute.isEmpty()) {
            return "[]";
        }
        try {
            return OBJECT_MAPPER.writeValueAsString(attribute);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Failed to serialize subcategories", ex);
        }
    }

    @Override
    public List<Subcategory> convertToEntityAttribute(String dbData) {
        if (dbData == null || dbData.isBlank()) {
            return List.of();
        }
        try {
            var value = OBJECT_MAPPER.readValue(dbData, TYPE);
            return Collections.unmodifiableList(value);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Failed to deserialize subcategories", ex);
        }
    }
}
