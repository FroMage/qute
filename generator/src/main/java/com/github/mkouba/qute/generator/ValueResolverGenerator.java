package com.github.mkouba.qute.generator;

import static org.objectweb.asm.Opcodes.ACC_PUBLIC;

import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.BiConsumer;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.jboss.jandex.AnnotationInstance;
import org.jboss.jandex.AnnotationTarget;
import org.jboss.jandex.AnnotationTarget.Kind;
import org.jboss.jandex.AnnotationValue;
import org.jboss.jandex.ClassInfo;
import org.jboss.jandex.DotName;
import org.jboss.jandex.FieldInfo;
import org.jboss.jandex.IndexView;
import org.jboss.jandex.MethodInfo;
import org.jboss.jandex.Type;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.github.mkouba.qute.EvalContext;
import com.github.mkouba.qute.TemplateData;
import com.github.mkouba.qute.ValueResolver;

import io.quarkus.gizmo.AssignableResultHandle;
import io.quarkus.gizmo.BranchResult;
import io.quarkus.gizmo.BytecodeCreator;
import io.quarkus.gizmo.ClassCreator;
import io.quarkus.gizmo.ClassOutput;
import io.quarkus.gizmo.FieldDescriptor;
import io.quarkus.gizmo.FunctionCreator;
import io.quarkus.gizmo.MethodCreator;
import io.quarkus.gizmo.MethodDescriptor;
import io.quarkus.gizmo.ResultHandle;

/**
 * 
 * 
 */
public class ValueResolverGenerator {

    public static Builder builder() {
        return new Builder();
    }

    public static final DotName TEMPLATE_DATA = DotName.createSimple(TemplateData.class.getName());
    public static final DotName TEMPLATE_DATA_CONTAINER = DotName.createSimple(TemplateData.Container.class.getName());

    private static final DotName COMPLETION_STAGE = DotName.createSimple(CompletionStage.class.getName());
    private static final DotName OBJECT = DotName.createSimple(Object.class.getName());

    public static final String SUFFIX = "_ValueResolver";

    private static final Logger LOGGER = LoggerFactory.getLogger(ValueResolverGenerator.class);

    private static final String GET_PREFIX = "get";
    private static final String IS_PREFIX = "is";

    private final Set<String> analyzedTypes;
    private final Set<String> generatedTypes;
    private final IndexView index;
    private final ClassOutput classOutput;
    private final Map<ClassInfo, AnnotationInstance> uncontrolled;

    ValueResolverGenerator(IndexView index, ClassOutput classOutput, Map<ClassInfo, AnnotationInstance> uncontrolled) {
        this.analyzedTypes = new HashSet<>();
        this.generatedTypes = new HashSet<>();
        this.classOutput = classOutput;
        this.index = index;
        this.uncontrolled = uncontrolled;
    }

    public Set<String> getGeneratedTypes() {
        return generatedTypes;
    }

    public Set<String> getAnalyzedTypes() {
        return analyzedTypes;
    }

    public void generate(ClassInfo clazz) {

        String clazzName = clazz.name().toString();
        if (analyzedTypes.contains(clazzName)) {
            return;
        }
        analyzedTypes.add(clazzName);

        // @TemplateData declared on class has precedence
        AnnotationInstance templateData = clazz.classAnnotation(TEMPLATE_DATA);
        if (templateData == null) {
            // Try to find @TemplateData declared on other classes
            templateData = uncontrolled.get(clazz);
        }

        Predicate<AnnotationTarget> filters = initFilters(templateData);

        LOGGER.debug("Analyzing {}", clazzName);

        String baseName;
        if (clazz.enclosingClass() != null) {
            baseName = simpleName(clazz.enclosingClass()) + "_" + simpleName(clazz);
        } else {
            baseName = simpleName(clazz);
        }
        String targetPackage = packageName(clazz.name());
        String generatedName = generatedNameFromTarget(targetPackage, baseName, SUFFIX);
        generatedTypes.add(generatedName);

        ClassCreator valueResolver = ClassCreator.builder().classOutput(classOutput).className(generatedName)
                .interfaces(ValueResolver.class).build();

        implementAppliesTo(valueResolver, clazz);
        implementResolve(valueResolver, clazzName, clazz, filters);

        valueResolver.close();

        if (!clazz.superName().equals(OBJECT)) {
            ClassInfo superClass = index.getClassByName(clazz.superClassType().name());
            if (superClass != null) {
                generate(superClass);
            } else {
                LOGGER.warn("Skipping super class {} - not found in the index", clazz.superClassType());
            }
        }
    }

