package com.albunyaan.tube.controller;

import com.google.firebase.auth.FirebaseAuth;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Ruling R18: the client stream push is retired. Installed apps still get their 202 whatever they send, and the
 * slice has no service bean: a dependency added back to IndexController fails this context at once.
 */
@WebMvcTest(IndexController.class)
@AutoConfigureMockMvc(addFilters = false)
class IndexControllerTest {

    @Autowired MockMvc mockMvc;
    @MockBean FirebaseAuth firebaseAuth;
    @MockBean com.albunyaan.tube.repository.UserRepository userRepository;

    @Test
    void aValidPushAnswers202() throws Exception {
        String item = "{\"id\":\"lesson00001\",\"name\":\"Seerah Lesson 1\",\"thumbnailUrl\":\"https://i.ytimg.com/vi/lesson00001/hqdefault.jpg\",\"streamType\":\"VIDEO\"}";
        for (String source : new String[]{
                "\"sourceType\":\"CHANNEL\",\"sourceId\":\"UCaaaaaaaaaaaaaaaaaaaaaa\"",
                "\"sourceType\":\"PLAYLIST\",\"sourceId\":\"PLaaaaaaaaaaaaaaaaaaaaaa\""}) {
            mockMvc.perform(post("/api/v1/index/streams")
                            .contentType(MediaType.APPLICATION_JSON)
                            .header("X-Device-Id", "test-device")
                            .content("{" + source + ",\"items\":[" + item + "]}"))
                    .andExpect(status().isAccepted());
        }
    }

    @Test
    void aMalformedOrEmptyPushAlsoAnswers202() throws Exception {
        mockMvc.perform(post("/api/v1/index/streams").contentType(MediaType.APPLICATION_JSON).content("not json"))
                .andExpect(status().isAccepted());
        mockMvc.perform(post("/api/v1/index/streams")).andExpect(status().isAccepted());
    }
}
