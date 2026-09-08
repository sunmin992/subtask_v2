package org.hanbat.ses.app;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.scheduling.annotation.EnableAsync;

/**
 * SES 기반 LLM 시나리오 생성 서버.
 *
 * <p>모듈이 org.hanbat.ses 아래로 흩어져 있으므로 스캔 기준점을 상위 패키지로 올린다.
 * JPA 활성화는 여기 두지 않는다 — memory 프로파일에서 DataSource 자동설정을 껐는데
 * 애플리케이션 클래스가 리포지토리를 강제로 만들면 EntityManagerFactory 를 찾다 죽는다.
 * 조건부 활성화는 JpaPersistenceConfig 가 맡는다.
 */
@SpringBootApplication(scanBasePackages = "org.hanbat.ses")
@EnableCaching
@EnableAsync
public class SesScenarioServerApplication {

    public static void main(String[] args) {
        SpringApplication.run(SesScenarioServerApplication.class, args);
    }
}