    private void implementResolve(ClassCreator valueResolver, String clazzName, ClassInfo clazz,
            Predicate<AnnotationTarget> filter) {
        MethodCreator resolve = valueResolver.getMethodCreator("resolve", CompletionStage.class, EvalContext.class)
                .setModifiers(ACC_PUBLIC);

        ResultHandle evalContext = resolve.getMethodParam(0);
        ResultHandle base = resolve.invokeInterfaceMethod(Descriptors.GET_BASE, evalContext);

        ResultHandle name = resolve.invokeInterfaceMethod(Descriptors.GET_NAME, evalContext);
        ResultHandle params = resolve.invokeInterfaceMethod(Descriptors.GET_PARAMS, evalContext);
        ResultHandle paramsCount = resolve.invokeInterfaceMethod(Descriptors.COLLECTION_SIZE, params);

        // Fields
        List<FieldInfo> fields = clazz.fields().stream().filter(filter::test).collect(Collectors.toList());
        if (!fields.isEmpty()) {
            BytecodeCreator zeroParamsBranch = resolve.ifNonZero(paramsCount).falseBranch();
            for (FieldInfo field : fields) {
                LOGGER.debug("Field added: {}", field);
                // Match field name
                BytecodeCreator fieldMatch = zeroParamsBranch
                        .ifNonZero(
                                zeroParamsBranch.invokeVirtualMethod(Descriptors.EQUALS,
                                        resolve.load(field.name()), name))
                        .trueBranch();
                ResultHandle value;
                if (Modifier.isStatic(field.flags())) {
                    value = fieldMatch
                            .readStaticField(FieldDescriptor.of(clazzName, field.name(), field.type().name().toString()));
                } else {
                    value = fieldMatch
                            .readInstanceField(FieldDescriptor.of(clazzName, field.name(), field.type().name().toString()),
                                    base);
                }
                fieldMatch.returnValue(fieldMatch.invokeStaticMethod(Descriptors.COMPLETED_FUTURE, value));
            }
        }

        // Methods
        for (MethodInfo method : clazz.methods().stream().filter(filter::test).collect(Collectors.toList())) {
            LOGGER.debug("Method added {}", method);
            List<Type> methodParams = method.parameters();

            BytecodeCreator matchScope = resolve.createScope();
            // Match name
            BytecodeCreator notMatched = matchScope.ifNonZero(matchScope.invokeVirtualMethod(Descriptors.EQUALS,
                    matchScope.load(method.name()),
                    name))
                    .falseBranch();
            // Match the property name for getters,  ie. "foo" for "getFoo"
            if (methodParams.size() == 0 && isGetterName(method.name())) {
                notMatched.ifNonZero(notMatched.invokeVirtualMethod(Descriptors.EQUALS,
                        notMatched.load(getPropertyName(method.name())),
                        name)).falseBranch().breakScope(matchScope);
            } else {
                notMatched.breakScope(matchScope);
            }
            // Match number of params
            matchScope.ifNonZero(matchScope.invokeStaticMethod(Descriptors.INTEGER_COMPARE,
                    matchScope.load(methodParams.size()), paramsCount)).trueBranch().breakScope(matchScope);

            // Invoke the method
            ResultHandle ret;
            boolean hasCompletionStage = !skipMemberType(method.returnType())
                    && hasCompletionStageInTypeClosure(index.getClassByName(method.returnType().name()), index);
            if (method.parameters().size() > 0) {
                // We need to evaluate the params 
                ret = matchScope
                        .newInstance(MethodDescriptor.ofConstructor(CompletableFuture.class));

                ResultHandle resultsArray = matchScope.newArray(CompletableFuture.class,
                        matchScope.load(methodParams.size()));
                for (int i = 0; i < methodParams.size(); i++) {
                    ResultHandle evalResult = matchScope.invokeInterfaceMethod(
                            Descriptors.EVALUATE, evalContext,
                            matchScope.invokeInterfaceMethod(Descriptors.LIST_GET, params,
                                    matchScope.load(i)));
                    matchScope.writeArrayValue(resultsArray, i,
                            matchScope.invokeInterfaceMethod(Descriptors.CF_TO_COMPLETABLE_FUTURE, evalResult));
                }
                ResultHandle allOf = matchScope.invokeStaticMethod(Descriptors.COMPLETABLE_FUTURE_ALL_OF,
                        resultsArray);

                FunctionCreator whenCompleteFun = matchScope.createFunction(BiConsumer.class);
                matchScope.invokeInterfaceMethod(Descriptors.CF_WHEN_COMPLETE, allOf, whenCompleteFun.getInstance());

                BytecodeCreator whenComplete = whenCompleteFun.getBytecode();

                // TODO workaround for https://github.com/quarkusio/gizmo/issues/6
                AssignableResultHandle whenBase = whenComplete.createVariable(Object.class);
                whenComplete.assign(whenBase, base);
                AssignableResultHandle whenRet = whenComplete.createVariable(CompletableFuture.class);
                whenComplete.assign(whenRet, ret);
                AssignableResultHandle whenResults = whenComplete.createVariable(CompletableFuture[].class);
                whenComplete.assign(whenResults, resultsArray);

                BranchResult throwableIsNull = whenComplete.ifNull(whenComplete.getMethodParam(1));

                // complete
                BytecodeCreator success = throwableIsNull.trueBranch();

                ResultHandle[] paramsHandle = new ResultHandle[methodParams.size()];
                for (int i = 0; i < methodParams.size(); i++) {
                    ResultHandle paramResult = success.readArrayValue(whenResults, i);
                    paramsHandle[i] = success.invokeVirtualMethod(Descriptors.COMPLETABLE_FUTURE_GET, paramResult);
                }
                ResultHandle invokeRet;
                if (Modifier.isInterface(clazz.flags())) {
                    invokeRet = success.invokeInterfaceMethod(MethodDescriptor.of(method), whenBase, paramsHandle);
                } else {
                    invokeRet = success.invokeVirtualMethod(MethodDescriptor.of(method), whenBase, paramsHandle);
                }

                if (hasCompletionStage) {
                    FunctionCreator invokeWhenCompleteFun = success.createFunction(BiConsumer.class);
                    success.invokeInterfaceMethod(Descriptors.CF_WHEN_COMPLETE, invokeRet, invokeWhenCompleteFun.getInstance());
                    BytecodeCreator invokeWhenComplete = invokeWhenCompleteFun.getBytecode();

                    // TODO workaround for https://github.com/quarkusio/gizmo/issues/6
                    AssignableResultHandle invokeWhenRet = invokeWhenComplete.createVariable(CompletableFuture.class);
                    invokeWhenComplete.assign(invokeWhenRet, whenRet);

                    BranchResult invokeThrowableIsNull = invokeWhenComplete.ifNull(invokeWhenComplete.getMethodParam(1));
                    BytecodeCreator invokeSuccess = invokeThrowableIsNull.trueBranch();
                    invokeSuccess.invokeVirtualMethod(Descriptors.COMPLETABLE_FUTURE_COMPLETE, invokeWhenRet,
                            invokeWhenComplete.getMethodParam(0));
                    BytecodeCreator invokeFailure = invokeThrowableIsNull.falseBranch();
                    invokeFailure.invokeVirtualMethod(Descriptors.COMPLETABLE_FUTURE_COMPLETE_EXCEPTIONALLY, invokeWhenRet,
                            invokeWhenComplete.getMethodParam(1));
                    invokeWhenComplete.returnValue(null);
                } else {
                    success.invokeVirtualMethod(Descriptors.COMPLETABLE_FUTURE_COMPLETE, whenRet, invokeRet);
                }

                // completeExceptionally
                BytecodeCreator failure = throwableIsNull.falseBranch();
                failure.invokeVirtualMethod(Descriptors.COMPLETABLE_FUTURE_COMPLETE_EXCEPTIONALLY, whenRet,
                        whenComplete.getMethodParam(1));
                whenComplete.returnValue(null);

            } else {
                // No params
                ResultHandle invokeRet;
                if (Modifier.isInterface(clazz.flags())) {
                    invokeRet = matchScope.invokeInterfaceMethod(MethodDescriptor.of(method), base);
                } else {
                    invokeRet = matchScope.invokeVirtualMethod(MethodDescriptor.of(method), base);
                }
                if (hasCompletionStage) {
                    ret = invokeRet;
                } else {
                    ret = matchScope.invokeStaticMethod(Descriptors.COMPLETED_FUTURE, invokeRet);
                }
            }
            matchScope.returnValue(ret);
        }
        resolve.returnValue(resolve.readStaticField(Descriptors.RESULT_NOT_FOUND));
    }

