package com.mediassistrover.backend.controller.assistant;

import java.net.URI;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/** Keep existing bookmarks usable after moving AI administration into the main app. */
@Controller
public class AiAdminUiController {
    private final URI destination;

    public AiAdminUiController(@Value("${app.assistant.admin-ui-url:http://localhost:5173/admin/voice-scripts}") String url) {
        destination = URI.create(url);
        if (!java.util.Set.of("http", "https").contains(destination.getScheme()) || destination.getHost() == null)
            throw new IllegalArgumentException("AI admin UI URL must be an absolute HTTP(S) address");
    }

    @GetMapping("/knowledge-admin.html")
    public ResponseEntity<Void> redirect() {
        return ResponseEntity.status(HttpStatus.SEE_OTHER).location(destination).cacheControl(CacheControl.noStore()).build();
    }
}
