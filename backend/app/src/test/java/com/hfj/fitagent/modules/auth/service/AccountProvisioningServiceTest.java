package com.hfj.fitagent.modules.auth.service;

import com.hfj.fitagent.common.error.BizException;
import com.hfj.fitagent.common.error.ErrorCode;
import com.hfj.fitagent.modules.auth.entity.UserAccountEntity;
import com.hfj.fitagent.modules.auth.mapper.UserAccountMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 并发首登只注册一个账号的正确性来自唯一索引，这里验证的是"撞索引之后必须复用既有行"这条代码路径
 * （真实索引行为需 MySQL 8.4 验证，见 docs/p1-database.md §2.2，本用例只覆盖异常翻译后的分支）。
 */
@ExtendWith(MockitoExtension.class)
class AccountProvisioningServiceTest {

    private static final String APP_ID = "wx0A1b2C3d4E5f6g7h";

    @Mock
    private UserAccountMapper userAccounts;

    private AccountProvisioningService provisioning;

    @BeforeEach
    void setUp() {
        provisioning = new AccountProvisioningService(userAccounts, new AccountRegistrar(userAccounts));
    }

    @Test
    @DisplayName("重复登录复用同一账号，不产生第二次插入")
    void repeatLoginReusesExistingAccount() {
        UserAccountEntity existing = account(11L, "oX-existing");
        when(userAccounts.findByAppIdAndOpenId(APP_ID, "oX-existing")).thenReturn(existing);

        AccountProvisioningService.Provisioned result = provisioning.provision(APP_ID, "oX-existing", null);

        assertThat(result.account()).isSameAs(existing);
        assertThat(result.newAccount()).isFalse();
        verify(userAccounts, never()).insert(any(UserAccountEntity.class));
    }

    @Test
    @DisplayName("首次登录自动注册：ACTIVE/USER/Asia/Shanghai，且不写数据库拥有的列")
    void firstLoginCreatesAccount() {
        when(userAccounts.findByAppIdAndOpenId(APP_ID, "oX-new")).thenReturn(null);
        when(userAccounts.insert(any(UserAccountEntity.class))).thenAnswer(invocation -> {
            invocation.<UserAccountEntity>getArgument(0).setId(42L);
            return 1;
        });

        AccountProvisioningService.Provisioned result = provisioning.provision(APP_ID, "oX-new", "  uN-9  ");

        assertThat(result.newAccount()).isTrue();
        assertThat(result.account().getId()).isEqualTo(42L);

        ArgumentCaptor<UserAccountEntity> inserted = ArgumentCaptor.forClass(UserAccountEntity.class);
        verify(userAccounts).insert(inserted.capture());
        UserAccountEntity entity = inserted.getValue();
        assertThat(entity.getStatus()).isEqualTo("ACTIVE");
        assertThat(entity.getRole()).isEqualTo("USER");
        assertThat(entity.getTimezone()).isEqualTo("Asia/Shanghai");
        assertThat(entity.getWechatUnionId()).isEqualTo("uN-9");
        // data_revision / created_at / updated_at 由数据库默认值维护，Java 侧不得预置
        assertThat(entity.getDataRevision()).isNull();
        assertThat(entity.getCreatedAt()).isNull();
        assertThat(entity.getUpdatedAt()).isNull();
    }

    @Test
    @DisplayName("并发首登撞唯一索引后返回竞争窗口先插入的那一行，且不算新建")
    void concurrentDuplicateReturnsExistingRow() {
        UserAccountEntity winner = account(77L, "oX-race");
        when(userAccounts.findByAppIdAndOpenId(APP_ID, "oX-race")).thenReturn(null, winner);
        when(userAccounts.insert(any(UserAccountEntity.class))).thenThrow(new DuplicateKeyException("uq_user_account_1"));

        AccountProvisioningService.Provisioned result = provisioning.provision(APP_ID, "oX-race", null);

        assertThat(result.account()).isSameAs(winner);
        assertThat(result.newAccount()).isFalse();
    }

    @Test
    @DisplayName("撞索引后仍查不到账号属数据异常，不能伪装成注册成功")
    void duplicateWithoutVisibleRowFails() {
        when(userAccounts.findByAppIdAndOpenId(anyString(), anyString())).thenReturn(null);
        when(userAccounts.insert(any(UserAccountEntity.class))).thenThrow(new DuplicateKeyException("uq_user_account_1"));

        assertThatThrownBy(() -> provisioning.provision(APP_ID, "oX-lost", null))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).errorCode())
                .isEqualTo(ErrorCode.INTERNAL_ERROR);
    }

    @Test
    @DisplayName("身份标识只 trim，绝不折叠大小写（openid 大小写有意义）")
    void externalIdsKeepCaseAndAreTrimmed() {
        when(userAccounts.findByAppIdAndOpenId("APP-Id", "oX-MixedCASE")).thenReturn(null);
        when(userAccounts.insert(any(UserAccountEntity.class))).thenAnswer(invocation -> {
            invocation.<UserAccountEntity>getArgument(0).setId(5L);
            return 1;
        });

        provisioning.provision(" APP-Id ", "oX-MixedCASE ", null);

        verify(userAccounts).findByAppIdAndOpenId("APP-Id", "oX-MixedCASE");
        ArgumentCaptor<UserAccountEntity> inserted = ArgumentCaptor.forClass(UserAccountEntity.class);
        verify(userAccounts).insert(inserted.capture());
        assertThat(inserted.getValue().getWechatOpenId()).isEqualTo("oX-MixedCASE");
        assertThat(inserted.getValue().getWechatAppId()).isEqualTo("APP-Id");
    }

    @Test
    @DisplayName("缺少 appId 属后端配置问题，缺 openid 属微信侧失败，两者错误码不同")
    void missingIdentifiersMapToDifferentErrors() {
        assertThatThrownBy(() -> provisioning.provision("  ", "oX-1", null))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).errorCode())
                .isEqualTo(ErrorCode.WECHAT_CONFIG_MISSING);

        assertThatThrownBy(() -> provisioning.provision(APP_ID, "   ", null))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).errorCode())
                .isEqualTo(ErrorCode.WECHAT_LOGIN_FAILED);

        verify(userAccounts, never()).insert(any(UserAccountEntity.class));
    }

    @Test
    @DisplayName("findOrCreate 与 provision 走同一条路径，只是丢弃是否新建的信息")
    void findOrCreateDelegates() {
        UserAccountEntity existing = account(3L, "oX-3");
        when(userAccounts.findByAppIdAndOpenId(APP_ID, "oX-3")).thenReturn(existing);

        assertThat(provisioning.findOrCreate(APP_ID, "oX-3", null)).isSameAs(existing);
    }

    private static UserAccountEntity account(long id, String openId) {
        UserAccountEntity entity = new UserAccountEntity();
        entity.setId(id);
        entity.setWechatAppId(APP_ID);
        entity.setWechatOpenId(openId);
        entity.setStatus("ACTIVE");
        entity.setRole("USER");
        entity.setTimezone("Asia/Shanghai");
        entity.setDataRevision(0L);
        return entity;
    }
}
