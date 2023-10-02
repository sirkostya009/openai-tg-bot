package ua.sirkostya009.aicustomerservicebot;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import com.fasterxml.jackson.databind.JsonNode;
import com.theokanning.openai.completion.chat.ChatFunction;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.stereotype.Service;
import org.telegram.abilitybots.api.sender.SilentSender;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * TODO Refactor this thing to utilize Spring IoC, AOP and shit
 */
@Slf4j
@Service
public class Functions {
    private SilentSender sender;
    private final Map<String, Method> methods = Arrays.stream(getClass().getDeclaredMethods())
            .collect(Collectors.toMap(Method::getName, Function.identity()));

    @OpenAiFunction(
            description = "This function prints text to the user",
            parameters = PrintParameters.class
    )
    public void print(long chatId, String text) {
        log.info("Printing response to {}: {}", chatId, text);
        sender.send(text, chatId);
    }

    public void invoke(String methodName, long chatId, JsonNode values) {
        if (!methods.containsKey(methodName)) {
            log.warn("No method {} found", methodName);
            return;
        }

        var method = methods.get(methodName);

        try {
            var arguments = Stream.concat(Stream.of(chatId), parseParameters(method.getParameters(), values)).toArray();
            method.invoke(this, arguments);
        } catch (IllegalAccessException | InvocationTargetException e) {
            log.error("Failed to invoke {}", methodName, e);
        }
    }

    private Stream<Object> parseParameters(Parameter[] parameters, JsonNode values) {
        return Arrays.stream(parameters)
                .filter(parameter -> !"chatId".equalsIgnoreCase(parameter.getName()))
                .map(parameter -> {
                    var name = parameter.getName();
                    var type = parameter.getType();
                    var value = values.get(name);

                    if (value == null || value.hasNonNull(name)) {
                        log.warn("Function call response did not return required parameter {}", name);
                    } else if (String.class.equals(type)) {
                        return value.asText();
                    } else if (double.class.equals(type) || Double.class.equals(type)) {
                        return value.asDouble();
                    } else if (int.class.equals(type) || Integer.class.equals(type)) {
                        return value.asInt();
                    } else if (boolean.class.equals(type) || Boolean.class.equals(type)) {
                        return value.asBoolean();
                    } else if (long.class.equals(type) || Long.class.equals(type)) {
                        return value.asLong();
                    }

                    log.warn("Parameter {} not found", name);
                    return null;
                });
    }

    public void setSender(SilentSender sender) {
        if (this.sender == null)
            this.sender = sender;
    }

    record PrintParameters(
            @JsonPropertyDescription("Generated answer")
            @JsonProperty(required = true)
            String text
    ) {
    }

    @Bean
    public List<ChatFunction> chatFunctions() {
        return Arrays.stream(getClass().getMethods())
                .filter(method -> method.isAnnotationPresent(OpenAiFunction.class))
                .map(method -> {
                    var annotation = method.getAnnotation(OpenAiFunction.class);
                    var function = ChatFunction.builder()
                            .description(annotation.description())
                            .name(annotation.name().isEmpty() ? method.getName() : annotation.name())
                            .build();
                    function.setParametersClass(annotation.parameters());
                    return function;
                })
                .toList();
    }
}
