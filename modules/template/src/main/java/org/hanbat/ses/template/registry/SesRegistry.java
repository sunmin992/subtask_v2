package org.hanbat.ses.template.registry;

import java.util.List;
import java.util.Optional;

public interface SesRegistry {

    Optional<SesDefinition> find(String id);

    List<SesDefinition> findAll();

    SesDefinition register(SesDefinition definition);
}
