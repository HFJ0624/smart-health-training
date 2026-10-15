-- 只在容器第一次创建数据目录时执行（docker-entrypoint-initdb.d）。
-- 结构变更一律走 backend/app/src/main/resources/db/migration 下的 Flyway 脚本。
--
-- 库与账号由 compose 的环境变量创建，这里只做两件事：
--   1) 显式固化服务端字符集与排序规则，避免不同镜像默认值漂移；
--   2) 给 fitagent 账号补齐后续迁移需要的最小 DDL 权限。
-- 不给 SUPER / CREATE ROUTINE，也不授予全局权限：迁移账号只该看得见这一个 schema。

SET NAMES utf8mb4;

GRANT SELECT, INSERT, UPDATE, DELETE, CREATE, ALTER, INDEX, REFERENCES, SHOW VIEW, TRIGGER
  ON `fitagent`.* TO 'fitagent'@'%';
FLUSH PRIVILEGES;
