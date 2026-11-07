package crv.service;

import crv.dto.PhotoMatch;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.content.Media;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.stereotype.Component;
import org.springframework.util.MimeTypeUtils;
import tools.jackson.databind.json.JsonMapper;

/**
 * At runtime, when a shopper asks for a colour, checks whether each top product's photo shows it (AC1.6). The
 * answer only orders results: it is never shown or stated as a fact (AC1.7). Photos are checked in parallel, each
 * (photo, colour) pair once. A failure or timeout leaves the photo NOT_CHECKED, so it keeps its place.
 */
@Component
public class PhotoCheck {

    private static final Logger LOG = LoggerFactory.getLogger(PhotoCheck.class);
    private static final Duration BUDGET = Duration.ofSeconds(3);
    private static final Duration DOWNLOAD_TIMEOUT = Duration.ofSeconds(2);
    private static final int CACHE_LIMIT = 20_000;

    private final ChatModel chat;
    private final JsonMapper json;
    private final AssistantProperties settings;
    private final ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor();
    private final HttpClient http =
            HttpClient.newBuilder().connectTimeout(DOWNLOAD_TIMEOUT).build();
    private final Map<String, PhotoMatch> cache = new ConcurrentHashMap<>();

    public PhotoCheck(ChatModel chat, JsonMapper json, AssistantProperties settings) {
        this.chat = chat;
        this.json = json;
        this.settings = settings;
    }

    /**
     * Checks each photo for the colour.
     *
     * @param photos product id to photo URL
     * @return product id to what the photo shows, for every given product
     */
    public Map<String, PhotoMatch> check(String color, Map<String, String> photos) {
        String wanted = color.strip().toLowerCase(Locale.ROOT);
        Map<String, Future<PhotoMatch>> pending = new LinkedHashMap<>();
        for (Map.Entry<String, String> photo : photos.entrySet()) {
            String url = photo.getValue();
            pending.put(photo.getKey(), pool.submit(() -> lookup(wanted, url)));
        }
        long deadline = System.nanoTime() + BUDGET.toNanos();
        Map<String, PhotoMatch> seen = new LinkedHashMap<>();
        for (Map.Entry<String, Future<PhotoMatch>> entry : pending.entrySet()) {
            PhotoMatch match = await(entry.getValue(), deadline);
            if (match == PhotoMatch.NOT_CHECKED) {
                LOG.warn("Photo check gave no answer for product {} within {}", entry.getKey(), BUDGET);
            }
            seen.put(entry.getKey(), match);
        }
        return seen;
    }

    private PhotoMatch lookup(String wanted, String url) {
        String key = wanted + '\u0000' + url;
        PhotoMatch known = cache.get(key);
        if (known != null) {
            return known;
        }
        PhotoMatch answer = ask(wanted, url);
        if (answer != PhotoMatch.NOT_CHECKED) {
            if (cache.size() > CACHE_LIMIT) {
                cache.clear();
            }
            cache.put(key, answer);
        }
        return answer;
    }

    private PhotoMatch ask(String wanted, String url) {
        try {
            byte[] photo = download(url);
            UserMessage message = UserMessage.builder()
                    .text("""
                            Does the product in this photo show the colour "%s"?
                            Reply with JSON only: {"shows": "yes" | "partly" | "no"}""".formatted(wanted))
                    .media(List.of(new Media(MimeTypeUtils.IMAGE_JPEG, new ByteArrayResource(photo))))
                    .build();
            OpenAiChatOptions options = OpenAiChatOptions.builder()
                    .model(settings.model())
                    .reasoningEffort(settings.judgeReasoningEffort())
                    .temperature(0.0)
                    .maxTokens(60)
                    .build();
            String text = ConstraintJudge.text(chat.call(new Prompt(List.of(message), options)));
            String shows = json.readTree(ConstraintJudge.jsonPart(text))
                    .path("shows")
                    .asString("")
                    .strip()
                    .toLowerCase(Locale.ROOT);
            return switch (shows) {
                case "yes" -> PhotoMatch.YES;
                case "partly", "partial" -> PhotoMatch.PARTLY;
                case "no" -> PhotoMatch.NO;
                default -> PhotoMatch.NOT_CHECKED;
            };
        } catch (RuntimeException e) {
            LOG.warn("Photo check failed for {}: {}", url, e.toString());
            return PhotoMatch.NOT_CHECKED;
        }
    }

    /**
     * Downloads the photo here and sends its bytes, rather than letting the model provider fetch the URL: one slow
     * fetch on their side would otherwise hold up the whole search.
     */
    private byte[] download(String url) {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(DOWNLOAD_TIMEOUT)
                .header("User-Agent", "crv")
                .build();
        try {
            HttpResponse<byte[]> response = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() != 200) {
                throw new IllegalStateException("photo download returned " + response.statusCode());
            }
            return response.body();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("photo download interrupted", e);
        }
    }

    private static PhotoMatch await(Future<PhotoMatch> future, long deadline) {
        try {
            long left = Math.max(0, deadline - System.nanoTime());
            return future.get(left, TimeUnit.NANOSECONDS);
        } catch (TimeoutException | ExecutionException e) {
            future.cancel(true);
            return PhotoMatch.NOT_CHECKED;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return PhotoMatch.NOT_CHECKED;
        }
    }
}
