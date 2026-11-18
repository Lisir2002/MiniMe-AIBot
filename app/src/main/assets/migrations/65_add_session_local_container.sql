-- 会话级本地容器：null=内置 Alpine（向后兼容存量本机会话），非 null=绑定的本地容器 profile id。
-- 与 cloudConnectionId 一起表达「一个会话一个执行环境」；仅当 cloudConnectionId IS NULL 时有意义。
ALTER TABLE chat_sessions ADD COLUMN localContainerId TEXT;
