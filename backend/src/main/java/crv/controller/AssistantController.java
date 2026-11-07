package crv.controller;

import crv.dto.ChatReply;
import crv.dto.ChatRequest;
import crv.dto.SearchQuery;
import crv.dto.SearchResult;
import crv.service.AssistantService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import org.jspecify.annotations.Nullable;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(path = "/api", produces = MediaType.APPLICATION_JSON_VALUE)
public class AssistantController {

    private final AssistantService assistant;

    public AssistantController(AssistantService assistant) {
        this.assistant = assistant;
    }

    /** One shopper message. Starts a conversation when the request has no conversation id. */
    @PostMapping(path = "/chat", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ChatReply chat(@Valid @RequestBody ChatRequest request) {
        return assistant.chat(request);
    }

    /**
     * Runs a search directly, for example after the shopper removes a pill (AC4.7). With a conversation id, the
     * assistant's follow-ups then refer to these results.
     */
    @PostMapping(path = "/search", consumes = MediaType.APPLICATION_JSON_VALUE)
    public SearchResult search(
            @Valid @RequestBody SearchQuery query,
            @RequestParam(required = false) @Nullable @Size(max = 64) String conversationId) {
        return assistant.search(query, conversationId);
    }

    /** Products like this one (R12). */
    @GetMapping("/products/{productId}/similar")
    public SearchResult similar(@PathVariable String productId) {
        return assistant.similar(productId);
    }
}
