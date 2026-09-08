package org.hanbat.ses.app.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

/**
 * JPA 활성화 ({@code app.persistence=jpa}, 기본값).
 *
 * <p>조건을 걸어 두지 않으면 memory 프로파일에서도 리포지토리 프록시가 만들어지고,
 * EntityManagerFactory 가 없어 컨텍스트가 뜨지 않는다.
 */
@Configuration
@ConditionalOnProperty(name = "app.persistence", havingValue = "jpa", matchIfMissing = true)
@EntityScan(basePackages = "org.hanbat.ses.persistence.entity")
@EnableJpaRepositories(basePackages = "org.hanbat.ses.persistence.repository")
public class JpaPersistenceConfig {
}
