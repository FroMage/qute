package com.github.mkouba.qute;

import java.util.Collection;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

import com.github.mkouba.qute.Results.Result;

public final class ValueResolvers {

    static final String THIS = "this";

    public static ValueResolver collectionResolver() {
        return ValueResolver.match(Collection.class).resolveAsync(ValueResolvers::collectionResolveAsync).build();
    }

    public static ValueResolver thisResolver() {
        return ValueResolver.match(Object.class).andAppliesTo(ValueResolvers::thisAppliesTo).resolve(c -> c.getBase()).build();
    }

    /**
     * {@code foo.or(bar)},{@code foo or true},{@code name ?: 'elvis'}
     */
    public static ValueResolver orResolver() {
        return ValueResolver.match(Object.class).andAppliesTo(ValueResolvers::orAppliesTo).resolveAsync(ValueResolvers::orResolveAsync)
                .build();
    }

    public static ValueResolver mapEntryResolver() {
        return ValueResolver.match(Entry.class).resolve(ValueResolvers::entryResolve).build();
    }
    
    public static ValueResolver mapResolver() {
        return ValueResolver.match(Map.class).resolveAsync(ValueResolvers::mapResolveAsync).build();
    }

    // helper methods

    private static CompletionStage<Object> collectionResolveAsync(EvalContext context) {
        Collection<?> collection = (Collection<?>) context.getBase();
        switch (context.getName()) {
            case "size":
                return CompletableFuture.completedFuture(collection.size());
            case "isEmpty":
            case "empty":
                return CompletableFuture.completedFuture(collection.isEmpty());
            case "contains":
                if (context.getParams().size() == 1) {
                    return context.evaluate(context.getParams().get(0)).thenCompose(e -> {
                        return CompletableFuture.completedFuture(collection.contains(e));
                    });
                }
            default:
                return Results.NOT_FOUND;
        }
    }

    private static boolean thisAppliesTo(EvalContext context) {
        return THIS.equals(context.getName());
    }

    private static boolean orAppliesTo(EvalContext context) {
        return context.getParams().size() == 1
                && ("?:".equals(context.getName()) || "or".equals(context.getName()));
    }

    private static CompletionStage<Object> orResolveAsync(EvalContext context) {
        if (context.getBase() == null || Results.Result.NOT_FOUND.equals(context.getBase())) {
            return context.evaluate(context.getParams().get(0));
        }
        return CompletableFuture.completedFuture(context.getBase());
    }

    private static Object entryResolve(Entry<?, ?> entry, String name) {
        switch (name) {
            case "key":
            case "getKey":
                return entry.getKey();
            case "value":
            case "getValue":
                return entry.getValue();
            default:
                return Result.NOT_FOUND;
        }
    }
    
    @SuppressWarnings("rawtypes")
    private static CompletionStage<Object> mapResolveAsync(EvalContext context) {
        Map map = (Map) context.getBase();
        if (map.containsKey(context.getName())) {
            return CompletableFuture.completedFuture(map.get(context.getName()));
        }
        switch (context.getName()) {
            case "keys":
            case "keySet":
                return CompletableFuture.completedFuture(map.keySet());
            case "values":
                return CompletableFuture.completedFuture(map.values());
            case "size":
                return CompletableFuture.completedFuture(map.size());
            case "empty":
            case "isEmpty":
                return CompletableFuture.completedFuture(map.isEmpty());
            case "get":
                if (context.getParams().size() == 1) {
                    return context.evaluate(context.getParams().get(0)).thenCompose(k -> {
                        return CompletableFuture.completedFuture(map.get(k));
                    });
                }
            case "containsKey":
                if (context.getParams().size() == 1) {
                    return context.evaluate(context.getParams().get(0)).thenCompose(k -> {
                        return CompletableFuture.completedFuture(map.containsKey(k));
                    });
                }
            default:
                return Results.NOT_FOUND;
        }
    }

}
