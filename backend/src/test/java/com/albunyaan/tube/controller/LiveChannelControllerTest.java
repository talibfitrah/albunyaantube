package com.albunyaan.tube.controller;

import com.albunyaan.tube.service.LiveChannelService;
import com.google.firebase.auth.FirebaseAuth;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Map;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** A-M5: 404 is the apps' definitive "not live" stop, so it is never sent before a view of the loaded states is published. */
@WebMvcTest(LiveChannelController.class)
@AutoConfigureMockMvc(addFilters = false)
class LiveChannelControllerTest {

    @Autowired MockMvc mockMvc;
    @MockBean LiveChannelService live;
    @MockBean FirebaseAuth firebaseAuth;
    @MockBean com.albunyaan.tube.repository.UserRepository userRepository;

    @Test
    void whileStatesAreLoadingTheAnswerIs503WithRetryAfterNever404() throws Exception {
        when(live.published()).thenReturn(false);
        when(live.entries()).thenReturn(Map.of());
        mockMvc.perform(get("/api/v1/live/channels/UCaaaaaaaaaaaaaaaaaaaaaa"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string("Retry-After", "60"));
    }

    @Test
    void onceAViewIsPublishedAnUnknownChannelIsStill404() throws Exception {
        when(live.published()).thenReturn(true);
        when(live.entries()).thenReturn(Map.of());
        mockMvc.perform(get("/api/v1/live/channels/UCaaaaaaaaaaaaaaaaaaaaaa")).andExpect(status().isNotFound());
    }
}
