package org.hanbat.ses.dialogue.routing;

import java.util.Optional;

/** 요청문을 서브태스크 템플릿으로 보낸다. */
public interface RequestRouter {

    Optional<RoutingDecision> route(String request);
}
