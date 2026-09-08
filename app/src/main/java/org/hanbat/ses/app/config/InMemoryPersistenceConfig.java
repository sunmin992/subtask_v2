package org.hanbat.ses.app.config;

import org.hanbat.ses.dialogue.session.InMemorySessionStore;
import org.hanbat.ses.dialogue.session.SessionStore;
import org.hanbat.ses.llm.audit.LlmCallRecorder;
import org.hanbat.ses.scenario.model.InMemoryStores;
import org.hanbat.ses.scenario.model.RunStore;
import org.hanbat.ses.scenario.model.ScenarioStore;
import org.hanbat.ses.template.registry.InMemoryRegistries;
import org.hanbat.ses.template.registry.ModelBaseRegistry;
import org.hanbat.ses.template.registry.SesRegistry;
import org.hanbat.ses.template.registry.TemplateRegistry;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * DB 없이 도는 구성 ({@code app.persistence=memory}).
 *
 * <p>계획서의 Phase 1~3 은 LLM 도 DB 도 없이 완성하는 것이 목표다. 이 구성 덕분에
 * E2E 테스트가 Docker 없이 밀리초 단위로 돌고, 시연할 때도 PostgreSQL 을 띄울 필요가 없다.
 * {@code application-memory.yml} 이 DataSource/Flyway 자동설정을 함께 끈다.
 */
@Configuration
@ConditionalOnProperty(name = "app.persistence", havingValue = "memory")
public class InMemoryPersistenceConfig {

    @Bean
    public SessionStore sessionStore() {
        return new InMemorySessionStore();
    }

    @Bean
    public TemplateRegistry templateRegistry() {
        return new InMemoryRegistries.Templates();
    }

    @Bean
    public SesRegistry sesRegistry() {
        return new InMemoryRegistries.SesDefinitions();
    }

    @Bean
    public ModelBaseRegistry modelBaseRegistry() {
        return new InMemoryRegistries.ModelBases();
    }

    @Bean
    public ScenarioStore scenarioStore() {
        return new InMemoryStores.Scenarios();
    }

    @Bean
    public RunStore runStore() {
        return new InMemoryStores.Runs();
    }

    @Bean
    public LlmCallRecorder llmCallRecorder() {
        return LlmCallRecorder.noop();
    }
}
