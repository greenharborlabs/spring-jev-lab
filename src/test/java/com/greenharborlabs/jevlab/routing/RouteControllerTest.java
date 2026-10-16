package com.greenharborlabs.jevlab.routing;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/** Full application without an API key: the demo must start and fail closed. */
@SpringBootTest(properties = "jev.api-key=")
@AutoConfigureMockMvc
class RouteControllerTest {

  @Autowired MockMvc mvc;

  @Test
  void routesToReviewWhenJevIsNotConfigured() throws Exception {
    mvc.perform(
            post("/route")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {"method":"GET","path":"/api/articles/1","agentId":"agent-7",
                     "purpose":"summarize an article"}
                    """))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.decision").value("REVIEW"))
        .andExpect(jsonPath("$.reasons[0]").value("jev_not_configured"))
        .andExpect(jsonPath("$.intent").doesNotExist());
  }

  @Test
  void rejectsRequestWithoutPath() throws Exception {
    mvc.perform(
            post("/route").contentType(MediaType.APPLICATION_JSON).content("{\"method\":\"GET\"}"))
        .andExpect(status().isBadRequest());
  }
}
