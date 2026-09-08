package org.hanbat.ses.app.config;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import org.hibernate.type.format.jackson.JacksonJsonFormatMapper;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.boot.autoconfigure.orm.jpa.HibernatePropertiesCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Jackson 설정.
 *
 * <p>중요한 것은 <b>Hibernate 가 JSONB 를 쓸 때도 같은 ObjectMapper 를 쓰게 하는 것</b>이다.
 * Hibernate 는 기본적으로 자기 ObjectMapper 를 만들어 쓰는데, 그러면 SesNode 의 폴리모픽
 * 설정이나 날짜 표기가 API 응답과 DB 사이에서 미묘하게 달라진다. 그 차이는 보통
 * "저장은 됐는데 읽으면 깨진다"는 형태로, 한참 뒤에 발견된다.
 */
@Configuration
public class JacksonConfig {

    @Bean
    public Jackson2ObjectMapperBuilderCustomizer sesJacksonCustomizer() {
        return builder -> builder
                .featuresToDisable(
                        SerializationFeature.WRITE_DATES_AS_TIMESTAMPS,
                        SerializationFeature.FAIL_ON_EMPTY_BEANS,
                        DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    }

    @Bean
    public HibernatePropertiesCustomizer jsonFormatMapperCustomizer(ObjectMapper objectMapper) {
        return props -> props.put("hibernate.type.json_format_mapper",
                new JacksonJsonFormatMapper(objectMapper));
    }
}
