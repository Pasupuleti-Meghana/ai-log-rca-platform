package com.logsense.dto;

import org.springframework.data.domain.Page;

import java.util.List;

/** Stable JSON representation of a page (Spring's PageImpl JSON is not a stable contract). */
public record PageResponse<T>(List<T> content, int page, int size, long totalElements, int totalPages, String sort) {

    public static <T> PageResponse<T> of(Page<T> page) {
        return new PageResponse<>(page.getContent(), page.getNumber(), page.getSize(), page.getTotalElements(),
                page.getTotalPages(), page.getSort().toString());
    }
}
