package crv.controller;

import crv.dto.HomeFeed;
import crv.dto.InteractionRequest;
import crv.service.RecommendationService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import org.jspecify.annotations.Nullable;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(path = "/api", produces = MediaType.APPLICATION_JSON_VALUE)
public class RecommendationController {

    private final RecommendationService recommendations;

    public RecommendationController(RecommendationService recommendations) {
        this.recommendations = recommendations;
    }

    /** The homepage: random products for a new shopper, then products like the ones they clicked. */
    @GetMapping("/home")
    public HomeFeed home(
            @RequestParam(required = false) @Nullable @Size(max = 64) String shopperId,
            @RequestParam(defaultValue = "12") @Min(1) @Max(48) int size) {
        return recommendations.home(shopperId, size);
    }

    /** Records that the shopper clicked a product, on the homepage, in a search or in the chat. */
    @PostMapping(path = "/interactions", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Void> record(@Valid @RequestBody InteractionRequest request) {
        return recommendations.record(request)
                ? ResponseEntity.noContent().build()
                : ResponseEntity.notFound().build();
    }
}
