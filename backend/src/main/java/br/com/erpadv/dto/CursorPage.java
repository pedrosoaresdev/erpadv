package br.com.erpadv.dto;

import java.util.List;
import java.util.function.Function;

/** Página baseada em cursor. {@code nextCursor} nulo indica fim da lista. */
public record CursorPage<T>(List<T> items, String nextCursor) {

    public <R> CursorPage<R> map(Function<T, R> mapper) {
        return new CursorPage<>(items.stream().map(mapper).toList(), nextCursor);
    }
}
