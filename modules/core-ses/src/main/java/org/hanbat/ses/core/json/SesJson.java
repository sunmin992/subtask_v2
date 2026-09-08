package org.hanbat.ses.core.json;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import org.hanbat.ses.core.model.SesNode;

/**
 * Spring 무의존 환경(CLI, 단위 테스트)에서 쓰는 ObjectMapper.
 * 앱 쪽 Jackson 설정도 같은 규칙을 따라야 직렬화 왕복이 깨지지 않는다.
 */
public final class SesJson {

    private static final ObjectMapper MAPPER = create();

    private SesJson() {
    }

    public static ObjectMapper create() {
        return new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .disable(SerializationFeature.FAIL_ON_EMPTY_BEANS)
                // 도메인 정의 파일에 주석성 필드가 섞여도 로딩이 깨지지 않게 한다.
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    }

    public static ObjectMapper mapper() {
        return MAPPER;
    }

    public static String write(Object value) {
        try {
            return MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(value);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static <T> T read(String json, Class<T> type) {
        try {
            return MAPPER.readValue(json, type);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * SES 트리를 파일에서 읽는다.
     *
     * <p>{@code {"id":..,"tree":{..}}} 형태의 정의 봉투와 트리만 담긴 파일을 모두 받아들인다.
     * 등록용 파일과 CLI 입력이 같은 파일이면 좋은데, 등록에는 id·버전이 필요하고
     * CLI 에는 트리만 필요해서 두 모양이 다 돌아다닌다.
     */
    public static SesNode readTree(Path file) {
        try {
            String json = Files.readString(file);
            com.fasterxml.jackson.databind.JsonNode root = MAPPER.readTree(json);
            if (root.isObject() && root.has("tree")) {
                return MAPPER.treeToValue(root.get("tree"), SesNode.class);
            }
            return MAPPER.readValue(json, SesNode.class);
        } catch (IOException e) {
            throw new UncheckedIOException("SES 파일을 읽을 수 없습니다: " + file, e);
        }
    }
}
