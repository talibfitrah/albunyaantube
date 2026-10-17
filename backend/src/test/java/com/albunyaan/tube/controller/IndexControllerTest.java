package com.albunyaan.tube.controller;

import com.albunyaan.tube.service.StreamIndexService;
import com.google.firebase.auth.FirebaseAuth;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Ruling R18: the client stream push is retired; installed apps still get their 202 and nothing is indexed. */
@WebMvcTest(IndexController.class)
@AutoConfigureMockMvc(addFilters = false)
class IndexControllerTest {

    @Autowired MockMvc mockMvc;
    @MockBean StreamIndexService streamIndexService;
    @MockBean FirebaseAuth firebaseAuth;
    @MockBean com.albunyaan.tube.service.ChannelOrchestrator channelOrchestrator;
    @MockBean com.albunyaan.tube.repository.UserRepository userRepository;

    @Test
    void aValidPushAnswers202AndIndexesNothing() throws Exception {
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
        Thread.sleep(500); // the old controller indexed on a background thread; give a regression time to show
        verifyNoInteractions(streamIndexService);
    }
}
