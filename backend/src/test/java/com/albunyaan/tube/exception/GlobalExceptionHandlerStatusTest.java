package com.albunyaan.tube.exception;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Framework and ResponseStatusException 4xx must keep their status through the real advice; real 500s stay 500. */
class GlobalExceptionHandlerStatusTest {

    @RestController
    static class Probe {
        @GetMapping("/rse")
        void rse() { throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "bad thing"); }

        @PostMapping("/json")
        void json(@RequestBody Map<String, Object> body) { }

        @GetMapping("/param")
        void param(@RequestParam String needed) { }

        @GetMapping("/nores")
        void nores() throws Exception { throw new NoResourceFoundException(org.springframework.http.HttpMethod.GET, "/x"); }

        @GetMapping("/num/{n}")
        void num(@org.springframework.web.bind.annotation.PathVariable int n) { }

        @GetMapping("/rse503")
        void rse503() {
            org.springframework.http.HttpHeaders h = new org.springframework.http.HttpHeaders();
            h.set("Retry-After", "30");
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "down", null) {
                @Override public org.springframework.http.HttpHeaders getHeaders() { return h; }
            };
        }

        @GetMapping("/rse499")
        void rse499() { throw new ResponseStatusException(org.springframework.http.HttpStatusCode.valueOf(499), "gone away"); }

        @GetMapping("/boom")
        void boom() { throw new RuntimeException("secret internals"); }
    }

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(new Probe()).setControllerAdvice(new GlobalExceptionHandler())
                .setMessageConverters(new org.springframework.http.converter.json.MappingJackson2HttpMessageConverter(
                        org.springframework.http.converter.json.Jackson2ObjectMapperBuilder.json().build()))
                .build();
    }

    @Test
    void responseStatusExceptionKeepsItsStatusAndReason() throws Exception {
        mvc.perform(get("/rse")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400)).andExpect(jsonPath("$.error").value("Bad Request"))
                .andExpect(jsonPath("$.message").value("bad thing")).andExpect(jsonPath("$.path").value("/rse"));
    }

    @Test
    void malformedJsonIs400() throws Exception {
        mvc.perform(post("/json").contentType("application/json").content("{nope"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").value("Malformed request body"));
    }

    @Test
    void missingRequestParamIs400() throws Exception {
        mvc.perform(get("/param")).andExpect(status().isBadRequest());
    }

    @Test
    void wrongMethodIs405() throws Exception {
        mvc.perform(post("/rse")).andExpect(status().isMethodNotAllowed());
    }

    @Test
    void unknownPathIs404() throws Exception {
        mvc.perform(get("/nope")).andExpect(status().isNotFound());
        mvc.perform(get("/nores")).andExpect(status().isNotFound());
    }

    @Test
    void wrongMethodCarriesAllowHeader() throws Exception {
        mvc.perform(post("/rse")).andExpect(status().isMethodNotAllowed())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header().string("Allow", "GET"));
    }

    @Test
    void fiveXxResponseStatusExceptionStaysGeneric500WithoutItsHeaders() throws Exception {
        mvc.perform(get("/rse503")).andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.message").value("An unexpected error occurred"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header().doesNotExist("Retry-After"));
    }

    @Test
    void mvcExceptionMessageIsNotEchoed() throws Exception {
        mvc.perform(get("/param")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Bad Request"));
    }

    @Test
    void typeMismatchIs400WithoutEchoingTheValue() throws Exception {
        mvc.perform(get("/num/abc123")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Bad Request"));
    }

    @Test
    void nonStandard4xxDoesNotThrow() throws Exception {
        mvc.perform(get("/rse499")).andExpect(status().is(499))
                .andExpect(jsonPath("$.error").value("499")).andExpect(jsonPath("$.message").value("gone away"));
    }

    @Test
    void realFailureStaysGeneric500() throws Exception {
        mvc.perform(get("/boom")).andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.message").value("An unexpected error occurred"));
    }
}
