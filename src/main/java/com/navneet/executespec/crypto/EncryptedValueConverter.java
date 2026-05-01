package com.navneet.executespec.crypto;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

/**
 * JPA {@link AttributeConverter} that transparently encrypts/decrypts string values using {@link
 * SimpleKeyEncryptionService}.
 *
 * <p>Apply to any entity field:
 *
 * <pre>
 * &#64;Convert(converter = EncryptedValueConverter.class)
 * private String anthropicApiKey;
 * </pre>
 *
 * <p>Handles nulls gracefully (null in → null out).
 */
@Converter
@Component
@RequiredArgsConstructor
public class EncryptedValueConverter implements AttributeConverter<String, String> {

  private final SimpleKeyEncryptionService encryptionService;

  @Override
  public String convertToDatabaseColumn(String attribute) {
    if (attribute == null) return null;
    return encryptionService.encrypt(attribute);
  }

  @Override
  public String convertToEntityAttribute(String dbData) {
    if (dbData == null) return null;
    return encryptionService.decrypt(dbData);
  }
}
