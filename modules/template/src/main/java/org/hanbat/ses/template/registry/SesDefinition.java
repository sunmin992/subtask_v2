package org.hanbat.ses.template.registry;

import org.hanbat.ses.core.model.SesNode;

/** 도메인 SES 정의 한 건. 여러 템플릿이 같은 도메인 SES 를 공유할 수 있다. */
public record SesDefinition(String id, String domain, String version, SesNode tree) {
}
