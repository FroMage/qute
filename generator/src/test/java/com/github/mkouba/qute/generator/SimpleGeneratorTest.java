package com.github.mkouba.qute.generator;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.io.InputStream;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import org.jboss.jandex.DotName;
import org.jboss.jandex.Index;
import org.jboss.jandex.Indexer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.github.mkouba.qute.Engine;
import com.github.mkouba.qute.EvalContext;
import com.github.mkouba.qute.Expression;
import com.github.mkouba.qute.IfSectionHelper;
import com.github.mkouba.qute.ImmutableList;
import com.github.mkouba.qute.ValueResolver;
import com.github.mkouba.qute.ValueResolvers;

public class SimpleGeneratorTest {

    @BeforeAll
    public static void init() throws IOException {
        TestClassOutput classOutput = new TestClassOutput();
        Index index = index(MyService.class, PublicMyService.class, MyItem.class, String.class, CompletionStage.class,
                List.class);
        ValueResolverGenerator generator = new ValueResolverGenerator(index, classOutput, Collections.emptyMap());
        generator.generate(index.getClassByName(DotName.createSimple(MyService.class.getName())));
        generator.generate(index.getClassByName(DotName.createSimple(PublicMyService.class.getName())));
        generator.generate(index.getClassByName(DotName.createSimple(MyItem.class.getName())));
        generator.generate(index.getClassByName(DotName.createSimple(String.class.getName())));
        generator.generate(index.getClassByName(DotName.createSimple(List.class.getName())));
    }

    @Test
    public void testGenerator() throws Exception {
        Class<?> clazz = SimpleGeneratorTest.class.getClassLoader()
                .loadClass("com.github.mkouba.qute.generator.MyService_ValueResolver");
        ValueResolver resolver = (ValueResolver) clazz.newInstance();
        assertEquals("Foo",
                resolver.resolve(new TestEvalContext(new MyService(), "getName", Collections.emptyList(), null))
                        .toCompletableFuture().get(1, TimeUnit.SECONDS).toString());
        assertEquals("[0, 1, 2, 3, 4, 5, 6, 7, 8, 9]",
                resolver.resolve(new TestEvalContext(new MyService(), "getList", ImmutableList.of("1", "foo"),
                        e -> "foo".equals(e.parts.get(0)) ? CompletableFuture.completedFuture("foo")
                                : CompletableFuture.completedFuture(Integer.valueOf(10))))
                        .toCompletableFuture().get(1, TimeUnit.SECONDS).toString());
        assertEquals("oof",
                resolver.resolve(new TestEvalContext(new MyService(), "getTestName", Collections.emptyList(), null))
                        .toCompletableFuture().get(1, TimeUnit.SECONDS).toString());
        assertEquals("Emma",
                resolver.resolve(new TestEvalContext(new MyService(), "getAnotherTestName", Collections.singletonList("Emma"),
                        v -> CompletableFuture.completedFuture(v.parts.get(0))))
                        .toCompletableFuture().get(1, TimeUnit.SECONDS).toString());
        assertEquals("NOT_FOUND",
                resolver.resolve(new TestEvalContext(new MyService(), "surname", Collections.emptyList(), null))
                        .toCompletableFuture().get(1, TimeUnit.SECONDS).toString());
    }

    @Test
    public void testWithEngine() throws Exception {
        Engine engine = Engine.builder().addSectionHelper(new IfSectionHelper.Factory())
                .addValueResolver(ValueResolvers.thisResolver())
                .addValueResolver(newResolver("com.github.mkouba.qute.generator.MyService_ValueResolver"))
                .addValueResolver(newResolver("com.github.mkouba.qute.generator.PublicMyService_ValueResolver"))
                .addValueResolver(newResolver("com.github.mkouba.qute.generator.MyItem_ValueResolver"))
                .addValueResolver(newResolver("com.github.mkouba.qute.String_ValueResolver"))
                .addValueResolver(newResolver("com.github.mkouba.qute.List_ValueResolver"))
                .build();
        assertEquals(" FOO ", engine.parse("{#if isActive} {name.toUpperCase} {/if}").render(new MyService()));
        assertEquals("OK", engine.parse("{#if this.getList(5).size == 5}OK{/if}").render(new MyService()));
        assertEquals("Martin NOT_FOUND", engine.parse("{name} {surname}").render(new PublicMyService()));
        assertEquals("foo NOT_FOUND", engine.parse("{id} {bar}").render(new MyItem()));
    }

    private ValueResolver newResolver(String className)
            throws ClassNotFoundException, InstantiationException, IllegalAccessException {
        ClassLoader cl = Thread.currentThread().getContextClassLoader();
        if (cl == null) {
            cl = SimpleGeneratorTest.class.getClassLoader();
        }
        Class<?> clazz = cl.loadClass(className);
        return (ValueResolver) clazz.newInstance();
    }

    private static Index index(Class<?>... classes) throws IOException {
        Indexer indexer = new Indexer();
        for (Class<?> clazz : classes) {
            try (InputStream stream = SimpleGeneratorTest.class.getClassLoader()
                    .getResourceAsStream(clazz.getName().replace('.', '/') + ".class")) {
                indexer.index(stream);
            }
        }
        return indexer.complete();
    }

    static class TestEvalContext implements EvalContext {

        private final Object base;
        private final String name;
        private final List<String> params;
        private Function<Expression, CompletionStage<Object>> evaluate;

        public TestEvalContext(Object base, String name, List<String> params,
                Function<Expression, CompletionStage<Object>> evaluate) {
            this.base = base;
            this.name = name;
            this.params = params;
            this.evaluate = evaluate;
        }

        @Override
        public Object getBase() {
            return base;
        }

        @Override
        public String getName() {
            return name;
        }

        @Override
        public List<String> getParams() {
            return params;
        }

        @Override
        public CompletionStage<Object> evaluate(Expression expression) {
            return evaluate.apply(expression);
        }

    }

}
