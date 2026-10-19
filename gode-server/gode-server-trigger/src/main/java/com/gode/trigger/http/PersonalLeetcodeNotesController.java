package com.gode.trigger.http;

import com.gode.api.response.Response;
import com.gode.domain.agent.service.armory.matter.mcp.PersonalLeetcodeNotesService;
import com.gode.types.exception.AppException;
import jakarta.servlet.http.HttpServletRequest;
import lombok.Getter;
import lombok.Setter;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;

/** 只允许本机/SSH 隧道使用的单人测试配置接口，不是公开多用户 API。 */
@RestController
@RequestMapping("/api/v1/personal/leetcode-notes")
@CrossOrigin(originPatterns = "chrome-extension://*")
public class PersonalLeetcodeNotesController {
    private final PersonalLeetcodeNotesService service;
    public PersonalLeetcodeNotesController(PersonalLeetcodeNotesService service) { this.service = service; }

    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public Response<PersonalLeetcodeNotesService.Status> status(@RequestParam String agentId, HttpServletRequest request) {
        return execute(request, () -> service.status(agentId));
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public Response<PersonalLeetcodeNotesService.Status> save(@RequestBody SaveRequest body, HttpServletRequest request) {
        return execute(request, () -> service.save(body.getAgentId(), body.getCookie()));
    }

    @PostMapping(value = "/clear", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public Response<PersonalLeetcodeNotesService.Status> clear(@RequestBody ClearRequest body, HttpServletRequest request) {
        return execute(request, () -> service.clear(body.getAgentId()));
    }

    private Response<PersonalLeetcodeNotesService.Status> execute(HttpServletRequest request,
            java.util.function.Supplier<PersonalLeetcodeNotesService.Status> operation) {
        try {
            String remote = request.getRemoteAddr();
            if (!java.util.Set.of("127.0.0.1", "::1", "0:0:0:0:0:0:0:1").contains(remote)
                    || !"1".equals(request.getHeader("X-Gode-Personal-Config"))) {
                throw new AppException("0002", "个人配置仅允许通过本机或 SSH 隧道访问");
            }
            return Response.<PersonalLeetcodeNotesService.Status>builder().code("0000").info("成功").data(operation.get()).build();
        } catch (AppException exception) {
            return Response.<PersonalLeetcodeNotesService.Status>builder().code(exception.getCode()).info(exception.getInfo()).build();
        } catch (Exception exception) {
            return Response.<PersonalLeetcodeNotesService.Status>builder().code("0001").info("个人笔记配置操作失败，请稍后重试").build();
        }
    }

    // 不使用 @Data/@ToString，避免日志误打印整个凭据请求。
    @Getter @Setter
    public static class SaveRequest { private String agentId; private String cookie; }
    @Getter @Setter
    public static class ClearRequest { private String agentId; }
}