    private void implementAppliesTo(ClassCreator valueResolver, ClassInfo clazz) {
        MethodCreator appliesTo = valueResolver.getMethodCreator("appliesTo", boolean.class, EvalContext.class)
                .setModifiers(ACC_PUBLIC);

        ResultHandle evalContext = appliesTo.getMethodParam(0);
        ResultHandle base = appliesTo.invokeInterfaceMethod(Descriptors.GET_BASE, evalContext);
        BranchResult baseTest = appliesTo.ifNull(base);
        BytecodeCreator baseNotNullBranch = baseTest.falseBranch();

        // Test base object class
        ResultHandle baseClass = baseNotNullBranch.invokeVirtualMethod(Descriptors.GET_CLASS, base);
        ResultHandle testClass = baseNotNullBranch.loadClass(clazz.name().toString());
        ResultHandle test = baseNotNullBranch.invokeVirtualMethod(Descriptors.IS_ASSIGNABLE_FROM, testClass, baseClass);
        BytecodeCreator baseAssignableBranch = baseNotNullBranch.ifNonZero(test).trueBranch();
        baseAssignableBranch.returnValue(baseAssignableBranch.load(true));
        appliesTo.returnValue(appliesTo.load(false));
    }

    public static class Builder {

        private IndexView index;
        private ClassOutput classOutput;
        private Map<ClassInfo, AnnotationInstance> uncontrolled;

