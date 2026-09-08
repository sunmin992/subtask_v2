package org.hanbat.ses.template.registry;

public class TemplateNotFoundException extends RuntimeException {

    public TemplateNotFoundException(String id) {
        super("템플릿을 찾을 수 없습니다: " + id);
    }

    public TemplateNotFoundException(String id, String version) {
        super("템플릿을 찾을 수 없습니다: " + id + "@" + version);
    }
}
