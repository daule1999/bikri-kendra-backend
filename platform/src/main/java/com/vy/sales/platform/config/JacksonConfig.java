package com.vy.sales.platform.config;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.Module;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.module.SimpleModule;
import java.io.IOException;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Ensures all LocalDateTime fields in HTTP responses are serialised with an explicit +05:30 offset
 * so browsers on any OS timezone display the correct IST time.
 *
 * <p>Uses a Jackson {@link Module} bean instead of Jackson2ObjectMapperBuilderCustomizer — Spring
 * Boot's JacksonAutoConfiguration picks up all Module beans automatically, and this approach is
 * compatible with Spring Boot 3.x and 4.x.
 */
@Configuration
public class JacksonConfig {

  private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
  private static final DateTimeFormatter FORMATTER =
      DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSXXX");

  @Bean
  public Module jacksonISTModule() {
    SimpleModule module = new SimpleModule("ISTLocalDateTimeModule");

    module.addSerializer(
        LocalDateTime.class,
        new JsonSerializer<LocalDateTime>() {
          @Override
          public void serialize(
              LocalDateTime value, JsonGenerator gen, SerializerProvider serializers)
              throws IOException {
            gen.writeString(value.atZone(IST).format(FORMATTER));
          }
        });

    module.addDeserializer(
        LocalDateTime.class,
        new JsonDeserializer<LocalDateTime>() {
          @Override
          public LocalDateTime deserialize(JsonParser p, DeserializationContext ctx)
              throws IOException {
            String s = p.getText();
            if (s.contains("+") || s.endsWith("Z")) {
              return OffsetDateTime.parse(s).atZoneSameInstant(IST).toLocalDateTime();
            }
            return LocalDateTime.parse(s, DateTimeFormatter.ISO_LOCAL_DATE_TIME);
          }
        });

    return module;
  }
}
