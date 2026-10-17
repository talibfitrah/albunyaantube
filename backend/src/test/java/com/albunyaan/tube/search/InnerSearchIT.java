package com.albunyaan.tube.search;

import com.albunyaan.tube.integration.BaseIntegrationTest;
import com.albunyaan.tube.model.User;
import com.google.cloud.Timestamp;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseToken;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.is;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Spec §14.2. The app context runs with search.inner.dir set and a mocked fetcher, so there is no YouTube traffic.
 * An ADMIN turns on a first pass over three sources. /api/v1/search then serves their videos as plain VIDEO items with
 * sourceType and sourceId. Moderators read the admin surface, and only admins write. With the property unset (every
 * other IT), behaviour is exactly today's.
 */
class InnerSearchIT extends BaseIntegrationTest {

    private static final Path DIR = Path.of(System.getProperty("java.io.tmpdir"), "inner-search-it-" + ProcessHandle.current().pid());
    private static final String C1 = "UCinnerit00000000000001a", C2 = "UCinnerit00000000000002b", P1 = "PLinnerit000000000000001";

    @DynamicPropertySource
    static void innerDir(DynamicPropertyRegistry registry) {
        registry.add("search.inner.dir", DIR::toString);
    }

    @AfterAll
    static void cleanUp() throws IOException {
        if (!Files.exists(DIR)) return;
        try (Stream<Path> files = Files.walk(DIR)) {
            for (Path p : files.sorted(Comparator.reverseOrder()).toList()) Files.delete(p);
        }
    }

    @MockBean UploadsFetcher fetcher;
    @MockBean FirebaseAuth firebaseAuth;
    @Autowired SearchIndex searchIndex;
    @Autowired InnerIndex innerIndex;

    private void seed(String collection, String id, Map<String, Object> fields) throws Exception {
        firestore.collection(collection).document(id).set(fields).get(10, TimeUnit.SECONDS);
    }

    private static UploadsFetcher.Batch one(String id, String title, String uploader, String uploaderId) {
        return new UploadsFetcher.Batch(1, List.of(new InnerCorpus.Item(id, title, 2712, null, uploader, uploaderId)), 1, null);
    }

    @Test
    void anAdminFirstPassOverThreeSourcesIsServedWithItsSourceAndTodaysShape() throws Exception {
        innerIndex.refreshEveryMs = 0;
        seed("channels", "c1", Map.of("youtubeId", C1, "name", "Inner IT Lezingen", "status", "APPROVED"));
        seed("channels", "c2", Map.of("youtubeId", C2, "name", "Inner IT Kinderen", "status", "APPROVED"));
        seed("playlists", "p1", Map.of("youtubeId", P1, "title", "Inner IT Playlist", "status", "APPROVED"));
        searchIndex.reconcile();
        searchIndex.awaitWrites();
        when(fetcher.first(eq("channel:" + C1), anyString())).thenReturn(one("ilgiwqpcOC8", "Zubdahnoor: de rechtvaardige kalief", null, null));
        when(fetcher.first(eq("channel:" + C2), anyString())).thenReturn(one("kRNck4MdV2g", "Zubdahnoor voor kinderen", null, null));
        when(fetcher.first(eq("playlist:" + P1), anyString()))
                .thenReturn(one("cBjnzYHSV4M", "Zubdahnoor opvoeding", "Dawah Nederland", "UCfassTU6mGWIj-yb26_ADjQ"));
        stubToken(seedUser("admin@inner.it", "admin"), "admin");

        mvc.perform(put("/api/admin/inner-search").header("Authorization", "Bearer t")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"mode\":\"FIRST_PASS\",\"serveInner\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mode").value("FIRST_PASS"))
                .andExpect(jsonPath("$.serveInner").value(true));
        await().atMost(Duration.ofSeconds(20)).until(() -> innerIndex.docs() == 3);

        mvc.perform(get("/api/v1/search").param("q", "zubdahnoor").param("limit", "50"))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Search-Match", "exact"))
                .andExpect(jsonPath("$.length()").value(3))
                .andExpect(jsonPath("$[*].type", everyItem(is("VIDEO"))))
                .andExpect(jsonPath("$[?(@.id=='cBjnzYHSV4M')].sourceType").value(contains("PLAYLIST")))
                .andExpect(jsonPath("$[?(@.id=='cBjnzYHSV4M')].sourceId").value(contains(P1)))
                .andExpect(jsonPath("$[?(@.id=='cBjnzYHSV4M')].channelTitle").value(contains("Dawah Nederland")))
                .andExpect(jsonPath("$[?(@.id=='ilgiwqpcOC8')].channelTitle").value(contains("Inner IT Lezingen")))
                .andExpect(jsonPath("$[?(@.id=='ilgiwqpcOC8')].thumbnailUrl")
                        .value(contains("https://i.ytimg.com/vi/ilgiwqpcOC8/hqdefault.jpg")));
        mvc.perform(get("/api/v1/search").param("q", "inner it lezingen"))
                .andExpect(jsonPath("$[0].id").value(C1))
                .andExpect(jsonPath("$[0].sourceType").doesNotExist());
        mvc.perform(get("/api/v1/search").param("q", "kRNck4MdV2g"))          // R27: a pasted id of an inner video
                .andExpect(jsonPath("$[0].id").value("kRNck4MdV2g"))
                .andExpect(jsonPath("$[0].sourceId").value(C2));
        mvc.perform(get("/api/v1/titles").param("ids", "cBjnzYHSV4M,lesson99999")) // Rev 3, Task 24b: public, no token
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cBjnzYHSV4M").value("Zubdahnoor opvoeding"))
                .andExpect(jsonPath("$.lesson99999").doesNotExist());
    }

    @Test
    void moderatorsReadTheAdminSurfaceAndOnlyAdminsWriteIt() throws Exception {
        stubToken(seedUser("mod@inner.it", "moderator"), "moderator");
        mvc.perform(get("/api/admin/inner-search").header("Authorization", "Bearer t"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(true));
        mvc.perform(put("/api/admin/inner-search").header("Authorization", "Bearer t")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"mode\":\"OFF\"}"))
                .andExpect(status().isForbidden());
        stubToken(seedUser("admin2@inner.it", "admin"), "admin");
        mvc.perform(put("/api/admin/inner-search").header("Authorization", "Bearer t")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"capPerSource\":0}"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/admin/inner-search")).andExpect(status().is4xxClientError());
    }

    /** As MigrationControllerIntegrationTest: an active user document. */
    private String seedUser(String email, String role) throws Exception {
        String uid = "test-" + Math.abs(email.hashCode()) + "-" + System.nanoTime();
        User u = new User();
        u.setUid(uid);
        u.setEmail(email);
        u.setRole(role);
        u.setStatus("active");
        u.setCreatedAt(Timestamp.now());
        u.setUpdatedAt(u.getCreatedAt());
        userRepository.save(u);
        return uid;
    }

    /** As MigrationControllerIntegrationTest: verifyIdToken answers this uid and role claim. */
    private void stubToken(String uid, String role) throws Exception {
        FirebaseToken token = Mockito.mock(FirebaseToken.class);
        when(token.getUid()).thenReturn(uid);
        when(token.getEmail()).thenReturn(uid + "@test");
        Map<String, Object> claims = new HashMap<>();
        claims.put("role", role);
        when(token.getClaims()).thenReturn(claims);
        when(firebaseAuth.verifyIdToken(anyString())).thenReturn(token);
        when(firebaseAuth.verifyIdToken(anyString(), anyBoolean())).thenReturn(token);
    }
}
