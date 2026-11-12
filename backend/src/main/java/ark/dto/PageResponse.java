package ark.dto;

import java.util.List;

/** Standard paged list response: items + total for page-number pagination in the UI. */
public record PageResponse<T>(List<T> items, long total, int page, int size) {}
