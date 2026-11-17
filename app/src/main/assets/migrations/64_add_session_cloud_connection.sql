-- 会话级执行目标：null=本机运行，非 null=绑定的远程服务器连接 id。
-- 存活的旧会话缺失该列时回填 null，保持原有本机执行行为。
ALTER TABLE chat_sessions ADD COLUMN cloudConnectionId TEXT;
