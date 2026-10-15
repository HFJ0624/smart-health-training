package com.hfj.fitagent.modules.profile.controller;

import com.hfj.fitagent.common.AppClock;
import com.hfj.fitagent.common.web.ApiResponse;
import com.hfj.fitagent.common.web.DateRange;
import com.hfj.fitagent.common.web.PageQuery;
import com.hfj.fitagent.common.web.PageResult;
import com.hfj.fitagent.common.web.SortColumns;
import com.hfj.fitagent.modules.profile.dto.CreateBodyRecordRequest;
import com.hfj.fitagent.modules.profile.dto.UpdateBodyRecordRequest;
import com.hfj.fitagent.modules.profile.service.BodyMeasurementService;
import com.hfj.fitagent.modules.profile.vo.BodyRecordVo;
import com.hfj.fitagent.security.AuthenticatedUsers;
import com.hfj.fitagent.security.CurrentUser;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

/**
 * 体重记录接口（docs/p1-api.md §5）。
 *
 * <p>排序白名单：{@code measuredAt} {@code businessDate} {@code weightKg}，默认 {@code measuredAt,desc}。
 * 请求侧字段名先映射成列名，再作为 MyBatis-Plus 的排序条件传入，属性名本身永不进入 SQL。
 */
@RestController
@RequestMapping("/api/body-records")
public class BodyRecordController {

    /** 本接口的排序白名单：请求侧字段名 → 数据库列名。公开是为了让单测直接复用同一份映射。 */
    public static final SortColumns SORT = SortColumns.of(
            "measuredAt", "measured_at",
            "businessDate", "business_date",
            "weightKg", "weight_kg");

    public static final String DEFAULT_SORT_FIELD = "measuredAt";

    /** 契约规定的默认窗口：最近 90 天（含今天）。 */
    public static final int DEFAULT_WINDOW_DAYS = 90;

    private final BodyMeasurementService measurementService;
    private final AppClock clock;

    public BodyRecordController(BodyMeasurementService measurementService, AppClock clock) {
        this.measurementService = measurementService;
        this.clock = clock;
    }

    @GetMapping
    public ResponseEntity<ApiResponse<PageResult<BodyRecordVo>>> list(
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size,
            @RequestParam(required = false) String sort) {
        CurrentUser user = AuthenticatedUsers.require();
        // 默认窗口按用户的业务时区算"今天"，而不是按服务器时区或 SQL 里的 NOW()
        LocalDate today = clock.businessDate(user.zone());
        DateRange range = DateRange.of(from, to, today.minusDays(DEFAULT_WINDOW_DAYS - 1L), today);
        PageQuery query = PageQuery.of(page, size, sort, SORT.allowedFields(), DEFAULT_SORT_FIELD);
        PageResult<BodyRecordVo> result = measurementService.list(user.id(), range, query, SORT);
        return ResponseEntity.ok(ApiResponse.ok(result));
    }

    /**
     * 最近一次测量：唯一口径是 Mapper 的 {@code ORDER BY measured_at DESC, id DESC LIMIT 1}；
     * 无记录返回 {@code data: null}（200）。
     */
    @GetMapping("/latest")
    public ResponseEntity<ApiResponse<BodyRecordVo>> latest() {
        return ResponseEntity.ok(ApiResponse.ok(measurementService.latest(AuthenticatedUsers.requireId())));
    }

    /**
     * 新增。真正写入才 201；命中 (user_id, client_record_uuid) 唯一索引的重试回读既有行并以 200 返回，
     * 响应里 {@code created=false, idempotentReplay=true}，行数不变（验收 A6）。
     */
    @PostMapping
    public ResponseEntity<ApiResponse<BodyRecordVo>> create(@Valid @RequestBody CreateBodyRecordRequest request) {
        CurrentUser user = AuthenticatedUsers.require();
        BodyRecordVo record = measurementService.create(user.id(), user.zone(), request);
        boolean created = Boolean.TRUE.equals(record.created());
        return ResponseEntity.status(created ? HttpStatus.CREATED : HttpStatus.OK)
                .body(created ? ApiResponse.created(record) : ApiResponse.ok(record));
    }

    @PutMapping("/{id}")
    public ResponseEntity<ApiResponse<BodyRecordVo>> update(@PathVariable long id,
                                                            @Valid @RequestBody UpdateBodyRecordRequest request) {
        CurrentUser user = AuthenticatedUsers.require();
        return ResponseEntity.ok(ApiResponse.ok(measurementService.update(user.id(), id, user.zone(), request)));
    }

    /** 删除后 {@code /latest} 由排序口径天然回退（验收 A8）。 */
    @DeleteMapping("/{id}")
    public ResponseEntity<ApiResponse<Void>> delete(@PathVariable long id) {
        measurementService.delete(AuthenticatedUsers.requireId(), id);
        return ResponseEntity.ok(ApiResponse.ok());
    }
}
