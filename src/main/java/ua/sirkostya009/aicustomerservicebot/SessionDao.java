package ua.sirkostya009.aicustomerservicebot;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.theokanning.openai.completion.chat.ChatMessage;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import redis.clients.jedis.Jedis;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * TODO Refactor this bullshit to properly use lists instead of plain json strings
 */
@Slf4j
@Service
public class SessionDao {
    private final Jedis jedis = new Jedis();
    private final ObjectMapper mapper = new ObjectMapper();
    private final TypeReference<List<ChatMessage>> listReference = new TypeReference<>() {};

    @SneakyThrows
    public void add(long chatId, ChatMessage message) {
        var key = Long.toString(chatId);

        if (jedis.exists(key)) {
            var list = mapper.readValue(jedis.get(key), listReference);
            list.add(message);
            jedis.expireAt(key, Instant.now().plus(1, ChronoUnit.DAYS).toEpochMilli());
            jedis.set(key, mapper.writeValueAsString(list));
        } else {
            jedis.set(key, mapper.writeValueAsString(List.of(message)));
        }
    }

    @SneakyThrows
    public List<ChatMessage> getAll(long chatId) {
        return mapper.readValue(jedis.get(Long.toString(chatId)), listReference);
    }

    public boolean isEmpty(long chatId) {
        var key = Long.toString(chatId);
        return !jedis.exists(key) || isEmpty(jedis.get(key));
    }

    private boolean isEmpty(String jsonArray) {
        return jsonArray.isEmpty() || "[]".equals(jsonArray);
    }
}
