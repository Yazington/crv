package crv.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import crv.dto.PhotoMatch;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class PhotoCheckTest {

    private HttpServer server;
    private String base;

    @BeforeEach
    void startPhotoServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", exchange -> {
            boolean found = exchange.getRequestURI().getPath().equals("/orange.jpg");
            byte[] body = found ? new byte[] {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF} : new byte[0];
            exchange.sendResponseHeaders(found ? 200 : 404, found ? body.length : -1);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stopPhotoServer() {
        server.stop(0);
    }

    @Test
    void readsWhatThePhotoShowsAndChecksEachPhotoOnce() {
        ConstraintJudgeTest.FixedModel model = new ConstraintJudgeTest.FixedModel("{\"shows\": \"yes\"}");
        PhotoCheck check = new PhotoCheck(model, JsonMapper.builder().build(), ConstraintJudgeTest.SETTINGS);
        Map<String, String> photos = new LinkedHashMap<>();
        photos.put("P1", base + "/orange.jpg");
        photos.put("P2", base + "/missing.jpg");

        Map<String, PhotoMatch> first = check.check("Orange", photos);
        Map<String, PhotoMatch> second = check.check("orange", Map.of("P1", base + "/orange.jpg"));

        assertThat(first).containsExactly(Map.entry("P1", PhotoMatch.YES), Map.entry("P2", PhotoMatch.NOT_CHECKED));
        assertThat(second).containsEntry("P1", PhotoMatch.YES);
        assertThat(model.prompts).hasSize(1);
        assertThat(model.prompts.get(0).getUserMessage().getText()).contains("show the colour \"orange\"");
        assertThat(model.prompts.get(0).getUserMessage().getMedia()).hasSize(1);
    }

    @Test
    void anUnreadableAnswerLeavesThePhotoUnchecked() {
        ConstraintJudgeTest.FixedModel model = new ConstraintJudgeTest.FixedModel("I think so");
        PhotoCheck check = new PhotoCheck(model, JsonMapper.builder().build(), ConstraintJudgeTest.SETTINGS);

        assertThat(check.check("orange", Map.of("P1", base + "/orange.jpg")))
                .containsExactly(Map.entry("P1", PhotoMatch.NOT_CHECKED));
    }
}
