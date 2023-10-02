package ua.sirkostya009.aicustomerservicebot;

import com.theokanning.openai.completion.chat.*;
import com.theokanning.openai.service.OpenAiService;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.telegram.abilitybots.api.sender.DefaultSender;
import org.telegram.abilitybots.api.sender.SilentSender;
import org.telegram.telegrambots.bots.TelegramLongPollingBot;
import org.telegram.telegrambots.meta.TelegramBotsApi;
import org.telegram.telegrambots.meta.api.methods.send.SendChatAction;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.updatesreceivers.DefaultBotSession;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

@Slf4j
@Service
public class Bot extends TelegramLongPollingBot {
    @Getter
    private final String botUsername;
    private final OpenAiService service;
    private final SessionDao dao;

    private final ChatMessage prompt;
    private final List<ChatFunction> functions;
    private final Functions functionsObject;

    public Bot(@Value("${bot.token}") String botToken,
               @Value("${bot.username}") String botUsername,
               @Value("${openai.key}") String openaiKey,
               @Value("${openai.prompt}") Path prompt,
               List<ChatFunction> functions,
               Functions functionsObject,
               SessionDao dao) throws TelegramApiException, IOException {
        super(botToken);
        this.botUsername = botUsername;
        this.service = new OpenAiService(openaiKey);

        this.prompt = newMessage(ChatMessageRole.SYSTEM, String.join("\n", Files.readAllLines(prompt)));
        this.functions = functions;
        this.functionsObject = functionsObject;
        this.dao = dao;

        new TelegramBotsApi(DefaultBotSession.class).registerBot(this);
        this.functionsObject.setSender(new SilentSender(new DefaultSender(this)));
        log.info("Bot has been initialized");
    }

    @Override
    public void onUpdateReceived(Update update) {
        var message = update.getMessage();

        if (!update.hasMessage() || !message.hasText()) {
            log.info("Received an invalid request {}", update);
            return;
        }

        var chatId = message.getChatId();
        var text = message.getText();

        if (text.length() > 200) {
            functionsObject.print(chatId, "Character limit of 200 was exceeded");
            return;
        }

        log.info("Received a message '{}' from {}", text, message.getFrom());

        var result = sendMessage(chatId, text);

        if (result.getFunctionCall() == null) {
            log.warn("OpenAI returned a non function call response");
            functionsObject.print(chatId, String.format("Non-function call returned: %s", result.getContent()));
            dao.add(chatId, result);
            return;
        }

        var functionCall = result.getFunctionCall();

        functionsObject.invoke(functionCall.getName(), chatId, functionCall.getArguments());

        dao.add(chatId, result);
    }

    private ChatMessage sendMessage(long chatId, String text) {
        if (dao.isEmpty(chatId)) {
            dao.add(chatId, prompt);
        }

        dao.add(chatId, newMessage(ChatMessageRole.USER, text));

        sendApiMethodAsync(SendChatAction.builder().chatId(chatId).action("typing").build());

        var result = service.createChatCompletion(ChatCompletionRequest.builder()
                .messages(dao.getAll(chatId))
                .functions(functions)
                .model("gpt-3.5-turbo")
                .build());

        return result.getChoices().get(0).getMessage();
    }

    private ChatMessage newMessage(ChatMessageRole role, String content) {
        return new ChatMessage(role.value(), content);
    }
}
