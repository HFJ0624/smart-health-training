package com.hfj.fitagent.modules.profile.controller;

import com.hfj.fitagent.common.web.ApiResponse;
import com.hfj.fitagent.modules.profile.dto.UpsertProfileRequest;
import com.hfj.fitagent.modules.profile.service.UserProfileService;
import com.hfj.fitagent.modules.profile.vo.ProfileVo;
import com.hfj.fitagent.security.AuthenticatedUsers;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 档案接口（docs/p1-api.md §4.1–§4.2）。
 *
 * <p>userId 只来自 {@link AuthenticatedUsers#requireId()}：请求体、查询参数、路径里都没有身份入口，
 * 所以"前端传 userId 决定身份"在这层结构上不可能发生。
 */
@RestController
@RequestMapping("/api/me/profile")
public class ProfileController {

    private final UserProfileService profileService;

    public ProfileController(UserProfileService profileService) {
        this.profileService = profileService;
    }

    /**
     * 无档案时返回 200 + {@code data: null}，这是正常空状态而不是 404：
     * 页面要能区分"还没建立档案"与"没权限/数据已变更"。
     */
    @GetMapping
    public ResponseEntity<ApiResponse<ProfileVo>> get() {
        ProfileVo profile = profileService.find(AuthenticatedUsers.requireId());
        return ResponseEntity.ok(ApiResponse.ok(profile));
    }

    @PostMapping
    public ResponseEntity<ApiResponse<ProfileVo>> create(@Valid @RequestBody UpsertProfileRequest request) {
        ProfileVo created = profileService.create(AuthenticatedUsers.requireId(), request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.created(created));
    }

    @PutMapping
    public ResponseEntity<ApiResponse<ProfileVo>> update(@Valid @RequestBody UpsertProfileRequest request) {
        ProfileVo updated = profileService.update(AuthenticatedUsers.requireId(), request);
        return ResponseEntity.ok(ApiResponse.ok(updated));
    }
}
