package com.oj.common.web.exception;

import com.oj.common.core.exception.BusinessException;
import com.oj.common.core.exception.ErrorCode;
import com.oj.common.core.result.Result;
import com.oj.common.core.result.TraceIdHolder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.BindException;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.NoHandlerFoundException;

import java.util.stream.Collectors;

/**
 * 全局异常处理器（设计文档 §1.3「code 与 HTTP 状态码严格同值」的落地保证）。
 *
 * <p><b>它解决什么问题：</b>Spring 默认把未捕获异常转成 500，body 是 Whitelabel 页面或
 * 一段无结构的 JSON。前端 {@code api/http.js} 拿到后无法区分「参数错(400)」与
 * 「服务炸了(500)」，只能统一弹「服务器内部错误」——答辩演示时看起来像系统挂了。
 * 本类把**所有**异常统一转成 {@link Result}，并让 HTTP 状态码 = body.code。</p>
 *
 * <p><b>铁律：</b>只允许映射到 {@link ErrorCode} 的 10 个取值，绝不新增状态码。</p>
 *
 * <p><b>为什么不用 @ResponseStatus：</b>它只能固定一个码，无法把
 * {@code BusinessException.getCode()} 动态透出去。</p>
 *
 * @author oj-cloud
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /**
     * 业务异常：可预期的流程分支（配额耗尽、越权、乐观锁冲突……）。
     *
     * <p>按 WARN 记录且**不打堆栈**：这类异常在热路径上高频出现（判题防抖、配额校验），
     * 打堆栈会让日志量翻十几倍且毫无信息量。真正要查的是 traceId。</p>
     *
     * @param ex 业务异常
     * @return 状态码与 code 同值的统一响应
     */
    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<Result<Void>> handleBusiness(BusinessException ex) {
        int code = ex.getCode();
        if (!ErrorCode.isStandardCode(code)) {
            // 兜底：任何越界的 code 都收敛成 500，守住「只用 9 大错误码」的铁律
            log.error("业务异常使用了非法错误码 {}，已收敛为 500：{}", code, ex.getMessage());
            code = ErrorCode.INTERNAL_ERROR.getCode();
        }
        log.warn("[{}] 业务异常 {}：{}", TraceIdHolder.current(), code, ex.getMessage());
        Result<Void> body = Result.fail(code, ex.getMessage());
        return ResponseEntity.status(HttpStatus.valueOf(code)).body(body);
    }

    /**
     * 参数校验失败：{@code @Valid} 抛出的 BindException。
     *
     * <p>把字段名与失败原因拼成人话，前端直接展示即可——不要返回
     * 「Validation failed for argument [0]」这种框架原文。</p>
     *
     * @param ex 绑定异常
     * @return 400
     */
    @ExceptionHandler(BindException.class)
    public ResponseEntity<Result<Void>> handleBind(BindException ex) {
        String detail = ex.getBindingResult().getFieldErrors().stream()
                .map(this::describeFieldError)
                .collect(Collectors.joining("；"));
        return badRequest(detail.isEmpty() ? "参数校验失败" : detail);
    }

    /**
     * 请求体不是合法 JSON / 类型不匹配。
     *
     * @param ex 解析异常
     * @return 400
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Result<Void>> handleUnreadable(HttpMessageNotReadableException ex) {
        return badRequest("请求体不是合法 JSON 或字段类型不匹配");
    }

    /**
     * 缺必填查询参数。
     *
     * @param ex 缺参异常
     * @return 400
     */
    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<Result<Void>> handleMissingParam(MissingServletRequestParameterException ex) {
        return badRequest("缺少必填参数：" + ex.getParameterName());
    }

    /**
     * 路径/查询参数类型转换失败（如 {@code /questions/abc} 期望 long）。
     *
     * @param ex 类型不匹配异常
     * @return 400
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<Result<Void>> handleTypeMismatch(MethodArgumentTypeMismatchException ex) {
        return badRequest("参数 " + ex.getName() + " 的取值格式不正确");
    }

    /**
     * 方法不支持（如对只读端点发了 POST）。
     *
     * @param ex 方法不支持异常
     * @return 405 → 收敛为 400（405 不在九码内）
     */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<Result<Void>> handleMethod(HttpRequestMethodNotSupportedException ex) {
        // 405 不在九码内，按基线 §1.6 收敛成 400，绝不新增状态码
        return badRequest("该接口不支持 " + ex.getMethod() + " 方法");
    }

    /**
     * 路径不存在。
     *
     * @param ex 无处理器异常
     * @return 404
     */
    @ExceptionHandler(NoHandlerFoundException.class)
    public ResponseEntity<Result<Void>> handleNoHandler(NoHandlerFoundException ex) {
        Result<Void> body = Result.fail(ErrorCode.NOT_FOUND, "接口不存在：" + ex.getRequestURL());
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(body);
    }

    /**
     * 兜底：所有未预期异常。
     *
     * <p><b>绝不把异常堆栈或 SQL 片段返回给前端</b>——那既是信息泄露，
     * 也让前端无法稳定解析。前端只拿到「服务内部错误 + traceId」，
     * 真实原因靠 traceId 到日志里查。</p>
     *
     * @param ex 未捕获异常
     * @return 500
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Result<Void>> handleUnexpected(Exception ex) {
        String traceId = TraceIdHolder.current();
        log.error("[{}] 未捕获异常", traceId, ex);
        Result<Void> body = Result.fail(ErrorCode.INTERNAL_ERROR,
                "服务内部错误，请联系管理员并提供 traceId：" + (traceId == null ? "—" : traceId));
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(body);
    }

    /** 统一构造 400 响应 */
    private ResponseEntity<Result<Void>> badRequest(String message) {
        Result<Void> body = Result.fail(ErrorCode.BAD_REQUEST, message);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body);
    }

    /** 把 FieldError 拼成「字段 取值 不合法：原因」 */
    private String describeFieldError(FieldError fe) {
        String field = fe.getField();
        String msg = fe.getDefaultMessage();
        return (field == null || field.isBlank()) ? String.valueOf(msg) : field + " " + msg;
    }
}
