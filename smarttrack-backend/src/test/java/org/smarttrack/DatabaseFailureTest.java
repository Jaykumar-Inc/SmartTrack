package org.smarttrack;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import org.junit.jupiter.api.Test;
import org.smarttrack.controller.ApiController;
import org.smarttrack.controller.ApiErrors;
import org.smarttrack.service.TrackingService;
import org.smarttrack.service.UpdateHub;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class DatabaseFailureTest {
  @Test
  void unavailableDatabaseReturnsSafeRetryableError() throws Exception {
    TrackingService service = mock(TrackingService.class);
    when(service.devices("tester"))
        .thenThrow(new DataAccessResourceFailureException("Private SQL connection detail"));
    var mvc =
        MockMvcBuilders.standaloneSetup(new ApiController(service, mock(UpdateHub.class)))
            .setControllerAdvice(new ApiErrors())
            .build();
    mvc.perform(get("/api/devices").principal(() -> "tester"))
        .andExpect(status().isServiceUnavailable())
        .andExpect(jsonPath("$.error").value("Database unavailable; retry later"));
  }
}
