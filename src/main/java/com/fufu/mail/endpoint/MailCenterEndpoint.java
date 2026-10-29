package com.fufu.mail.endpoint;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fufu.mail.dto.MailDto;
import com.fufu.mail.service.MailConsoleService;
import com.fufu.mail.service.MailDispatchService;
import com.fufu.mail.service.MailLogService;
import com.fufu.mail.service.MailRecipientService;
import com.fufu.mail.service.MailRenderService;
import com.fufu.mail.service.MailSenderService;
import com.fufu.mail.service.MailTemplateCatalogService;
import com.fufu.mail.service.MailTriggerService;
import com.fufu.mail.service.RateLimiter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Callable;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.RouterFunctions;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import run.halo.app.core.extension.endpoint.CustomEndpoint;
import run.halo.app.extension.GroupVersion;

/**
 * Every HTTP surface of the plugin. Console routes are authorized by the plugin role
 * template; the single route the anonymous role may reach is {@code POST /trigger},
 * which is guarded by the key stored in the mail channel instead of a session.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MailCenterEndpoint implements CustomEndpoint {

    public static final String GROUP_VERSION = "mail.fufu520.cn/v1alpha1";
    public static final String API_KEY_HEADER = "X-Mail-Key";

    /** Lets external callers address a Halo notification template instead of a custom one. */
    public static final String BUILTIN_PREFIX = "builtin:";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final MailTemplateCatalogService catalog;
    private final MailDispatchService dispatch;
    private final MailRecipientService recipientService;
    private final MailConsoleService consoleService;
    private final MailLogService logService;
    private final MailTriggerService triggerService;
    private final MailSenderService senderService;
    private final RateLimiter rateLimiter;

    @Override
    public GroupVersion groupVersion() {
        return GroupVersion.parseAPIVersion(GROUP_VERSION);
    }

    @Override
    public RouterFunction<ServerResponse> endpoint() {
        return RouterFunctions.route()
            .GET("/overview", this::overview)
            .GET("/templates", this::listTemplates)
            .POST("/templates", this::createTemplate)
            .POST("/templates/validate", this::validateTemplate)
            .PUT("/templates/custom/{name}", this::updateCustomTemplate)
            .DELETE("/templates/custom/{name}", this::deleteCustomTemplate)
            .POST("/templates/custom/{name}/hidden", this::hideCustomTemplate)
            .PUT("/templates/builtin/{name}", this::updateBuiltinTemplate)
            .DELETE("/templates/builtin/{name}", this::deleteBuiltinTemplate)
            .POST("/templates/builtin/{name}/reset", this::resetBuiltinTemplate)
            .POST("/templates/builtin/{name}/hidden", this::hideBuiltinTemplate)
            .POST("/preview", this::preview)
            .GET("/recipients", this::recipients)
            .POST("/send", this::send)
            .GET("/logs", this::logs)
            .DELETE("/logs", this::clearLogs)
            .GET("/triggers", this::listTriggers)
            .POST("/triggers", this::createTrigger)
            .PUT("/triggers/{name}", this::updateTrigger)
            .DELETE("/triggers/{name}", this::deleteTrigger)
            .GET("/channel", this::channel)
            .PUT("/channel", this::saveChannel)
            .POST("/channel/test", this::testChannel)
            .POST("/trigger", this::openTrigger)
            .build();
    }

    private Mono<ServerResponse> overview(ServerRequest request) {
        return respond(consoleService::overview);
    }

    private Mono<ServerResponse> listTemplates(ServerRequest request) {
        return respond(() -> {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("groups", catalog.listBuiltinGroups());
            payload.put("custom", catalog.listCustom());
            return payload;
        });
    }

    private Mono<ServerResponse> createTemplate(ServerRequest request) {
        return body(request, MailDto.CustomTemplatePayload.class)
            .flatMap(payload -> respond(() -> catalog.toCustomView(catalog.createCustom(payload))));
    }

    private Mono<ServerResponse> updateCustomTemplate(ServerRequest request) {
        var name = request.pathVariable("name");
        return body(request, MailDto.CustomTemplatePayload.class)
            .flatMap(payload -> respond(() -> catalog.toCustomView(catalog.updateCustom(name, payload))));
    }

    private Mono<ServerResponse> deleteCustomTemplate(ServerRequest request) {
        var name = request.pathVariable("name");
        return respond(() -> {
            catalog.deleteCustom(name);
            return new MailDto.ApiResponse(true, "模板已删除");
        });
    }

    private Mono<ServerResponse> hideCustomTemplate(ServerRequest request) {
        var name = request.pathVariable("name");
        return body(request, MailDto.HiddenPayload.class)
            .flatMap(payload -> respond(() -> {
                catalog.setHidden("CUSTOM", name, Boolean.TRUE.equals(payload.hidden()));
                return new MailDto.ApiResponse(true,
                    Boolean.TRUE.equals(payload.hidden()) ? "模板已隐藏" : "模板已取消隐藏");
            }));
    }

    private Mono<ServerResponse> updateBuiltinTemplate(ServerRequest request) {
        var name = request.pathVariable("name");
        return body(request, MailDto.BuiltinTemplatePayload.class)
            .flatMap(payload -> respond(() -> catalog.updateBuiltin(name, payload)));
    }

    private Mono<ServerResponse> deleteBuiltinTemplate(ServerRequest request) {
        var name = request.pathVariable("name");
        return respond(() -> {
            catalog.deleteBuiltin(name);
            return new MailDto.ApiResponse(true,
                "模板已删除。站内模板在 Halo 下次启动时可能被重新创建，想让它长期不出现请改用隐藏");
        });
    }

    private Mono<ServerResponse> resetBuiltinTemplate(ServerRequest request) {
        var name = request.pathVariable("name");
        return respond(() -> catalog.resetBuiltin(name));
    }

    private Mono<ServerResponse> hideBuiltinTemplate(ServerRequest request) {
        var name = request.pathVariable("name");
        return body(request, MailDto.HiddenPayload.class)
            .flatMap(payload -> respond(() -> {
                catalog.setHidden("BUILTIN", name, Boolean.TRUE.equals(payload.hidden()));
                return new MailDto.ApiResponse(true,
                    Boolean.TRUE.equals(payload.hidden()) ? "模板已隐藏" : "模板已取消隐藏");
            }));
    }

    private Mono<ServerResponse> validateTemplate(ServerRequest request) {
        return body(request, MailDto.ValidatePayload.class)
            .flatMap(payload -> respond(() -> {
                dispatch.validate(payload.subject(), payload.content(), payload.rawContent(),
                    payload.variables());
                return new MailDto.ApiResponse(true, "模板语法检查通过");
            }));
    }

    private Mono<ServerResponse> preview(ServerRequest request) {
        return body(request, MailDto.PreviewPayload.class)
            .flatMap(payload -> respond(() -> dispatch.preview(payload.templateType(),
                payload.templateName(), payload.variables())));
    }

    private Mono<ServerResponse> recipients(ServerRequest request) {
        var keyword = request.queryParam("keyword").orElse("");
        var limit = intParam(request, "limit", 200);
        return respond(() -> recipientService.directory(keyword, limit));
    }

    private Mono<ServerResponse> send(ServerRequest request) {
        return body(request, MailDto.SendPayload.class)
            .flatMap(payload -> respond(() -> dispatch.send(payload, "MANUAL", null)));
    }

    private Mono<ServerResponse> logs(ServerRequest request) {
        var page = intParam(request, "page", 1);
        var size = intParam(request, "size", 20);
        var status = request.queryParam("status").orElse(null);
        var keyword = request.queryParam("keyword").orElse(null);
        return respond(() -> logService.page(page, size, status, keyword));
    }

    private Mono<ServerResponse> clearLogs(ServerRequest request) {
        return respond(() -> new MailDto.ApiResponse(true,
            "已清理 " + logService.clear() + " 条发送记录"));
    }

    private Mono<ServerResponse> listTriggers(ServerRequest request) {
        return respond(triggerService::listViews);
    }

    private Mono<ServerResponse> createTrigger(ServerRequest request) {
        return body(request, MailDto.TriggerPayload.class)
            .flatMap(payload -> respond(() -> triggerService.toView(triggerService.create(payload))));
    }

    private Mono<ServerResponse> updateTrigger(ServerRequest request) {
        var name = request.pathVariable("name");
        return body(request, MailDto.TriggerPayload.class)
            .flatMap(payload -> respond(() -> triggerService.toView(triggerService.update(name, payload))));
    }

    private Mono<ServerResponse> deleteTrigger(ServerRequest request) {
        var name = request.pathVariable("name");
        return respond(() -> {
            triggerService.delete(name);
            return new MailDto.ApiResponse(true, "触发规则已删除");
        });
    }

    private Mono<ServerResponse> channel(ServerRequest request) {
        return respond(consoleService::channelView);
    }

    private Mono<ServerResponse> saveChannel(ServerRequest request) {
        return body(request, MailDto.ChannelPayload.class)
            .flatMap(payload -> respond(() -> consoleService.saveChannel(payload)));
    }

    private Mono<ServerResponse> testChannel(ServerRequest request) {
        return body(request, MailDto.TestMailPayload.class)
            .flatMap(payload -> {
                var email = MailDispatchService.normalizeEmail(payload.to());
                if (email == null) {
                    return badRequest("请输入一个合法的收件邮箱地址");
                }
                return respond(() -> {
                    var outcome = senderService.sendTest(email);
                    return outcome.success()
                        ? new MailDto.ApiResponse(true, "测试邮件已发送，耗时 " + outcome.durationMs() + " ms")
                        : new MailDto.ApiResponse(false, "发送失败：" + outcome.error());
                });
            });
    }

    /**
     * The endpoint external code calls. Everything that touches storage runs on the
     * bounded elastic scheduler, because the extension client and the mail transport are
     * blocking.
     */
    private Mono<ServerResponse> openTrigger(ServerRequest request) {
        var provided = request.headers().firstHeader(API_KEY_HEADER);
        var clientKey = request.remoteAddress()
            .map(address -> address.getAddress().getHostAddress())
            .orElse("unknown");
        return body(request, MailDto.OpenTriggerPayload.class)
            .flatMap(payload -> Mono.fromCallable(() -> runOpenTrigger(clientKey, provided, payload))
                .subscribeOn(Schedulers.boundedElastic()))
            .flatMap(outcome -> ServerResponse.status(outcome.status()).bodyValue(outcome.payload()))
            .onErrorResume(this::failure);
    }

    TriggerOutcome runOpenTrigger(String clientKey, String provided, MailDto.OpenTriggerPayload payload) {
        var settings = consoleService.settings();
        if (!rateLimiter.allow(clientKey, settings.sendRateLimitPerMinuteOrDefault())) {
            return new TriggerOutcome(HttpStatus.TOO_MANY_REQUESTS,
                new MailDto.ApiResponse(false, "请求过于频繁，请稍后再试"));
        }
        var channel = consoleService.channelView();
        if (!channel.apiEnabled() || channel.apiKey() == null || channel.apiKey().isBlank()) {
            return new TriggerOutcome(HttpStatus.FORBIDDEN,
                new MailDto.ApiResponse(false, "触发接口尚未启用"));
        }
        if (provided == null || provided.isBlank() || !provided.equals(channel.apiKey())) {
            return new TriggerOutcome(HttpStatus.UNAUTHORIZED,
                new MailDto.ApiResponse(false, "触发密钥不正确"));
        }
        var targets = new ArrayList<MailDto.SendTarget>();
        if (payload.to() != null) {
            payload.to().forEach(email -> targets.add(new MailDto.SendTarget(email, null)));
        }
        var templateName = payload.template() == null ? "" : payload.template().trim();
        var templateType = "CUSTOM";
        if (templateName.startsWith(BUILTIN_PREFIX)) {
            templateType = "BUILTIN";
            templateName = templateName.substring(BUILTIN_PREFIX.length());
        }
        var sendPayload = new MailDto.SendPayload(templateType, templateName, targets,
            payload.variables(), payload.subject());
        return new TriggerOutcome(HttpStatus.OK, dispatch.send(sendPayload, "API", null));
    }

    /** Result of a call that decides its own status code. */
    record TriggerOutcome(HttpStatus status, Object payload) {
    }

    /** Reads a JSON body into a typed record through a plain map, which is portable. */
    private <T> Mono<T> body(ServerRequest request, Class<T> type) {
        return request.bodyToMono(new ParameterizedTypeReference<Map<String, Object>>() {
            })
            .defaultIfEmpty(Map.of())
            .map(map -> MAPPER.convertValue(map, type));
    }

    /** Runs a blocking service call off the event loop and maps failures to a status. */
    private <T> Mono<ServerResponse> respond(Callable<T> action) {
        return Mono.fromCallable(action)
            .subscribeOn(Schedulers.boundedElastic())
            .flatMap(value -> ServerResponse.ok().bodyValue(value))
            .onErrorResume(this::failure);
    }

    private Mono<ServerResponse> failure(Throwable error) {
        if (error instanceof IllegalArgumentException || error instanceof MailRenderService.RenderException) {
            return badRequest(error.getMessage());
        }
        log.error("Mail center request failed", error);
        var message = error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
        return ServerResponse.status(HttpStatus.INTERNAL_SERVER_ERROR)
            .bodyValue(new MailDto.ApiResponse(false, message));
    }

    private static Mono<ServerResponse> badRequest(String message) {
        return ServerResponse.badRequest().bodyValue(new MailDto.ApiResponse(false, message));
    }

    private static int intParam(ServerRequest request, String name, int fallback) {
        return request.queryParam(name)
            .flatMap(value -> {
                try {
                    return Optional.of(Integer.parseInt(value));
                } catch (NumberFormatException e) {
                    return Optional.<Integer>empty();
                }
            })
            .orElse(fallback);
    }
}