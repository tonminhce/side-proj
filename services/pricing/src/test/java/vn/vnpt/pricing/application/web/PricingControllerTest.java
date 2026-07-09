package vn.vnpt.pricing.application.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import vn.vnpt.pricing.domain.Pricebook;

class PricingControllerTest {

  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    Pricebook pricebook = new Pricebook(new ObjectMapper());
    pricebook.load();
    PricingController controller = new PricingController(pricebook);
    mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
  }

  @Test
  void get_returns200_withJsonShape_forKnownVariant() throws Exception {
    mockMvc.perform(get("/api/pricing/variant-1"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.variantId").value("variant-1"))
        .andExpect(jsonPath("$.listPriceCents").value(1_990_000))
        .andExpect(jsonPath("$.salePriceCents").value(1_790_000))
        .andExpect(jsonPath("$.currency").value("VND"))
        .andExpect(jsonPath("$.effectiveAt").exists());
  }

  @Test
  void get_returns404_forUnknownVariant() throws Exception {
    mockMvc.perform(get("/api/pricing/variant-does-not-exist"))
        .andExpect(status().isNotFound());
  }
}