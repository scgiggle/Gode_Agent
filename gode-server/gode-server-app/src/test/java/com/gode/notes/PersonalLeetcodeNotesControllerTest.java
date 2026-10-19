package com.gode.notes;

import com.gode.domain.agent.service.armory.matter.mcp.PersonalLeetcodeNotesService;
import com.gode.trigger.http.PersonalLeetcodeNotesController;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockServletContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class PersonalLeetcodeNotesControllerTest {
    @Test void configurationRequiresLoopbackAndExplicitExtensionHeader() {
        var service = mock(PersonalLeetcodeNotesService.class);
        var controller = new PersonalLeetcodeNotesController(service);
        var request = new MockHttpServletRequest();
        request.setRemoteAddr("203.0.113.10");
        request.addHeader("X-Gode-Personal-Config", "1");
        assertEquals("0002", controller.status("demo-agent", request).getCode());
        request.setRemoteAddr("127.0.0.1");
        request.removeHeader("X-Gode-Personal-Config");
        assertEquals("0002", controller.status("demo-agent", request).getCode());
        verifyNoInteractions(service);
        request.addHeader("X-Gode-Personal-Config", "1");
        when(service.status("demo-agent")).thenReturn(new PersonalLeetcodeNotesService.Status(true, false, false, false));
        assertEquals("0000", controller.status("demo-agent", request).getCode());
    }

    @Test void corsRejectsWebsitesAndAllowsExtensionPreflight() throws Exception {
        try (var context = new AnnotationConfigWebApplicationContext()) {
            context.setServletContext(new MockServletContext());
            context.register(WebConfiguration.class);
            context.refresh();
            var mvc = MockMvcBuilders.webAppContextSetup(context).build();
            mvc.perform(options("/api/v1/personal/leetcode-notes")
                .header("Origin", "https://evil.example")
                .header("Access-Control-Request-Method", "POST"))
                .andExpect(status().isForbidden());
            mvc.perform(options("/api/v1/personal/leetcode-notes")
                .header("Origin", "chrome-extension://abcdefghijklmnopabcdefghijklmnop")
                .header("Access-Control-Request-Method", "POST")
                .header("Access-Control-Request-Headers", "content-type,x-gode-personal-config"))
                .andExpect(status().isOk());
        }
    }

    @Configuration @EnableWebMvc
    static class WebConfiguration {
        @Bean PersonalLeetcodeNotesService service() { return mock(PersonalLeetcodeNotesService.class); }
        @Bean PersonalLeetcodeNotesController controller(PersonalLeetcodeNotesService service) {
            return new PersonalLeetcodeNotesController(service);
        }
    }

    @Test void statusAndErrorsNeverReturnCredentialsOrUpstreamExceptions() {
        var service = mock(PersonalLeetcodeNotesService.class);
        var controller = new PersonalLeetcodeNotesController(service);
        var request = new MockHttpServletRequest();
        request.setRemoteAddr("127.0.0.1");
        request.addHeader("X-Gode-Personal-Config", "1");
        var body = new PersonalLeetcodeNotesController.SaveRequest();
        body.setAgentId("demo-agent");
        body.setCookie("sensitive-cookie");
        assertFalse(body.toString().contains("sensitive-cookie"));
        when(service.save("demo-agent", "sensitive-cookie")).thenThrow(new RuntimeException("sensitive-cookie"));
        var response = controller.save(body, request);
        assertEquals("0001", response.getCode());
        assertFalse(response.toString().contains("sensitive-cookie"));
    }
}
