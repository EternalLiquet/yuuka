package com.yuuka.backend;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.yuuka.backend.support.AbstractPostgresTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@AutoConfigureMockMvc
@SpringBootTest(properties = "yuuka.auth.registration-enabled=false")
class AuthRegistrationDisabledTests extends AbstractPostgresTest {
  @Autowired private MockMvc mockMvc;

  @Test
  void registrationIsDisabledByDefault() throws Exception {
    mockMvc
        .perform(
            post("/api/v1/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {
                      "email": "owner@yuuka.local",
                      "password": "Password12345"
                    }
                    """))
        .andExpect(status().isNotFound());
  }
}
