package org.hanbat.ses.template.registry;

public class ModelBaseNotFoundException extends RuntimeException {

    public ModelBaseNotFoundException(String modelId) {
        super("모델 베이스 항목을 찾을 수 없습니다: " + modelId);
    }
}
