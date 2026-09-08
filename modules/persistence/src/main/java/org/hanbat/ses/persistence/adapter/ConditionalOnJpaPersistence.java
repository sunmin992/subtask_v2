package org.hanbat.ses.persistence.adapter;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

/**
 * {@code app.persistence=jpa} 일 때만 활성화한다.
 *
 * <p>Phase 1~3 을 DB 없이 검증할 수 있어야 한다는 요구에서 나온 장치다.
 * {@code app.persistence=memory} 로 두면 인메모리 구현이 대신 등록되고
 * DataSource 자동설정도 함께 꺼지므로 PostgreSQL 없이 서버가 뜬다.
 */
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@Documented
@ConditionalOnProperty(name = "app.persistence", havingValue = "jpa", matchIfMissing = true)
public @interface ConditionalOnJpaPersistence {
}