        public Builder setIndex(IndexView index) {
            this.index = index;
            return this;
        }

        public Builder setClassOutput(ClassOutput classOutput) {
            this.classOutput = classOutput;
            return this;
        }

        public Builder setUncontrolled(Map<ClassInfo, AnnotationInstance> uncontrolled) {
            this.uncontrolled = uncontrolled;
            return this;
        }

        public ValueResolverGenerator build() {
            return new ValueResolverGenerator(index, classOutput, uncontrolled);
        }

    }

    private boolean skipMemberType(Type type) {
        switch (type.kind()) {
            case VOID:
            case PRIMITIVE:
            case ARRAY:
            case TYPE_VARIABLE:
            case UNRESOLVED_TYPE_VARIABLE:
            case WILDCARD_TYPE:
                return true;
            default:
                return false;
        }
    }

    private Predicate<AnnotationTarget> initFilters(AnnotationInstance templateData) {
        Predicate<AnnotationTarget> filter = t -> {
            // Always ignore constructors, static and non-public members, synthetic and void methods
            switch (t.kind()) {
                case METHOD:
                    MethodInfo method = t.asMethod();
                    if (method.name().equals("<init>")
                            || method.name().equals("<clinit>") || isSynthetic(method.flags())
                            || !Modifier.isPublic(method.flags())
                            || method.returnType().kind() == org.jboss.jandex.Type.Kind.VOID) {
                        return false;
                    } else {
                        return true;
                    }
                case FIELD:
                    return Modifier.isPublic(t.asField().flags()) && !Modifier.isStatic(t.asField().flags());
                default:
                    throw new IllegalArgumentException();
            }
        };
        // @TemplateData
        if (templateData != null) {
            AnnotationValue ignoreValue = templateData.value("ignore");
            if (ignoreValue != null) {
                List<Pattern> ignore = Arrays.asList(ignoreValue.asStringArray()).stream().map(Pattern::compile)
                        .collect(Collectors.toList());
                filter = filter.and(t -> {
                    if (t.kind() == Kind.FIELD) {
                        return !ignore.stream().anyMatch(p -> p.matcher(t.asField().name()).matches());
                    } else {
                        return !ignore.stream().anyMatch(p -> p.matcher(t.asMethod().name()).matches());
                    }
                });
            }
            AnnotationValue propertiesValue = templateData.value("properties");
            if (propertiesValue != null && propertiesValue.asBoolean()) {
                filter = filter.and(t -> {
                    if (t.kind() == Kind.METHOD) {
                        return t.asMethod().parameters().size() == 0;
                    }
                    return true;
                });
            }
        }
        return filter;
    }

