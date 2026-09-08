package org.hanbat.ses.template.registry;

public class SesDefinitionNotFoundException extends RuntimeException {

    public SesDefinitionNotFoundException(String id) {
        super("도메인 SES 정의를 찾을 수 없습니다: " + id);
    }
}
