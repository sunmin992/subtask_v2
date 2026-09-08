package org.hanbat.ses.template.model;

import java.time.LocalDate;

public record TemplateMeta(String author, LocalDate createdAt, String description) {
}
