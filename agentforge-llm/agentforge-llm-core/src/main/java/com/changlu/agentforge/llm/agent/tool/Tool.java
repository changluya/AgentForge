package com.changlu.agentforge.llm.agent.tool;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a method as a tool (function) that an LLM may call.
 *
 * <p>AgentForge mirrors LangChain4j's {@code dev.langchain4j.agent.tool.Tool}.</p>
 *
 * <p>When a method is annotated with {@code @Tool}, a {@link ToolSpecification} is derived from the
 * method signature (method name, parameter names/types and {@link P} annotations) and registered with
 * the model. If the model decides to call the tool, the arguments JSON is bound to method parameters
 * and the method is invoked reflectively.</p>
 *
 * <p>Return value handling:</p>
 * <ul>
 *   <li>{@code String} — sent to the model as-is;</li>
 *   <li>{@code void} — the literal {@code "Success"} is sent;</li>
 *   <li>any other value — serialized into a JSON string and sent to the model.</li>
 * </ul>
 *
 * @author changlu
 * @since 2026-09-13
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface Tool {

    /**
     * Name of the tool. If blank, the method name is used.
     *
     * @return tool name
     */
    String name() default "";

    /**
     * Description of the tool. Multiple strings are joined with a newline. If blank, the tool
     * description stays empty unless derived elsewhere.
     *
     * @return tool description
     */
    String[] value() default "";

    /**
     * Return behavior of the tool. See {@link ReturnBehavior} for details.
     *
     * @return return behavior
     */
    ReturnBehavior returnBehavior() default ReturnBehavior.TO_LLM;
}