    static boolean isSynthetic(int mod) {
        return (mod & 0x00001000) != 0;
    }

    static boolean isGetterName(String name) {
        return name.startsWith(GET_PREFIX) || name.startsWith(IS_PREFIX);
    }

    static String getPropertyName(String methodName) {
        if (methodName.startsWith(GET_PREFIX)) {
            return decapitalize(methodName.substring(GET_PREFIX.length(), methodName.length()));
        } else if (methodName.startsWith(IS_PREFIX)) {
            return decapitalize(methodName.substring(IS_PREFIX.length(), methodName.length()));
        }
        return methodName;
    }

    static String decapitalize(String name) {
        if (name == null || name.length() == 0) {
            return name;
        }
        if (name.length() > 1 && Character.isUpperCase(name.charAt(1)) &&
                Character.isUpperCase(name.charAt(0))) {
            return name;
        }
        char chars[] = name.toCharArray();
        chars[0] = Character.toLowerCase(chars[0]);
        return new String(chars);
    }

    /**
     * 
     * @param clazz
     * @return the simple name for the given top-level or nested class
     */
    static String simpleName(ClassInfo clazz) {
        switch (clazz.nestingType()) {
            case TOP_LEVEL:
                return simpleName(clazz.name());
            case INNER:
                // Nested class
                // com.foo.Foo$Bar -> Bar
                return clazz.simpleName();
            default:
                throw new IllegalStateException("Unsupported nesting type: " + clazz);
        }
    }

    /**
     * @param dotName
     * @see #simpleName(String)
     */
    static String simpleName(DotName dotName) {
        return simpleName(dotName.toString());
    }

    /**
     * Note that "$" is a valid character for class names so we cannot detect a nested class here. Therefore, this method would
     * return "Foo$Bar" for the
     * parameter "com.foo.Foo$Bar". Use {@link #simpleName(ClassInfo)} when you need to distinguish the nested classes.
     * 
     * @param name
     * @return the simple name
     */
    static String simpleName(String name) {
        return name.contains(".") ? name.substring(name.lastIndexOf(".") + 1, name.length()) : name;
    }

    static String packageName(DotName dotName) {
        String name = dotName.toString();
        int index = name.lastIndexOf('.');
        if (index == -1) {
            return "";
        }
        return name.substring(0, index);
    }

    static String generatedNameFromTarget(String targetPackage, String baseName, String suffix) {
        if (targetPackage == null || targetPackage.isEmpty()) {
            return baseName + suffix;
        } else if (targetPackage.startsWith("java")) {
            return "com/github/mkouba/qute" + "/" + baseName + suffix;
        } else {
            return targetPackage.replace('.', '/') + "/" + baseName + suffix;
        }
    }

    static boolean hasCompletionStageInTypeClosure(ClassInfo classInfo,
            IndexView index) {

        if (classInfo == null) {
            // TODO cannot perform analysis
            return false;
        }
        if (classInfo.name().equals(COMPLETION_STAGE)) {
            return true;
        }
        // Interfaces
        for (Type interfaceType : classInfo.interfaceTypes()) {
            ClassInfo interfaceClassInfo = index.getClassByName(interfaceType.name());
            if (interfaceClassInfo != null && hasCompletionStageInTypeClosure(interfaceClassInfo, index)) {
                return true;
            }
        }
        // Superclass
        if (classInfo.superClassType() != null) {
            ClassInfo superClassInfo = index.getClassByName(classInfo.superName());
            if (superClassInfo != null && hasCompletionStageInTypeClosure(superClassInfo, index)) {
                return true;
            }
        }
        return false;
    }

}
