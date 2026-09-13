package com.changlu.agentforge.llm.agent.tool;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Annotation for a parameter of a {@link Tool}-annotated method.
 *
 * <p>Mirrors LangChain4j's {@code dev.langchain4j.agent.tool.P}. It lets you override the
 * parameter name the LLM will see (useful when javac is not run with {@code -parameters},
 * otherwise reflection returns {@code arg0}, {@code arg1}, ...) and provide a description.</p>
 *
 * <pre>{@code
 * @Tool
 * void getWeather(@P(value = "The city name") String city) { ... }
 *
 * @Tool
 * void getWeather(@P(name = "city", description = "The city name") String city) { ... }
 * }</pre>
 *
 * @author changlu
 * @since 2026-09-13
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.PARAMETER)
public @interface P {

    /**
     * Name of the parameter as seen by the LLM. If blank, the actual method parameter name is used.
     *
     * @return parameter name override
     */
    String name() default "";

    /**
     * Description of the parameter. Alias of {@link #description()}.
     *
     * @return parameter description
     */
    String value() default "";

    /**
     * Description of the parameter. Alias of {@link #value()}.
     *
     * @return parameter description
     */
    String description() default "";
}